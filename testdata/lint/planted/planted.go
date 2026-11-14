// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package planted holds deliberate violations of the lint rules this repository relies on. It is
// never built (testdata is outside ./...). others/scripts/check-go-lint-planted.sh lints it and
// fails unless exactly the lines marked "want <linter>…" are reported, by exactly those linters, so
// a configuration typo or a tool upgrade cannot silently switch a rule off. Lines marked "allowed"
// must not be reported.
package planted

import (
	"context"
	"database/sql" // want depguard
	"fmt"
	"log" // want depguard
	"log/slog"
	"math/rand" // want depguard
	"net/http"
	"os"
	"strconv"
	"time"
)

var _ *sql.DB

// Clock reads the wall clock every way ADR-0033 forbids outside internal/clock.
func Clock() {
	_ = time.Now()                     // want forbidigo
	_ = time.Since(time.Time{})        // want forbidigo
	_ = time.Until(time.Time{})        // want forbidigo
	<-time.After(time.Second)          // want forbidigo
	_ = time.Tick(time.Second)         // want forbidigo
	_ = time.NewTimer(time.Second)     // want forbidigo
	_ = time.Time{}.After(time.Time{}) // allowed: a method of time.Time, not the wall clock
	_ = time.Now()                     //nolint:forbidigo // allowed: an exemption that says what it measures
}

func init() { // want gochecknoinits
	log.Println(rand.Int()) // want gosec
}

// Others breaks one rule each.
func Others(ctx context.Context, logger *slog.Logger) {
	_ = os.Getenv("DATABASE_HOST")                    // want forbidigo
	fmt.Println("hello")                              // want forbidigo
	resp, _ := http.Get("https://example.com")        // want forbidigo bodyclose noctx
	_ = http.ListenAndServe(":8080", nil)             // want forbidigo gosec
	_ = resp.Header                                   // allowed
	os.Remove("/tmp/x")                               // want errcheck gosec
	logger.InfoContext(ctx, "n="+strconv.Itoa(1))     // want sloglint
	logger.InfoContext(ctx, "count", "snake_case", 1) // want sloglint
}
