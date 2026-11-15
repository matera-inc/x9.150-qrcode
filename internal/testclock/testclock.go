// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package testclock is the movable test clock's control surface (ADR-0033), as the Java build
// serves it:
//
//   - GET, PUT and DELETE /test/clock, on a listener of its own, never on the API port, and only
//     when X9_TESTING_CLOCKCONTROL=true. PUT takes exactly one of {"at": "<RFC 3339, to the
//     millisecond at most>"}, {"offsetMillis": n} or {"offsetSeconds": n}; DELETE goes back to real
//     time; all three answer {"now": "…Z", "offsetMillis": n}.
//   - The guard: the listener refuses to start, and PUT answers 403, unless the signing certificate
//     is one bundled with this repository, by SHA-256 fingerprint compiled into the binary.
//   - The X-Test-Clock-Offset response header, on every API answer while the offset is not zero.
//
// The clock is the real one plus the offset, so it keeps ticking; it is never frozen.
package testclock

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
)

const (
	// Path is the clock's one route.
	Path = "/test/clock"
	// Header carries the offset, in signed milliseconds, on every API answer while it is not zero.
	Header = "X-Test-Clock-Offset"

	fieldAt            = "at"
	fieldOffsetMillis  = "offsetMillis"
	fieldOffsetSeconds = "offsetSeconds"
	oneOf              = `{"at": "…"}, {"offsetMillis": n} or {"offsetSeconds": n}`

	// maxBody bounds a PUT body; the largest valid one is a few dozen bytes.
	maxBody = 4 << 10
)

// OffsetHeader sets X-Test-Clock-Offset on every answer of next while c's offset is not zero. With
// the test clock off the offset is always zero, so the header never appears.
func OffsetHeader(c *clock.Clock, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if ms := c.OffsetMillis(); ms != 0 {
			w.Header().Set(Header, strconv.FormatInt(ms, 10))
		}
		next.ServeHTTP(w, r)
	})
}

// CertificateSource returns the signing certificate in use now. The keys can be reloaded at run
// time, so the guard asks it again on every PUT.
type CertificateSource func() *x509.Certificate

// Handler serves /test/clock for c. It is not a route of the API's mux: it is the whole handler
// of the clock's own listener.
func Handler(c *clock.Clock, cert CertificateSource, logger *slog.Logger) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != Path {
			respond(w, http.StatusNotFound, map[string]any{"error": "not found"})
			return
		}
		switch r.Method {
		case http.MethodGet:
			respond(w, http.StatusOK, state(c))
		case http.MethodDelete:
			_ = c.SetOffsetMillis(0) // zero is always within range, and c allows an offset
			logger.Warn("test clock reset to real time")
			respond(w, http.StatusOK, state(c))
		case http.MethodPut:
			put(w, r, c, cert, logger)
		default:
			w.Header().Set("Allow", "GET, PUT, DELETE")
			respond(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		}
	})
}

func put(w http.ResponseWriter, r *http.Request, c *clock.Clock, cert CertificateSource, logger *slog.Logger) {
	// The keys can be reloaded at run time: a real certificate swapped in later stops the clock moving.
	if err := RequireBundled(cert()); err != nil {
		respond(w, http.StatusForbidden, map[string]any{"error": err.Error()})
		return
	}
	fields, err := readBody(http.MaxBytesReader(w, r.Body, maxBody))
	if err != nil {
		respond(w, http.StatusBadRequest, map[string]any{"error": "body must be JSON: " + oneOf})
		return
	}
	given := 0
	for _, f := range []string{fieldAt, fieldOffsetMillis, fieldOffsetSeconds} {
		if fields[f] != nil {
			given++
		}
	}
	if given != 1 {
		respond(w, http.StatusBadRequest, map[string]any{"error": "send exactly one of " + oneOf})
		return
	}
	if err := apply(c, fields); err != nil {
		respond(w, http.StatusBadRequest, map[string]any{"error": err.Error()})
		return
	}
	logger.Warn("test clock moved", "offsetMillis", c.OffsetMillis())
	respond(w, http.StatusOK, state(c))
}

// readBody decodes the body's first JSON value. An empty body, or one that is not an object, has
// no fields, as Jackson's readTree reads it; the fields' numbers keep their text.
func readBody(body io.Reader) (map[string]any, error) {
	dec := json.NewDecoder(body)
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		if errors.Is(err, io.EOF) {
			return nil, nil
		}
		return nil, err
	}
	fields, _ := v.(map[string]any)
	return fields, nil
}

// apply moves c as the one field given says, or says why it cannot, in the Java build's words.
func apply(c *clock.Clock, fields map[string]any) error {
	switch {
	case fields[fieldAt] != nil:
		at, err := parseAt(fields[fieldAt])
		if err != nil {
			return err
		}
		return c.SetNow(at)
	case fields[fieldOffsetMillis] != nil:
		ms, err := integer(fields, fieldOffsetMillis)
		if err != nil {
			return err
		}
		return c.SetOffsetMillis(ms)
	default:
		s, err := integer(fields, fieldOffsetSeconds)
		if err != nil {
			return err
		}
		if s > math.MaxInt64/1000 || s < math.MinInt64/1000 {
			return errors.New(fieldOffsetSeconds + " is too large")
		}
		return c.SetOffsetMillis(s * 1000)
	}
}

var errAt = errors.New("at must be an RFC 3339 instant, e.g. 2031-12-31T23:59:59.999Z")

// parseAt reads an RFC 3339 instant given to the millisecond at most: anything finer could not be
// honoured by a millisecond offset.
func parseAt(v any) (time.Time, error) {
	text, ok := v.(string)
	if !ok {
		return time.Time{}, errAt
	}
	at, err := time.Parse(time.RFC3339, text)
	if err != nil {
		return time.Time{}, errAt
	}
	if at.Nanosecond()%int(time.Millisecond) != 0 {
		return time.Time{}, errors.New("at must be given to the millisecond at most, e.g. 2031-12-31T23:59:59.999Z")
	}
	return at, nil
}

// integerLiteral is a JSON number with no fraction and no exponent: what Jackson calls integral.
var integerLiteral = regexp.MustCompile(`^-?(0|[1-9][0-9]*)$`)

func integer(fields map[string]any, name string) (int64, error) {
	n, ok := fields[name].(json.Number)
	if ok && integerLiteral.MatchString(n.String()) {
		if v, err := strconv.ParseInt(n.String(), 10, 64); err == nil {
			return v, nil
		}
	}
	return 0, errors.New(name + " must be an integer")
}

// state is every answer's body: the business "now", to the millisecond, and the offset.
func state(c *clock.Clock) map[string]any {
	return map[string]any{"now": formatInstant(c.Now()), fieldOffsetMillis: c.OffsetMillis()}
}

// formatInstant writes t as Java's Instant.toString does once truncated to the millisecond: no
// fraction when it is zero, three digits otherwise.
func formatInstant(t time.Time) string {
	t = t.UTC().Truncate(time.Millisecond)
	if t.Nanosecond() == 0 {
		return t.Format("2006-01-02T15:04:05Z")
	}
	return t.Format("2006-01-02T15:04:05.000Z")
}

func respond(w http.ResponseWriter, status int, body map[string]any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(body) // the client may have gone; nothing else to tell it
}

// Listener is the clock's own HTTP listener.
type Listener struct {
	srv    *http.Server
	addr   net.Addr
	served chan error
	clock  *clock.Clock
}

// Start checks the guard, then serves Handler on addr, a listener of its own, and logs a WARN that
// this deployment's business time can be moved. It refuses a clock that cannot be moved, and a
// signing certificate that is not bundled.
func Start(ctx context.Context, addr string, c *clock.Clock, cert CertificateSource, logger *slog.Logger) (*Listener, error) {
	if !c.OffsetAllowed() {
		return nil, errors.New("the test clock listener needs a clock built with clock.AllowOffset")
	}
	if err := RequireBundled(cert()); err != nil {
		return nil, err
	}
	l, err := (&net.ListenConfig{}).Listen(ctx, "tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("could not bind the test clock listener to %s: %w", addr, err)
	}
	srv := &http.Server{
		Handler:           Handler(c, cert, logger),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    30 << 10,
	}
	tl := &Listener{srv: srv, addr: l.Addr(), served: make(chan error, 1), clock: c}
	go func() { tl.served <- srv.Serve(l) }()
	logger.Warn("TEST CLOCK CONTROL IS ON (X9_TESTING_CLOCKCONTROL=true): /test/clock can move this deployment's "+
		"business time. This is a test deployment; it must never handle real payments.", "addr", tl.addr.String())
	return tl, nil
}

// Addr is the address actually bound: it differs from the configured one when that has port 0.
func (l *Listener) Addr() net.Addr { return l.addr }

// Shutdown stops the listener, waits for it, and puts the clock back on real time.
func (l *Listener) Shutdown(ctx context.Context) error {
	err := l.srv.Shutdown(ctx)
	if err != nil {
		_ = l.srv.Close()
	}
	if e := <-l.served; !errors.Is(e, http.ErrServerClosed) {
		err = errors.Join(err, e)
	}
	_ = l.clock.SetOffsetMillis(0) // zero is always within range, and the clock allows an offset
	return err
}
