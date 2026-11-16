// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package metrics

import (
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// S12.2-AC1: counters are served in the Prometheus text format at /actuator/prometheus.
func TestExposition(t *testing.T) {
	var r Registry
	a := r.Counter("x9_test_rows_total", "Rows.\nTwo lines.", Label{"table", "a"})
	b := r.Counter("x9_test_rows_total", "ignored", Label{"table", `b"\`})
	r.Counter("x9_test_failures_total", "Failures.")
	if r.Counter("x9_test_rows_total", "", Label{"table", "a"}) != a {
		t.Fatal("the same series is not the same counter")
	}
	a.Add(3)
	b.Inc()

	mux := http.NewServeMux()
	r.Register(mux)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, Path, nil))
	body, _ := io.ReadAll(rec.Body)
	want := `# HELP x9_test_rows_total Rows.\nTwo lines.
# TYPE x9_test_rows_total counter
x9_test_rows_total{table="a"} 3
x9_test_rows_total{table="b\"\\"} 1
# HELP x9_test_failures_total Failures.
# TYPE x9_test_failures_total counter
x9_test_failures_total 0
`
	if diff := cmp.Diff(want, string(body)); diff != "" {
		t.Fatalf("exposition (-want +got):\n%s", diff)
	}
	if ct := rec.Header().Get("Content-Type"); ct != contentType {
		t.Fatalf("Content-Type = %q", ct)
	}
}

func TestConcurrentAdds(t *testing.T) {
	var r Registry
	var wg sync.WaitGroup
	for range 16 {
		wg.Go(func() {
			for range 1000 {
				r.Counter("x9_test_total", "").Inc()
			}
		})
	}
	wg.Wait()
	if got := r.Counter("x9_test_total", "").Value(); got != 16_000 {
		t.Fatalf("count = %d, want 16000", got)
	}
}
