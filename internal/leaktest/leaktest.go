// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package leaktest fails a test binary that leaves a goroutine stuck. Every package with tests
// calls it from TestMain:
//
//	func TestMain(m *testing.M) { leaktest.Main(m) }
//
// It reads the runtime's goroutineleak profile, which reports only goroutines blocked on something
// no other goroutine can reach, so connection pools and keep-alives do not show up as leaks.
package leaktest

import (
	"bytes"
	"fmt"
	"os"
	"runtime/pprof"
	"testing"
)

// Main runs the tests, then exits non-zero if they failed or if a goroutine leaked.
func Main(m *testing.M) {
	os.Exit(check(m.Run()))
}

func check(code int) int {
	profile := pprof.Lookup("goroutineleak")
	if profile == nil {
		fmt.Fprintln(os.Stderr, "leaktest: this Go runtime has no goroutineleak profile, so leaks cannot be checked")
		return 1
	}
	// Writing the profile runs the leak-detecting collection; Count is meaningful only after it.
	var out bytes.Buffer
	if err := profile.WriteTo(&out, 1); err != nil {
		fmt.Fprintln(os.Stderr, "leaktest: goroutineleak profile:", err)
		return 1
	}
	if profile.Count() > 0 {
		fmt.Fprintln(os.Stderr, "leaktest: leaked goroutines:\n"+out.String())
		return 1
	}
	return code
}
