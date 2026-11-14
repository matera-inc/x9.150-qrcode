// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package httplog

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

const (
	trace128 = "4bf92f3577b34da6a3ce929d0e0e4736"
	span     = "00f067aa0ba902b7"
	trace64  = "a3ce929d0e0e4736"
)

func TestTraceFrom(t *testing.T) {
	for _, tc := range []struct {
		name    string
		headers map[string]string
		want    Trace
		ok      bool
	}{
		{"b3 single", map[string]string{"b3": trace128 + "-" + span + "-1"}, Trace{trace128, span}, true},
		{"b3 single 64-bit", map[string]string{"b3": trace64 + "-" + span}, Trace{trace64, span}, true},
		{"b3 multi", map[string]string{"X-B3-TraceId": trace128, "X-B3-SpanId": span}, Trace{trace128, span}, true},
		{"traceparent", map[string]string{"traceparent": "00-" + trace128 + "-" + span + "-01"}, Trace{trace128, span}, true},
		{"b3 wins over traceparent", map[string]string{"b3": trace64 + "-" + span, "traceparent": "00-" + trace128 + "-" + span + "-01"}, Trace{trace64, span}, true},
		{"upper case is normalised", map[string]string{"traceparent": "00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01"}, Trace{trace128, span}, true},
		{"b3 sampling only", map[string]string{"b3": "1"}, Trace{}, false},
		{"malformed traceparent", map[string]string{"traceparent": "00-xyz-" + span + "-01"}, Trace{}, false},
		{"all-zero trace id", map[string]string{"traceparent": "00-00000000000000000000000000000000-" + span + "-01"}, Trace{}, false},
		{"forbidden version", map[string]string{"traceparent": "ff-" + trace128 + "-" + span + "-01"}, Trace{}, false},
		{"injection attempt", map[string]string{"b3": trace64 + "\n{\"x\":1}-" + span}, Trace{}, false},
		{"none", nil, Trace{}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := http.Header{}
			for k, v := range tc.headers {
				h.Set(k, v)
			}
			got, ok := TraceFrom(h)
			if ok != tc.ok {
				t.Fatalf("ok = %v, want %v", ok, tc.ok)
			}
			if diff := cmp.Diff(tc.want, got); diff != "" {
				t.Fatalf("trace (-want +got):\n%s", diff)
			}
		})
	}
}

// S7.1-AC3: logs are slog JSON, and incoming b3 / traceparent ids appear in request logs.
func TestMiddlewareLogsTraceIDs(t *testing.T) {
	for _, tc := range []struct {
		name, header, value, wantTrace string
	}{
		{"b3", "b3", trace64 + "-" + span + "-1", trace64},
		{"traceparent", "traceparent", "00-" + trace128 + "-" + span + "-01", trace128},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var buf bytes.Buffer
			h := Middleware(NewLogger(&buf, slog.LevelInfo), http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(http.StatusTeapot)
			}))
			req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/api/v1/x", nil)
			req.Header.Set(tc.header, tc.value)
			h.ServeHTTP(httptest.NewRecorder(), req)

			var line map[string]any
			if err := json.Unmarshal(buf.Bytes(), &line); err != nil {
				t.Fatalf("the log line is not JSON: %q", buf.String())
			}
			for k, want := range map[string]any{
				"msg": "request", "level": "INFO", "method": "GET", "path": "/api/v1/x",
				"status": float64(http.StatusTeapot), "traceId": tc.wantTrace, "spanId": span,
			} {
				if line[k] != want {
					t.Errorf("%s = %v, want %v (line %s)", k, line[k], want, buf.String())
				}
			}
			if _, ok := line["durationMs"]; !ok {
				t.Errorf("no durationMs: %s", buf.String())
			}
		})
	}
}

func TestMiddlewareWithoutTraceAndQuietPaths(t *testing.T) {
	var buf bytes.Buffer
	h := Middleware(NewLogger(&buf, slog.LevelInfo), http.NotFoundHandler())
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/actuator/health/readiness", nil))
	if buf.Len() != 0 {
		t.Fatalf("a probe was logged: %s", buf.String())
	}
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/x", nil))
	var line map[string]any
	if err := json.Unmarshal(buf.Bytes(), &line); err != nil {
		t.Fatal(err)
	}
	if _, ok := line["traceId"]; ok || line["status"] != float64(http.StatusNotFound) {
		t.Fatalf("unexpected line: %s", buf.String())
	}
}

// FuzzTraceFrom: the trace headers are external input; whatever they hold, a returned id is plain
// lowercase hex, so it can never break the JSON log line it is written to.
func FuzzTraceFrom(f *testing.F) {
	f.Add(trace128+"-"+span+"-1", "00-"+trace128+"-"+span+"-01", trace128, span)
	f.Add("1", "garbage", "", "")
	f.Fuzz(func(t *testing.T, b3, traceparent, xTrace, xSpan string) {
		h := http.Header{}
		h.Set("b3", b3)
		h.Set("traceparent", traceparent)
		h.Set("X-B3-TraceId", xTrace)
		h.Set("X-B3-SpanId", xSpan)
		got, ok := TraceFrom(h)
		if !ok {
			return
		}
		for _, id := range []string{got.TraceID, got.SpanID} {
			for _, r := range id {
				if (r < '0' || r > '9') && (r < 'a' || r > 'f') {
					t.Fatalf("id %q is not lowercase hex", id)
				}
			}
		}
	})
}
