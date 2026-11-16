// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package metrics holds the service's Prometheus metrics and serves them, in the Prometheus text
// format (version 0.0.4), at GET /actuator/prometheus, the path the Java build's actuator uses
// (ADR-0031). docs/METRICS.md is the inventory: every family the Java build exported, kept under
// its name, replaced or dropped, and the Go build's own.
//
// It is the standard library only, decided in Story 14.1 (S14.1-AC2): the service exports a few
// dozen series, a counter is an atomic integer, a gauge is read when the page is scraped, and the
// exposition is written by hand. prometheus/client_golang would bring a dependency tree and a
// second registry model for no metric this service needs; it is the right choice the day the
// service needs histograms with exemplars, the OpenMetrics or protobuf formats, or push. What it
// would have given for free and this package writes itself: the request timer (requests.go), the
// process and Go runtime gauges (process.go), and the log-event counter (logs.go).
package metrics

import (
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
)

// Path is where the metrics are served.
const Path = "/actuator/prometheus"

// contentType is the Prometheus text format's media type.
const contentType = "text/plain; version=0.0.4; charset=utf-8"

// The metric types of the text format.
const (
	TypeCounter = "counter"
	TypeGauge   = "gauge"
	TypeSummary = "summary"
)

// Counter is a count that only goes up, safe for concurrent use.
type Counter struct{ v atomic.Uint64 }

// Add adds n.
func (c *Counter) Add(n uint64) { c.v.Add(n) }

// Inc adds one.
func (c *Counter) Inc() { c.v.Add(1) }

// Value is the current count.
func (c *Counter) Value() uint64 { return c.v.Load() }

// Label is one label of a series.
type Label struct{ Name, Value string }

type series struct {
	labels  string // rendered: {a="b",c="d"}, or "" for none
	counter *Counter
}

type family struct {
	name, help string
	series     []series
}

// Collector writes metrics whose values are read when the page is scraped (a gauge, a count kept
// by somebody else).
type Collector func(e *Exposition)

// Registry is a set of counters and collectors. The zero value is ready to use.
type Registry struct {
	mu         sync.Mutex
	families   []*family
	collectors []Collector
}

// Counter returns the counter of the series name{labels}, registering it the first time: asking
// twice for the same series returns the same counter. name must end in _total, as a Prometheus
// counter's name does; help is the family's HELP text, taken from the first registration.
func (r *Registry) Counter(name, help string, labels ...Label) *Counter {
	rendered := render(labels)
	r.mu.Lock()
	defer r.mu.Unlock()
	i := slices.IndexFunc(r.families, func(f *family) bool { return f.name == name })
	if i < 0 {
		r.families = append(r.families, &family{name: name, help: help})
		i = len(r.families) - 1
	}
	f := r.families[i]
	for _, s := range f.series {
		if s.labels == rendered {
			return s.counter
		}
	}
	c := &Counter{}
	f.series = append(f.series, series{labels: rendered, counter: c})
	return c
}

// Collect adds a collector, called on every scrape after the counters, in the order added.
func (r *Registry) Collect(c Collector) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.collectors = append(r.collectors, c)
}

// Write renders every counter, then every collector, in the Prometheus text format, in the order
// they were registered.
func (r *Registry) Write(b *strings.Builder) {
	r.mu.Lock()
	collectors := slices.Clone(r.collectors)
	for _, f := range r.families {
		writeHeader(b, f.name, f.help, TypeCounter)
		for _, s := range f.series {
			b.WriteString(f.name + s.labels + " " + strconv.FormatUint(s.counter.Value(), 10) + "\n")
		}
	}
	r.mu.Unlock()
	e := &Exposition{b: b}
	for _, c := range collectors {
		c(e)
	}
}

// Register serves the registry at GET Path on mux.
func (r *Registry) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET "+Path, func(w http.ResponseWriter, _ *http.Request) {
		var b strings.Builder
		r.Write(&b)
		w.Header().Set("Content-Type", contentType)
		_, _ = w.Write([]byte(b.String()))
	})
}

// Exposition is what a Collector writes to.
type Exposition struct{ b *strings.Builder }

// Sample is one line of a family: the family's name plus Suffix (_count, _sum, or ""), its labels
// and its value.
type Sample struct {
	Suffix string
	Labels []Label
	Value  float64
}

// Family writes one family: its HELP and TYPE lines, then its samples.
func (e *Exposition) Family(name, help, typ string, samples ...Sample) {
	writeHeader(e.b, name, help, typ)
	for _, s := range samples {
		e.b.WriteString(name + s.Suffix + render(s.Labels) + " " + formatFloat(s.Value) + "\n")
	}
}

// Gauge writes a family of one gauge.
func (e *Exposition) Gauge(name, help string, v float64, labels ...Label) {
	e.Family(name, help, TypeGauge, Sample{Labels: labels, Value: v})
}

func writeHeader(b *strings.Builder, name, help, typ string) {
	b.WriteString("# HELP " + name + " " + escapeHelp(help) + "\n")
	b.WriteString("# TYPE " + name + " " + typ + "\n")
}

func formatFloat(v float64) string { return strconv.FormatFloat(v, 'g', -1, 64) }

func render(labels []Label) string {
	if len(labels) == 0 {
		return ""
	}
	parts := make([]string, 0, len(labels))
	for _, l := range labels {
		parts = append(parts, l.Name+`="`+escapeValue(l.Value)+`"`)
	}
	return "{" + strings.Join(parts, ",") + "}"
}

var (
	valueEscaper = strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`)
	helpEscaper  = strings.NewReplacer(`\`, `\\`, "\n", `\n`)
)

func escapeValue(v string) string { return valueEscaper.Replace(v) }
func escapeHelp(v string) string  { return helpEscaper.Replace(v) }
