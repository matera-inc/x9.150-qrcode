// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package main

import (
	"context"
	"net/http"
	"os"
	"strings"
	"testing"

	"github.com/matera-inc/x9.150-qrcode/internal/dbtest"
)

// X9-OPS-001 (S14.1-AC1, S14.1-AC2, S14.1-AC3): the running service serves every family
// docs/METRICS.md marks kept, with its type and label names, its request timer labelled by route
// template (never a raw id), the Go build's own families, and /actuator/info with the stamped
// version and commit.
//
// Source: ours (ADR-0031, Story 14.1). Why: an operator's dashboards and alerts on the Java build
// read these names; a kept family that silently disappeared would leave an alert that never fires.
func testExportsEveryKeptMetric(t *testing.T, db *dbtest.DB) {
	ctx, cancel := context.WithCancel(t.Context())
	var out syncBuffer
	done := make(chan error, 1)
	port := freePort(ctx, t)
	go func() { done <- run(ctx, nil, dbEnv(db, port), &out) }()
	waitForLog(t, &out, "x9-qrcode ready")
	if code, _ := probe(ctx, t, port, "/api/v1/payment-request/01A1E638C4AB1EB442D140F15C9758A2"); code != http.StatusNotFound {
		t.Fatalf("GET of an unknown QR Code: %d", code)
	}
	code, info := probe(ctx, t, port, "/actuator/info")
	if want := `{"build":{"name":"x9-qrcode","version":"` + version + `"},"git":{"commit":{"id":"` + commit + `"}}}`; code != http.StatusOK || info != want {
		t.Fatalf("/actuator/info: %d %s, want %s", code, info, want)
	}
	code, page := probe(ctx, t, port, "/actuator/prometheus")
	if code != http.StatusOK {
		t.Fatalf("/actuator/prometheus: %d\n%s", code, page)
	}
	for _, k := range keptMetrics(t) {
		if !strings.Contains(page, "\n# TYPE "+k.name+" "+k.typ+"\n") {
			t.Errorf("kept family %s (%s) is not served", k.name, k.typ)
			continue
		}
		sample := firstSample(page, k.name)
		for _, label := range k.labels {
			if !strings.Contains(sample, label+`="`) {
				t.Errorf("kept family %s: no label %s in %q", k.name, label, sample)
			}
		}
	}
	for _, want := range []string{
		`http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="CLIENT_ERROR",status="404",uri="/api/v1/payment-request/{id}"} 1`,
		`http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/actuator/info"} 1`,
		"\n# TYPE x9_retention_runs_total counter\n",
		"\n# TYPE x9_database_pool_connections gauge\n",
		`x9_database_pool_max_connections{pool="write"} 8`,
		"\n# TYPE go_goroutines gauge\n",
	} {
		if !strings.Contains(page, want) {
			t.Errorf("missing %q", want)
		}
	}
	if strings.Contains(page, "01A1E638") {
		t.Errorf("a raw id reached a label")
	}
	if t.Failed() {
		t.Logf("/actuator/prometheus:\n%s", page)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("run returned %v", err)
	}
}

type keptMetric struct {
	name, typ string
	labels    []string
}

// keptMetrics reads the "Kept" table of docs/METRICS.md, between its kept:start and kept:end
// markers: the families the Go build promises to serve under the Java build's name, type and labels.
func keptMetrics(t *testing.T) []keptMetric {
	t.Helper()
	doc, err := os.ReadFile("../../docs/METRICS.md")
	if err != nil {
		t.Fatal(err)
	}
	_, table, ok := strings.Cut(string(doc), "<!-- kept:start -->")
	table, _, ok2 := strings.Cut(table, "<!-- kept:end -->")
	if !ok || !ok2 {
		t.Fatal("docs/METRICS.md has no kept:start / kept:end markers")
	}
	var kept []keptMetric
	for line := range strings.SplitSeq(table, "\n") {
		cells := strings.Split(line, "|")
		if len(cells) < 5 || !strings.HasPrefix(strings.TrimSpace(cells[1]), "`") {
			continue
		}
		k := keptMetric{name: strings.Trim(strings.TrimSpace(cells[1]), "`"), typ: strings.TrimSpace(cells[2])}
		for l := range strings.SplitSeq(cells[3], ",") {
			if l = strings.Trim(strings.TrimSpace(l), "`"); l != "" && l != "—" {
				k.labels = append(k.labels, l)
			}
		}
		kept = append(kept, k)
	}
	if len(kept) == 0 {
		t.Fatal("docs/METRICS.md's kept table is empty")
	}
	return kept
}

// firstSample is the first sample line of family name on page (name, or name_count for a summary).
func firstSample(page, name string) string {
	for line := range strings.SplitSeq(page, "\n") {
		if strings.HasPrefix(line, name+"{") || strings.HasPrefix(line, name+" ") || strings.HasPrefix(line, name+"_count{") {
			return line
		}
	}
	return ""
}

// The kept table is read as the test expects: every row a family, a type and its labels.
func TestKeptMetricsTableParses(t *testing.T) {
	kept := keptMetrics(t)
	if len(kept) != 9 {
		t.Fatalf("%d kept families, want the 9 docs/METRICS.md counts", len(kept))
	}
	if k := kept[0]; k.name != "http_server_requests_seconds" || k.typ != "summary" || len(k.labels) != 6 {
		t.Fatalf("first row = %+v", k)
	}
}
