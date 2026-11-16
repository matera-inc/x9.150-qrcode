// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package metrics

import (
	"cmp"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

// The request timer's families, under the names, type and labels the Java build's Spring
// (Micrometer) exported, so a dashboard or an alert on them keeps working (S14.1-AC2).
const (
	RequestsName    = "http_server_requests_seconds"
	RequestsMaxName = "http_server_requests_seconds_max"
	requestsHelp    = "Duration of HTTP server requests, by route template, method and status."
	requestsMaxHelp = "The longest HTTP server request of the last two minutes, by route template, method and status."
)

// The uri label of a request no route takes: "/**" for a 404, as the Java build's resource
// handler labelled it, and UNKNOWN for anything else (a 405, an OPTIONS), as Spring did.
const (
	uriNotFound = "/**"
	uriUnknown  = "UNKNOWN"
)

// Micrometer's time-window maximum: the largest value of the last two minutes, kept in three
// buffers that rotate every 40 seconds.
const (
	maxExpiry  = 2 * time.Minute
	maxBuffers = 3
	maxRotate  = maxExpiry / maxBuffers
)

type requestKey struct{ method, outcome, status, uri string }

type requestTimer struct {
	count uint64
	sum   float64
	max   windowMax
}

// requests times every request, labelled by its route's template, never by its raw path: an id
// in a label would make one series per QR Code.
type requests struct {
	mux  *http.ServeMux
	now  func() time.Time
	mu   sync.Mutex
	byID map[requestKey]*requestTimer
}

// Requests wraps next so that every request it answers is timed in http_server_requests_seconds
// (a summary: _count and _sum) and http_server_requests_seconds_max, labelled as the Java build
// labelled them: error and exception ("none": Go has no exception class to name), method,
// outcome, status, and uri, the template of the route of mux that takes the request
// ("/api/v1/payment-request/{id}").
func (r *Registry) Requests(mux *http.ServeMux, next http.Handler) http.Handler {
	t := &requests{
		mux:  mux,
		now:  time.Now, //nolint:forbidigo // request latency is real time, not business time
		byID: map[requestKey]*requestTimer{},
	}
	r.Collect(t.collect)
	return t.wrap(next)
}

func (t *requests) wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, pattern := t.mux.Handler(r)
		start := t.now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		t.observe(r.Method, pattern, rec.status, t.now().Sub(start))
	})
}

func (t *requests) observe(method, pattern string, status int, d time.Duration) {
	key := requestKey{method: method, outcome: outcome(status), status: strconv.Itoa(status), uri: uri(pattern, status)}
	seconds := d.Seconds()
	now := t.now()
	t.mu.Lock()
	defer t.mu.Unlock()
	timer := t.byID[key]
	if timer == nil {
		timer = &requestTimer{}
		t.byID[key] = timer
	}
	timer.count++
	timer.sum += seconds
	timer.max.record(now, seconds)
}

func (t *requests) collect(e *Exposition) {
	now := t.now()
	type row struct {
		key        requestKey
		count      uint64
		sum, worst float64
	}
	t.mu.Lock()
	rows := make([]row, 0, len(t.byID))
	for k, timer := range t.byID {
		rows = append(rows, row{key: k, count: timer.count, sum: timer.sum, worst: timer.max.value(now)})
	}
	t.mu.Unlock()
	slices.SortFunc(rows, func(a, b row) int {
		return cmp.Or(cmp.Compare(a.key.uri, b.key.uri), cmp.Compare(a.key.method, b.key.method),
			cmp.Compare(a.key.status, b.key.status))
	})
	summary := make([]Sample, 0, 2*len(rows))
	maxima := make([]Sample, 0, len(rows))
	for _, r := range rows {
		labels := r.key.labels()
		summary = append(summary,
			Sample{Suffix: "_count", Labels: labels, Value: float64(r.count)},
			Sample{Suffix: "_sum", Labels: labels, Value: r.sum})
		maxima = append(maxima, Sample{Labels: labels, Value: r.worst})
	}
	e.Family(RequestsName, requestsHelp, TypeSummary, summary...)
	e.Family(RequestsMaxName, requestsMaxHelp, TypeGauge, maxima...)
}

// labels are in the order the Java build wrote them (alphabetical).
func (k requestKey) labels() []Label {
	return []Label{
		{"error", "none"}, {"exception", "none"}, {"method", k.method},
		{"outcome", k.outcome}, {"status", k.status}, {"uri", k.uri},
	}
}

func uri(pattern string, status int) string {
	if pattern == "" {
		if status == http.StatusNotFound {
			return uriNotFound
		}
		return uriUnknown
	}
	// A ServeMux pattern is "[METHOD ][HOST]/PATH"; this service's patterns name no host.
	if _, path, ok := strings.Cut(pattern, " "); ok {
		return path
	}
	return pattern
}

// outcome is Spring's name for the status's class.
func outcome(status int) string {
	switch {
	case status >= 100 && status < 200:
		return "INFORMATIONAL"
	case status >= 200 && status < 300:
		return "SUCCESS"
	case status >= 300 && status < 400:
		return "REDIRECTION"
	case status >= 400 && status < 500:
		return "CLIENT_ERROR"
	case status >= 500 && status < 600:
		return "SERVER_ERROR"
	default:
		return "UNKNOWN"
	}
}

// windowMax is Micrometer's TimeWindowMax: every value is recorded into each of the buffers, the
// current one is read, and every maxRotate the current one is cleared and the next becomes
// current, so a value is forgotten between 80 and 120 seconds after it was recorded.
type windowMax struct {
	ring    [maxBuffers]float64
	current int
	rotated time.Time
}

func (m *windowMax) rotate(now time.Time) {
	if m.rotated.IsZero() {
		m.rotated = now
		return
	}
	steps := int(now.Sub(m.rotated) / maxRotate)
	if steps <= 0 {
		return
	}
	if steps >= maxBuffers {
		m.ring = [maxBuffers]float64{}
	} else {
		for range steps {
			m.ring[m.current] = 0
			m.current = (m.current + 1) % maxBuffers
		}
	}
	m.rotated = m.rotated.Add(time.Duration(steps) * maxRotate)
}

func (m *windowMax) record(now time.Time, v float64) {
	m.rotate(now)
	for i := range m.ring {
		m.ring[i] = max(m.ring[i], v)
	}
}

func (m *windowMax) value(now time.Time) float64 {
	m.rotate(now)
	return m.ring[m.current]
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
