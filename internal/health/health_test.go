// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package health

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

type fakeDB struct{ down atomic.Bool }

func (f *fakeDB) Ping(context.Context) error {
	if f.down.Load() {
		return errors.New("connection refused")
	}
	return nil
}

func get(t *testing.T, h http.Handler, path, accept string) (int, string, string) {
	t.Helper()
	req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, path, nil)
	if accept != "" {
		req.Header.Set("Accept", accept)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec.Code, rec.Body.String(), rec.Header().Get("Content-Type")
}

// S7.3-AC1: the three paths, in the Java build's JSON shape; readiness DOWN until the schema check
// passes and while the database is unreachable.
func TestProbes(t *testing.T) {
	db := &fakeDB{}
	h := New(db)
	mux := http.NewServeMux()
	h.Register(mux)

	type answer struct {
		code int
		body string
	}
	check := func(stage string, want map[string]answer) {
		t.Helper()
		for path, w := range want {
			code, body, _ := get(t, mux, path, "")
			if code != w.code || body != w.body {
				t.Errorf("%s: %s = %d %s, want %d %s", stage, path, code, body, w.code, w.body)
			}
		}
	}
	up := answer{http.StatusOK, `{"status":"UP"}`}
	down := answer{http.StatusServiceUnavailable, `{"status":"DOWN"}`}
	aggUp := answer{http.StatusOK, `{"status":"UP","groups":["liveness","readiness"]}`}
	aggDown := answer{http.StatusServiceUnavailable, `{"status":"DOWN","groups":["liveness","readiness"]}`}

	check("before the schema check", map[string]answer{
		"/actuator/health": aggDown, "/actuator/health/liveness": up, "/actuator/health/readiness": down,
	})
	h.SetReady(true)
	check("ready", map[string]answer{
		"/actuator/health": aggUp, "/actuator/health/liveness": up, "/actuator/health/readiness": up,
	})
	db.down.Store(true)
	check("database unreachable", map[string]answer{
		"/actuator/health": aggDown, "/actuator/health/liveness": up, "/actuator/health/readiness": down,
	})
	db.down.Store(false)
	check("database back", map[string]answer{"/actuator/health/readiness": up})
	h.SetReady(false)
	check("shutting down", map[string]answer{
		"/actuator/health": aggDown, "/actuator/health/liveness": up, "/actuator/health/readiness": down,
	})
}

// The content type is Spring Boot's actuator type, or application/json when that is what the
// caller asked for.
func TestContentType(t *testing.T) {
	mux := http.NewServeMux()
	New(&fakeDB{}).Register(mux)
	for accept, want := range map[string]string{
		"":                 actuatorV3,
		"*/*":              actuatorV3,
		"application/json": "application/json",
		actuatorV3:         actuatorV3,
	} {
		if _, _, got := get(t, mux, "/actuator/health/liveness", accept); got != want {
			t.Errorf("Accept %q: Content-Type %q, want %q", accept, got, want)
		}
	}
}
