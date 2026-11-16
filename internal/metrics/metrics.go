// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package metrics holds the service's Prometheus counters and serves them, in the Prometheus text
// format (version 0.0.4), at GET /actuator/prometheus, the path the Java build's actuator uses
// (ADR-0031). It is the standard library only: a counter is an atomic integer, and the exposition
// is written by hand. It carries what the Go build counts today (the retention job's, ADR-0029);
// the inventory of the Java build's metrics, each kept under its name or listed as dropped, is the
// image-and-deploy phase's work.
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

// Registry is a set of counters. The zero value is ready to use.
type Registry struct {
	mu       sync.Mutex
	families []*family
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

// Write renders every counter in the Prometheus text format, families and series in the order
// they were registered.
func (r *Registry) Write(b *strings.Builder) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, f := range r.families {
		b.WriteString("# HELP " + f.name + " " + escapeHelp(f.help) + "\n")
		b.WriteString("# TYPE " + f.name + " counter\n")
		for _, s := range f.series {
			b.WriteString(f.name + s.labels + " " + strconv.FormatUint(s.counter.Value(), 10) + "\n")
		}
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
