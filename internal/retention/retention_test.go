// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package retention

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
	"github.com/matera-inc/x9.150-qrcode/internal/metrics"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

const day = 24 * time.Hour

// fixedClock is a business clock that counts how often it is read.
type fixedClock struct {
	now   time.Time
	reads atomic.Int64
}

func (c *fixedClock) Now() time.Time {
	c.reads.Add(1)
	return c.now
}

// downDB is a database that cannot begin a transaction.
type downDB struct{ begins atomic.Int64 }

var errDown = errors.New("the database is down")

func (d *downDB) BeginTx(context.Context, pgx.TxOptions) (pgx.Tx, error) {
	d.begins.Add(1)
	return nil, errDown
}

// syncBuffer is a log sink safe for the job's goroutine and the test's.
type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) lines(t *testing.T) []map[string]any {
	t.Helper()
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []map[string]any
	for line := range strings.SplitSeq(strings.TrimSpace(s.b.String()), "\n") {
		if line == "" {
			continue
		}
		var m map[string]any
		if err := json.Unmarshal([]byte(line), &m); err != nil {
			t.Fatalf("log line %q: %v", line, err)
		}
		out = append(out, m)
	}
	return out
}

func newJob(db DB, c Clock, cfg Config) (*Job, *metrics.Registry, *syncBuffer) {
	var reg metrics.Registry
	logs := &syncBuffer{}
	return New(db, c, cfg, &reg, slog.New(slog.NewJSONHandler(logs, nil))), &reg, logs
}

// S12.1-AC2: the cutoffs are the business clock's, read once per run.
func TestCutoffsComeFromTheBusinessClockOnce(t *testing.T) {
	c := &fixedClock{now: time.Date(2027, 3, 1, 10, 0, 0, 0, time.FixedZone("x", 3600))}
	job, _, _ := newJob(&downDB{}, c, Config{Events: 30 * day, QRCodes: 400 * day, Interval: time.Hour})
	r, err := job.RunOnce(t.Context())
	if !errors.Is(err, errDown) {
		t.Fatalf("err = %v", err)
	}
	if got := c.reads.Load(); got != 1 {
		t.Fatalf("the clock was read %d times in one run, want once", got)
	}
	now := c.now.UTC()
	if !r.EventsCutoff.Equal(now.Add(-30*day)) || !r.QRCodesCutoff.Equal(now.Add(-400*day)) || r.EventsCutoff.Location() != time.UTC {
		t.Fatalf("cutoffs %v, %v from %v", r.EventsCutoff, r.QRCodesCutoff, now)
	}
}

// S12.1-AC4, S12.2-AC1: a failed run is logged at ERROR with its cutoffs and counted; it never
// panics nor stops the job, which tries again at the next tick.
func TestAFailedRunIsLoggedCountedAndRetried(t *testing.T) {
	db := &downDB{}
	job, reg, logs := newJob(db, &fixedClock{now: time.Now().UTC()}, Config{Events: day, QRCodes: day, Interval: 10 * time.Millisecond}) //nolint:forbidigo // any instant will do
	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan struct{})
	go func() {
		defer close(done)
		job.Run(ctx)
	}()
	deadline := time.After(5 * time.Second) //nolint:forbidigo // a test's real timeout
	for db.begins.Load() < 3 {
		select {
		case <-deadline:
			t.Fatalf("only %d runs in 5 s", db.begins.Load())
		case <-time.After(5 * time.Millisecond): //nolint:forbidigo // polling in real time
		}
	}
	cancel()
	<-done

	if got := reg.Counter(MetricFailedRuns, "").Value(); got < 3 {
		t.Fatalf("%s = %d, want at least 3", MetricFailedRuns, got)
	}
	var failures int
	for _, l := range logs.lines(t) {
		if l["msg"] == "x9-qrcode retention run failed; retried at the next tick" {
			failures++
			if l["level"] != "ERROR" || l["eventsCutoff"] == nil || l["err"] == nil {
				t.Fatalf("failure line: %v", l)
			}
		}
	}
	if failures < 3 {
		t.Fatalf("%d failure lines", failures)
	}
}

// S12.1-AC4: the job stops when its context ends, even while it waits for its timer, and a run
// cut short by the shutdown is not a failure.
func TestStopsOnShutdown(t *testing.T) {
	job, reg, logs := newJob(&downDB{}, &fixedClock{}, Config{Events: day, QRCodes: day, Interval: time.Hour})
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := job.RunOnce(ctx); err == nil {
		t.Fatal("a cancelled run reported success")
	}
	if got := reg.Counter(MetricFailedRuns, "").Value(); got != 0 {
		t.Fatalf("a run cut short by the shutdown counted as a failure: %d", got)
	}

	ctx, cancel = context.WithCancel(t.Context())
	done := make(chan struct{})
	go func() {
		defer close(done)
		job.Run(ctx)
	}()
	time.Sleep(20 * time.Millisecond) // lets the first run end and the hour-long timer start
	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second): //nolint:forbidigo // a test's real timeout
		t.Fatal("the job did not stop")
	}
	var stopped bool
	for _, l := range logs.lines(t) {
		stopped = stopped || l["msg"] == "x9-qrcode retention stopped"
	}
	if !stopped {
		t.Fatal("no stop line")
	}
}
