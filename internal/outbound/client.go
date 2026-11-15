// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package outbound

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strings"
	"sync"
	"time"
)

// Config bounds a Client. A zero value is the default.
type Config struct {
	ConnectTimeout time.Duration
	AnswerTimeout  time.Duration
	// DialContext, when set, opens the connections in place of a net.Dialer; the connect timeout
	// still bounds it. Tests use it to make a connection slow.
	DialContext    func(ctx context.Context, network, addr string) (net.Conn, error)
	MaxAnswerBytes int64
	// TLSConfig is the client's TLS configuration, for tests. Nil is the system roots.
	TLSConfig *tls.Config
}

// Client posts a signed message to another party and reads its whole answer: the payer's side of
// a payment notification (Story 10.6) and of a payload request (Story 10.7). The destination came
// out of something the caller cannot vouch for, so no redirect is followed, no proxy is read from
// the environment, the connection and the answer have timeouts, and the answer has a size cap.
// It is safe for concurrent use.
type Client struct {
	client    *http.Client
	transport *http.Transport
	connectIn time.Duration
	answerIn  time.Duration
	maxBytes  int64
}

// NewClient returns a Client bounded by cfg.
func NewClient(cfg Config) *Client {
	connect := cfg.ConnectTimeout
	if connect <= 0 {
		connect = DefaultConnectTimeout
	}
	answer := cfg.AnswerTimeout
	if answer <= 0 {
		answer = DefaultAnswerTimeout
	}
	maxBytes := cfg.MaxAnswerBytes
	if maxBytes <= 0 {
		maxBytes = DefaultMaxAnswerBytes
	}
	dial := (&net.Dialer{Timeout: connect}).DialContext
	if cfg.DialContext != nil {
		dial = func(ctx context.Context, network, addr string) (net.Conn, error) {
			ctx, cancel := context.WithTimeout(ctx, connect)
			defer cancel()
			return cfg.DialContext(ctx, network, addr)
		}
	}
	transport := &http.Transport{
		// Java's HttpClient reads no proxy from the environment; neither does this.
		Proxy:               nil,
		DialContext:         dial,
		TLSClientConfig:     cfg.TLSConfig,
		TLSHandshakeTimeout: connect,
		MaxIdleConns:        16,
		IdleConnTimeout:     30 * time.Second,
		ForceAttemptHTTP2:   true,
	}
	return &Client{
		transport: transport,
		client: &http.Client{
			Transport: transport,
			// A redirect is the other party's answer, reported as such, never followed (ADR-0015).
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		connectIn: connect,
		answerIn:  answer,
		maxBytes:  maxBytes,
	}
}

// CloseIdleConnections closes the connections kept open; main calls it once the server has stopped.
func (c *Client) CloseIdleConnections() { c.transport.CloseIdleConnections() }

// errTooLarge is an answer past MaxAnswerBytes.
var errTooLarge = errors.New("the answer is larger than this service reads")

// errAnswerTimeout is a whole answer not received within the answer timeout.
var errAnswerTimeout = errors.New("the whole answer did not arrive in time")

// Post posts body to endpoint with header and returns the answer's status and body. As in the Java
// build, the connection gets the connect timeout, and the whole answer, headers and body, gets the
// answer timeout counted from when the request has been sent. Any failure to get a whole answer is
// an error: the other party could not be reached, closed the connection without answering,
// answered too late, or answered more than this client reads.
func (c *Client) Post(ctx context.Context, endpoint *url.URL, header http.Header, body string) (int, string, error) {
	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)
	// A backstop on everything before the answer clock starts: the dial and the TLS handshake have
	// their own bounds, and this keeps a stalled write from holding the caller.
	sending := time.AfterFunc(c.connectIn+c.answerIn, func() { cancel(errAnswerTimeout) })
	defer sending.Stop()
	var (
		mu       sync.Mutex
		answerBy *time.Timer
	)
	trace := &httptrace.ClientTrace{WroteRequest: func(httptrace.WroteRequestInfo) {
		mu.Lock()
		defer mu.Unlock()
		if answerBy == nil {
			answerBy = time.AfterFunc(c.answerIn, func() { cancel(errAnswerTimeout) })
		}
	}}
	defer func() {
		mu.Lock()
		defer mu.Unlock()
		if answerBy != nil {
			answerBy.Stop()
		}
	}()
	req, err := http.NewRequestWithContext(httptrace.WithClientTrace(ctx, trace), http.MethodPost, endpoint.String(),
		strings.NewReader(body))
	if err != nil {
		return 0, "", err
	}
	for name, values := range header {
		req.Header[name] = append([]string(nil), values...)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return 0, "", errors.Join(err, context.Cause(ctx))
	}
	defer func() { _ = resp.Body.Close() }()
	var answer bytes.Buffer
	n, err := io.Copy(&answer, io.LimitReader(resp.Body, c.maxBytes+1))
	if err != nil {
		return 0, "", errors.Join(err, context.Cause(ctx))
	}
	if n > c.maxBytes {
		return 0, "", errTooLarge
	}
	return resp.StatusCode, answer.String(), nil
}
