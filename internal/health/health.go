// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package health answers the probes the Java build answered, on the same paths and in the same
// JSON shape (ADR-0031), so probes, dashboards and scripts keep working unchanged:
//
//	GET /actuator/health            {"status":"UP","groups":["liveness","readiness"]}
//	GET /actuator/health/liveness   {"status":"UP"}
//	GET /actuator/health/readiness  {"status":"UP"}
//
// Liveness means the process is serving. Readiness means both that the schema check has passed
// and that the database answers now; when it is not ready it answers OUT_OF_SERVICE (503), the word
// the Java build's readiness group used, before the check, while the database is unreachable, and
// from the moment shutdown begins. The aggregate answers 503 whenever readiness does: DOWN while
// the database is unreachable (as the Java build's database indicator did), OUT_OF_SERVICE
// otherwise. Details are never shown, as in the Java build without credentials.
package health

import (
	"context"
	"net/http"
	"strings"
	"sync/atomic"
	"time"
)

// Pinger is what readiness asks: can the database be reached right now.
type Pinger interface {
	Ping(ctx context.Context) error
}

// pingTimeout bounds one readiness probe's database round trip.
const pingTimeout = 2 * time.Second

// Health holds the readiness switch and answers the probes.
type Health struct {
	db    Pinger
	ready atomic.Bool
}

// New returns a Health that is live and not ready.
func New(db Pinger) *Health {
	return &Health{db: db}
}

// SetReady turns readiness on once the schema check has passed, and off when shutdown begins.
func (h *Health) SetReady(ready bool) { h.ready.Store(ready) }

// Register adds the three probe routes to mux.
func (h *Health) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /actuator/health", h.aggregate)
	mux.HandleFunc("GET /actuator/health/liveness", h.liveness)
	mux.HandleFunc("GET /actuator/health/readiness", h.readiness)
}

// Ready reports readiness: the schema check passed, shutdown has not begun, and the database
// answers.
func (h *Health) Ready(ctx context.Context) bool {
	return h.ready.Load() && h.dbUp(ctx)
}

func (h *Health) dbUp(ctx context.Context) bool {
	ctx, cancel := context.WithTimeout(ctx, pingTimeout)
	defer cancel()
	return h.db.Ping(ctx) == nil
}

func (h *Health) aggregate(w http.ResponseWriter, r *http.Request) {
	dbUp := h.dbUp(r.Context())
	switch {
	case !dbUp:
		write(w, r, http.StatusServiceUnavailable, `{"status":"DOWN","groups":["liveness","readiness"]}`)
	case !h.ready.Load():
		write(w, r, http.StatusServiceUnavailable, `{"status":"OUT_OF_SERVICE","groups":["liveness","readiness"]}`)
	default:
		write(w, r, http.StatusOK, `{"status":"UP","groups":["liveness","readiness"]}`)
	}
}

func (h *Health) liveness(w http.ResponseWriter, r *http.Request) {
	write(w, r, http.StatusOK, `{"status":"UP"}`)
}

func (h *Health) readiness(w http.ResponseWriter, r *http.Request) {
	if h.Ready(r.Context()) {
		write(w, r, http.StatusOK, `{"status":"UP"}`)
		return
	}
	write(w, r, http.StatusServiceUnavailable, `{"status":"OUT_OF_SERVICE"}`)
}

// actuatorV3 is the media type Spring Boot's actuator answers with unless the caller asks for
// plain application/json.
const actuatorV3 = "application/vnd.spring-boot.actuator.v3+json"

func write(w http.ResponseWriter, r *http.Request, status int, body string) {
	contentType := actuatorV3
	if accept := r.Header.Get("Accept"); strings.Contains(accept, "application/json") && !strings.Contains(accept, "actuator") {
		contentType = "application/json"
	}
	w.Header().Set("Content-Type", contentType)
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}
