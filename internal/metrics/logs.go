// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package metrics

import (
	"context"
	"log/slog"
)

// LogEventsName is the Java build's count of log events by level, kept under its name so that an
// alert on the rate of errors keeps working (S14.1-AC2), though the Go build has no logback.
const LogEventsName = "logback_events_total"

// The levels the Java build counted, in its order. Go has no trace level: its series stays 0.
var logLevels = []string{"debug", "error", "info", "trace", "warn"}

// CountLogs wraps h so that every log line it writes is counted in logback_events_total{level}:
// a line below info counts as debug, then info, warn and error. Only lines the level lets through
// are counted, as the Java build counted the events its effective level enabled.
func (r *Registry) CountLogs(h slog.Handler) slog.Handler {
	c := &logCounters{}
	for i, level := range logLevels {
		c.byLevel[i] = r.Counter(LogEventsName, "Number of log events that were enabled by the effective log level.",
			Label{"level", level})
	}
	return &countingHandler{Handler: h, counters: c}
}

type logCounters struct{ byLevel [5]*Counter }

func (c *logCounters) count(l slog.Level) {
	switch {
	case l < slog.LevelInfo:
		c.byLevel[0].Inc()
	case l < slog.LevelWarn:
		c.byLevel[2].Inc()
	case l < slog.LevelError:
		c.byLevel[4].Inc()
	default:
		c.byLevel[1].Inc()
	}
}

type countingHandler struct {
	slog.Handler
	counters *logCounters
}

func (h *countingHandler) Handle(ctx context.Context, rec slog.Record) error {
	h.counters.count(rec.Level)
	return h.Handler.Handle(ctx, rec)
}

func (h *countingHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &countingHandler{Handler: h.Handler.WithAttrs(attrs), counters: h.counters}
}

func (h *countingHandler) WithGroup(name string) slog.Handler {
	return &countingHandler{Handler: h.Handler.WithGroup(name), counters: h.counters}
}
