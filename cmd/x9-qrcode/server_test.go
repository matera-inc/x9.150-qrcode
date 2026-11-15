// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package main

import (
	"net/http"
	"testing"
	"time"
)

// S9.2-AC8: ADR-0031's server timeouts. The 30 KB header limit is httpapi's (Tomcat's 400), so
// net/http's own limit, which answers 431, sits far above it.
func TestServerTimeouts(t *testing.T) {
	srv := newServer(8080, http.NotFoundHandler())
	got := []time.Duration{srv.ReadHeaderTimeout, srv.ReadTimeout, srv.WriteTimeout, srv.IdleTimeout}
	want := []time.Duration{10 * time.Second, 30 * time.Second, 40 * time.Second, 120 * time.Second}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("timeouts %v, want %v (read-header, read, write, idle)", got, want)
			break
		}
	}
	if srv.MaxHeaderBytes <= 30<<10 {
		t.Errorf("MaxHeaderBytes %d would answer 431 before httpapi answers 400", srv.MaxHeaderBytes)
	}
}
