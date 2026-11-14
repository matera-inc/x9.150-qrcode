// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package planted holds one deliberate violation per lint rule this repository relies on. It is
// never built (testdata is outside ./...). others/scripts/check-go-lint-planted.sh lints it and
// fails if any violation marked "want" stops being reported, or anything marked "allowed" is, so a
// configuration typo or a tool upgrade cannot silently switch a rule off.
package planted

import "time"

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
