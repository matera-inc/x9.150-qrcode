// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package httpapi is how every handler of the Go build reads a request and writes an answer, so
// that a client cannot tell which build it is talking to (ADR-0028). It holds the routes' mux and
// the answers no handler gives (an unknown path, a wrong method, request headers past 30 KB, a
// panic), the refusals in the Java build's problem+json shape, the JSON reader, and the readers of
// ids, If-Match and query parameters.
//
// A handler is a HandlerFunc: it reads with DecodeJSON and the Path/Query helpers, writes with
// WriteJSON, and returns a *Problem (or any error, answered 500 without its words) to refuse.
package httpapi

import (
	"errors"
	"log/slog"
	"net/http"
	"path"
	"strings"
	"time"
)

// MaxHeaderBytes is the request line and headers the Java build reads, its
// server.max-http-request-header-size of 30 KB, counted as Tomcat counts it: every byte up to and
// including the blank line that ends the headers.
const MaxHeaderBytes = 30 << 10

// HandlerFunc serves a request. A non-nil error is the answer: a *Problem is written as it is, an
// error from SpringError with Spring Boot's error body, and anything else as a 500 internal-error,
// its words logged and never written.
type HandlerFunc func(w http.ResponseWriter, r *http.Request) error

// API is the service's routes. It is an http.Handler; main puts it behind internal/httplog and the
// test clock's offset header.
type API struct {
	mux    *http.ServeMux
	logger *slog.Logger
	now    func() time.Time
}

// New returns an API with no routes. now is the business clock, read only for the timestamp of
// Spring Boot's error body.
func New(logger *slog.Logger, now func() time.Time) *API {
	return &API{mux: http.NewServeMux(), logger: logger, now: now}
}

// Mux is the underlying mux, for handlers that write their own answers (the actuator probes).
func (a *API) Mux() *http.ServeMux { return a.mux }

// Handle registers h for pattern, a net/http method pattern such as "GET /api/v1/payment-request/{id}".
func (a *API) Handle(pattern string, h HandlerFunc) {
	a.mux.Handle(pattern, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := h(w, r); err != nil {
			a.WriteError(w, r, err)
		}
	}))
}

// WriteError answers err as Handle does.
func (a *API) WriteError(w http.ResponseWriter, r *http.Request, err error) {
	var problem *Problem
	var spring *springError
	switch {
	case errors.As(err, &problem):
		WriteProblem(w, r, problem)
	case errors.As(err, &spring):
		writeSpringError(w, r, spring, a.now())
	default:
		a.logger.ErrorContext(r.Context(), "x9-qrcode request failed", "method", r.Method, "path", r.URL.Path,
			"err", err)
		WriteProblem(w, r, InternalError())
	}
}

func (a *API) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if requestHeaderBytes(r) > MaxHeaderBytes {
		writeHeaderTooLarge(w)
		return
	}
	defer a.recoverPanic(w, r)
	// Spring matches the path as it was sent; ServeMux would redirect a path holding "//", "/./" or
	// "/../" to its clean form. A trailing slash goes on to the mux, which has no pattern for it.
	if clean := path.Clean(r.URL.Path); r.URL.Path == "" || clean != r.URL.Path && r.URL.Path != clean+"/" {
		writeSpringError(w, r, &springError{status: http.StatusNotFound}, a.now())
		return
	}
	if _, pattern := a.mux.Handler(r); pattern == "" {
		a.unrouted(w, r)
		return
	}
	a.mux.ServeHTTP(w, r)
}

// recoverPanic answers a handler's panic as a 500 internal-error that carries nothing of the panic,
// which is logged. net/http's own abort sentinel is passed on.
func (a *API) recoverPanic(w http.ResponseWriter, r *http.Request) {
	v := recover()
	if v == nil {
		return
	}
	if v == http.ErrAbortHandler { //nolint:errorlint // the sentinel net/http panics with, compared as net/http does
		panic(v)
	}
	a.logger.ErrorContext(r.Context(), "x9-qrcode request panicked", "method", r.Method, "path", r.URL.Path,
		"panic", v)
	WriteProblem(w, r, InternalError())
}

// unrouted answers a request no pattern takes, as Spring does: 404, or 405 with Allow when the path
// is served under another method; OPTIONS on a served path is 200 with Allow.
func (a *API) unrouted(w http.ResponseWriter, r *http.Request) {
	probe := &recorder{header: http.Header{}, status: http.StatusOK}
	h, _ := a.mux.Handler(r)
	h.ServeHTTP(probe, r)
	if probe.status != http.StatusMethodNotAllowed {
		writeSpringError(w, r, &springError{status: http.StatusNotFound}, a.now())
		return
	}
	allow := strings.ReplaceAll(probe.header.Get("Allow"), ", ", ",")
	if r.Method == http.MethodOptions {
		w.Header().Set("Allow", allow+","+http.MethodOptions)
		w.WriteHeader(http.StatusOK)
		return
	}
	writeSpringError(w, r, &springError{status: http.StatusMethodNotAllowed, header: http.Header{"Allow": {allow}}},
		a.now())
}

// recorder keeps what ServeMux's own 404 and 405 handlers write, to learn which one it was.
type recorder struct {
	header http.Header
	status int
}

func (r *recorder) Header() http.Header         { return r.header }
func (r *recorder) Write(b []byte) (int, error) { return len(b), nil }
func (r *recorder) WriteHeader(status int)      { r.status = status }

// requestHeaderBytes is the size of r's request line and headers as they came on the wire, each
// header line counted as "Name: value\r\n". net/http keeps Host and Transfer-Encoding out of
// r.Header, so they are added back.
func requestHeaderBytes(r *http.Request) int {
	n := len(r.Method) + 1 + len(r.RequestURI) + 1 + len(r.Proto) + 2
	if r.Host != "" {
		n += len("Host: ") + len(r.Host) + 2
	}
	for name, values := range r.Header {
		for _, v := range values {
			n += len(name) + 2 + len(v) + 2
		}
	}
	for _, te := range r.TransferEncoding {
		n += len("Transfer-Encoding: ") + len(te) + 2
	}
	return n + 2
}

// tomcatBadRequest is the page Tomcat answers a request whose headers pass
// max-http-request-header-size with, before any Spring code runs.
const tomcatBadRequest = `<!doctype html><html lang="en"><head><title>HTTP Status 400 – Bad Request</title>` +
	`<style type="text/css">body {font-family:Tahoma,Arial,sans-serif;} h1, h2, h3, b {color:white;` +
	`background-color:#525D76;} h1 {font-size:22px;} h2 {font-size:16px;} h3 {font-size:14px;} ` +
	`p {font-size:12px;} a {color:black;} .line {height:1px;background-color:#525D76;border:none;}</style>` +
	`</head><body><h1>HTTP Status 400 – Bad Request</h1></body></html>`

func writeHeaderTooLarge(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/html;charset=utf-8")
	w.Header().Set("Content-Language", "en")
	w.Header().Set("Connection", "close")
	w.WriteHeader(http.StatusBadRequest)
	_, _ = w.Write([]byte(tomcatBadRequest))
}
