// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package config

import (
	"strings"
	"testing"
	"time"
)

const day = 24 * time.Hour

// S12.1-AC1: events.retention P30D, qrcode.retention P400D and an hourly run, unless configured.
func TestRetentionDefaults(t *testing.T) {
	cfg, err := Load("", complete())
	if err != nil {
		t.Fatal(err)
	}
	if got := time.Duration(cfg.Events.Retention); got != 30*day {
		t.Errorf("events.retention = %v, want 30 days", got)
	}
	if got := time.Duration(cfg.QRCode.Retention); got != 400*day {
		t.Errorf("qrcode.retention = %v, want 400 days", got)
	}
	if got := time.Duration(cfg.Retention.Interval); got != time.Hour {
		t.Errorf("retention.interval = %v, want 1h", got)
	}
}

// S12.1-AC1: each key is read from its variable, and from the file.
func TestRetentionFromEnvironmentAndFile(t *testing.T) {
	cfg, err := Load("", append(complete(), "X9_EVENTS_RETENTION=P7D", "X9_QRCODE_RETENTION=p90d", "X9_RETENTION_INTERVAL=PT5S"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Events.Retention != Duration(7*day) || cfg.QRCode.Retention != Duration(90*day) || cfg.Retention.Interval != Duration(5*time.Second) {
		t.Fatalf("from the environment: %v %v %v", cfg.Events.Retention, cfg.QRCode.Retention, cfg.Retention.Interval)
	}

	path := writeFile(t, "x9.yaml", "events:\n  retention: P10D\nqrcode:\n  retention: P20D\nretention:\n  interval: PT2M\n")
	cfg, err = Load(path, complete())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Events.Retention != Duration(10*day) || cfg.QRCode.Retention != Duration(20*day) || cfg.Retention.Interval != Duration(2*time.Minute) {
		t.Fatalf("from the file: %v %v %v", cfg.Events.Retention, cfg.QRCode.Retention, cfg.Retention.Interval)
	}

	if _, err := Load(writeFile(t, "bad.yaml", "events:\n  retention: 30 days\n"), complete()); err == nil ||
		!strings.Contains(err.Error(), "not an ISO-8601 duration") {
		t.Fatalf("a malformed duration in the file: err = %v", err)
	}
}

// S12.1-AC1: a QR Code retention shorter than the events' is refused at start, naming both keys
// and both variables; an equal one starts.
func TestQRCodeRetentionShorterThanEventsIsRefused(t *testing.T) {
	_, err := Load("", append(complete(), "X9_EVENTS_RETENTION=P30D", "X9_QRCODE_RETENTION=P29D"))
	want := "qrcode.retention (X9_QRCODE_RETENTION): P29D is shorter than events.retention (X9_EVENTS_RETENTION) P30D"
	if err == nil || !strings.Contains(err.Error(), want) {
		t.Fatalf("err = %v, want it to contain %q", err, want)
	}
	if _, err := Load("", append(complete(), "X9_EVENTS_RETENTION=P30D", "X9_QRCODE_RETENTION=P30D")); err != nil {
		t.Fatalf("equal retentions are refused: %v", err)
	}
}

func TestRetentionInvalidValues(t *testing.T) {
	for _, tc := range []struct{ env, want string }{
		{"X9_EVENTS_RETENTION=30", "events.retention (X9_EVENTS_RETENTION): \"30\" is not an ISO-8601 duration"},
		{"X9_EVENTS_RETENTION=P1M", "is not an ISO-8601 duration"}, // a month has no fixed length
		{"X9_EVENTS_RETENTION=P1Y", "is not an ISO-8601 duration"},
		{"X9_EVENTS_RETENTION=P", "is not an ISO-8601 duration"},
		{"X9_RETENTION_INTERVAL=PT", "is not an ISO-8601 duration"},
		{"X9_RETENTION_INTERVAL=PT0S", "retention.interval (X9_RETENTION_INTERVAL): must be at least one second, got PT0S"},
		{"X9_EVENTS_RETENTION=P0D", "events.retention (X9_EVENTS_RETENTION): must be at least one second"},
		{"X9_QRCODE_RETENTION=P99999999D", "qrcode.retention (X9_QRCODE_RETENTION): \"P99999999D\" is longer than 36525 days"},
	} {
		t.Run(tc.env, func(t *testing.T) {
			_, err := Load("", append(complete(), tc.env))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to contain %q", err, tc.want)
			}
		})
	}
}

func TestDurationRoundTrip(t *testing.T) {
	for _, tc := range []struct {
		in   string
		want time.Duration
		out  string
	}{
		{"P30D", 30 * day, "P30D"},
		{"P400D", 400 * day, "P400D"},
		{"PT1H", time.Hour, "PT1H"},
		{"PT90S", 90 * time.Second, "PT1M30S"},
		{"P1DT12H", 36 * time.Hour, "P1DT12H"},
		{"pt5s", 5 * time.Second, "PT5S"},
		{"PT0S", 0, "PT0S"},
	} {
		d, err := ParseDuration(tc.in)
		if err != nil {
			t.Fatalf("%s: %v", tc.in, err)
		}
		if time.Duration(d) != tc.want || d.String() != tc.out {
			t.Errorf("%s = %v (%s), want %v (%s)", tc.in, time.Duration(d), d, tc.want, tc.out)
		}
	}
}
