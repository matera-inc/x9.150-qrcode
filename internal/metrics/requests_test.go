// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package metrics

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func scrape(t *testing.T, r *Registry) string {
	t.Helper()
	var b strings.Builder
	r.Write(&b)
	return b.String()
}

// X9-OPS-002 (S14.1-AC2): every request is timed under the Java build's family, type and labels,
// and its uri label is the route's template, never the raw path: a QR Code id in a label would make
// one series per QR Code. A request no route takes is "/**" when it is a 404 and UNKNOWN otherwise,
// as Spring labelled them.
func TestRequestsAreLabelledByRouteTemplate(t *testing.T) {
	var reg Registry
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/payment-request/{id}", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	})
	mux.HandleFunc("/pub/api/v1/payment-notification", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok"))
	})
	h := reg.Requests(mux, mux)
	for _, req := range []struct{ method, path string }{
		{http.MethodGet, "/api/v1/payment-request/01A1E638C4AB1EB442D140F15C9758A2"},
		{http.MethodGet, "/api/v1/payment-request/01A1E638C4AB1EB442D140F15C9758FF"},
		{http.MethodPost, "/pub/api/v1/payment-notification"},
		{http.MethodGet, "/nowhere/01A1E638C4AB1EB442D140F15C9758A2"},
		{http.MethodDelete, "/api/v1/payment-request/01A1E638C4AB1EB442D140F15C9758A2"},
	} {
		h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequestWithContext(t.Context(), req.method, req.path, nil))
	}
	page := scrape(t, &reg)
	for _, want := range []string{
		"# TYPE http_server_requests_seconds summary\n",
		"# TYPE http_server_requests_seconds_max gauge\n",
		`http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="CLIENT_ERROR",status="404",uri="/api/v1/payment-request/{id}"} 2` + "\n",
		`http_server_requests_seconds_sum{error="none",exception="none",method="GET",outcome="CLIENT_ERROR",status="404",uri="/api/v1/payment-request/{id}"} `,
		`http_server_requests_seconds_max{error="none",exception="none",method="GET",outcome="CLIENT_ERROR",status="404",uri="/api/v1/payment-request/{id}"} `,
		`http_server_requests_seconds_count{error="none",exception="none",method="POST",outcome="SUCCESS",status="200",uri="/pub/api/v1/payment-notification"} 1` + "\n",
		`http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="CLIENT_ERROR",status="404",uri="/**"} 1` + "\n",
		`http_server_requests_seconds_count{error="none",exception="none",method="DELETE",outcome="CLIENT_ERROR",status="405",uri="UNKNOWN"} 1` + "\n",
	} {
		if !strings.Contains(page, want) {
			t.Errorf("missing %q in:\n%s", want, page)
		}
	}
	if strings.Contains(page, "01A1E638") {
		t.Errorf("a raw id reached a label:\n%s", page)
	}
}

// X9-OPS-003 (S14.1-AC2): http_server_requests_seconds_max is Micrometer's time-window maximum:
// the longest request is remembered for at least 80 and at most 120 seconds, then forgotten.
func TestRequestMaxIsATimeWindow(t *testing.T) {
	t0 := time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC)
	var m windowMax
	m.record(t0, 0.5)
	m.record(t0.Add(50*time.Second), 0.2) // after the first rotation, at 40 s
	for _, c := range []struct {
		at   time.Duration
		want float64
	}{
		{0, 0.5}, {79 * time.Second, 0.5}, {119 * time.Second, 0.5}, // 0.5 lives 120 s
		{120 * time.Second, 0.2}, {159 * time.Second, 0.2}, // 0.2 lives 110 s
		{160 * time.Second, 0},
	} {
		if got := m.value(t0.Add(c.at)); got != c.want {
			t.Errorf("max after %v = %v, want %v", c.at, got, c.want)
		}
	}
	m.record(t0.Add(time.Hour), 0.1)
	if got := m.value(t0.Add(time.Hour)); got != 0.1 {
		t.Errorf("max after a long idle = %v, want 0.1", got)
	}
}

func TestOutcome(t *testing.T) {
	for status, want := range map[int]string{
		101: "INFORMATIONAL", 200: "SUCCESS", 201: "SUCCESS", 304: "REDIRECTION", 400: "CLIENT_ERROR",
		503: "SERVER_ERROR", 0: "UNKNOWN", 600: "UNKNOWN",
	} {
		if got := outcome(status); got != want {
			t.Errorf("outcome(%d) = %s, want %s", status, got, want)
		}
	}
}

// X9-OPS-004 (S14.1-AC2): the process and Go runtime gauges are served, each with its type.
func TestProcessAndRuntimeFamilies(t *testing.T) {
	var reg Registry
	reg.Process()
	reg.GoRuntime()
	page := scrape(t, &reg)
	for _, want := range []string{
		"# TYPE process_start_time_seconds gauge\n",
		"# TYPE process_uptime_seconds gauge\n",
		"# TYPE process_cpu_time_ns_total counter\n",
		"# TYPE process_files_open_files gauge\n",
		"# TYPE process_files_max_files gauge\n",
		"# TYPE system_cpu_count gauge\n",
		"# TYPE go_info gauge\n",
		"# TYPE go_goroutines gauge\n",
		"# TYPE go_threads gauge\n",
		"# TYPE go_sched_gomaxprocs_threads gauge\n",
		"# TYPE go_gc_gomemlimit_bytes gauge\n",
		"# TYPE go_memstats_alloc_bytes gauge\n",
		"# TYPE go_memstats_alloc_bytes_total counter\n",
		"# TYPE go_memstats_sys_bytes gauge\n",
		"# TYPE go_gc_cycles_total counter\n",
	} {
		if !strings.Contains(page, want) {
			t.Errorf("missing %q in:\n%s", want, page)
		}
	}
	_, rest, _ := strings.Cut(page, "\nprocess_start_time_seconds ")
	value, _, _ := strings.Cut(rest, "\n")
	if start, err := strconv.ParseFloat(value, 64); err != nil || start < 1.7e9 || start > 4.1e9 {
		t.Errorf("process_start_time_seconds = %q, want seconds since the epoch", value)
	}
}

// X9-OPS-005 (S14.1-AC2): every log line the level lets through is counted in
// logback_events_total{level}, the five levels the Java build counted present from the start.
func TestLogEventsAreCountedByLevel(t *testing.T) {
	var reg Registry
	var out bytes.Buffer
	logger := slog.New(reg.CountLogs(slog.NewJSONHandler(&out, &slog.HandlerOptions{Level: slog.LevelInfo})))
	ctx := context.Background()
	logger.DebugContext(ctx, "not enabled")
	logger.InfoContext(ctx, "one")
	logger.With("a", 1).WarnContext(ctx, "two")
	logger.WithGroup("g").ErrorContext(ctx, "three")
	logger.ErrorContext(ctx, "four")
	want := `# HELP logback_events_total Number of log events that were enabled by the effective log level.
# TYPE logback_events_total counter
logback_events_total{level="debug"} 0
logback_events_total{level="error"} 2
logback_events_total{level="info"} 1
logback_events_total{level="trace"} 0
logback_events_total{level="warn"} 1
`
	if got := scrape(t, &reg); got != want {
		t.Fatalf("got:\n%s\nwant:\n%s", got, want)
	}
	if n := strings.Count(out.String(), "\n"); n != 4 {
		t.Fatalf("%d lines written, want 4", n)
	}
}
