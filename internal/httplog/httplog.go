// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package httplog writes the service's JSON log and one line per HTTP request. The request line
// carries the caller's trace ids, from b3 (what the Java build propagated) or W3C traceparent, so a
// request can be followed across services. There is no exporter (ADR-0031): the ids are logged,
// not sent anywhere.
package httplog

import (
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"time"
)

// NewLogger returns the JSON logger every line of the service goes through.
func NewLogger(w io.Writer, level slog.Leveler) *slog.Logger {
	return slog.New(slog.NewJSONHandler(w, &slog.HandlerOptions{Level: level}))
}

// quiet are the paths probes hit every few seconds; the Java build did not log them either.
var quiet = []string{"/actuator/health", "/actuator/prometheus"}

// Middleware logs every request once it is answered, with its trace ids.
func Middleware(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		for _, p := range quiet {
			if r.URL.Path == p || strings.HasPrefix(r.URL.Path, p+"/") {
				next.ServeHTTP(w, r)
				return
			}
		}
		start := time.Now() //nolint:forbidigo // measures the request's real latency, not business time
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		attrs := []slog.Attr{
			slog.String("method", r.Method),
			slog.String("path", r.URL.Path),
			slog.Int("status", rec.status),
			slog.Int64("durationMs", time.Since(start).Milliseconds()), //nolint:forbidigo // real latency
		}
		if t, ok := TraceFrom(r.Header); ok {
			attrs = append(attrs, slog.String("traceId", t.TraceID), slog.String("spanId", t.SpanID))
		}
		logger.LogAttrs(r.Context(), slog.LevelInfo, "request", attrs...)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
}

func (s *statusRecorder) WriteHeader(code int) {
	if !s.wroteHeader {
		s.status, s.wroteHeader = code, true
	}
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	s.wroteHeader = true
	return s.ResponseWriter.Write(b)
}

// Unwrap lets http.ResponseController reach the underlying writer (flush, deadlines).
func (s *statusRecorder) Unwrap() http.ResponseWriter { return s.ResponseWriter }

// Trace is the caller's trace and span id.
type Trace struct {
	TraceID string
	SpanID  string
}

var (
	traceID64  = regexp.MustCompile(`^([0-9a-f]{16}|[0-9a-f]{32})$`)
	spanID     = regexp.MustCompile(`^[0-9a-f]{16}$`)
	traceID128 = regexp.MustCompile(`^[0-9a-f]{32}$`)
	allZero    = regexp.MustCompile(`^0+$`)
)

// TraceFrom reads the trace ids of an incoming request: the single b3 header, then the X-B3-*
// headers, then traceparent. A malformed header is ignored, never trusted.
func TraceFrom(h http.Header) (Trace, bool) {
	if v := h.Get("b3"); v != "" {
		parts := strings.Split(strings.ToLower(v), "-")
		if len(parts) >= 2 && validB3(parts[0], parts[1]) {
			return Trace{TraceID: parts[0], SpanID: parts[1]}, true
		}
	}
	if t, s := strings.ToLower(h.Get("X-B3-TraceId")), strings.ToLower(h.Get("X-B3-SpanId")); validB3(t, s) {
		return Trace{TraceID: t, SpanID: s}, true
	}
	// traceparent: version-traceid-parentid-flags (W3C Trace Context).
	if v := h.Get("traceparent"); v != "" {
		parts := strings.Split(strings.ToLower(v), "-")
		if len(parts) >= 4 && len(parts[0]) == 2 && parts[0] != "ff" &&
			traceID128.MatchString(parts[1]) && !allZero.MatchString(parts[1]) &&
			spanID.MatchString(parts[2]) && !allZero.MatchString(parts[2]) {
			return Trace{TraceID: parts[1], SpanID: parts[2]}, true
		}
	}
	return Trace{}, false
}

func validB3(trace, span string) bool {
	return traceID64.MatchString(trace) && !allZero.MatchString(trace) &&
		spanID.MatchString(span) && !allZero.MatchString(span)
}
