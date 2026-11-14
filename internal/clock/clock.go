// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package clock is the one source of business time (ADR-0033). Every decision that depends on
// "now" asks it: business rules, JWS iat and ttl, reservation stamps, timestamps written to the
// database, retention cut-offs. Nothing else in this module may call time.Now, time.Since,
// time.Until, time.After, time.Tick or time.NewTimer; the lint configuration enforces it, except
// at commented exemptions that measure a real duration (a request's latency, a timeout).
//
// A test deployment may shift the clock by an offset, so it keeps ticking and is never frozen.
// The offset has millisecond precision. It can be moved only on a clock built with
// AllowOffset, and this build never builds one: the /test/clock listener and its certificate
// fingerprint guard need the signing keys, and arrive with them.
package clock

import (
	"errors"
	"sync/atomic"
	"time"
)

// ErrOffsetDisabled is returned when the offset of a production clock is moved.
var ErrOffsetDisabled = errors.New("the clock offset is disabled in this deployment")

// Clock is the business clock. The zero value is not usable; call New.
type Clock struct {
	allowOffset bool
	offsetMs    atomic.Int64
}

// Option configures a Clock.
type Option func(*Clock)

// AllowOffset lets SetOffset move the clock. Only a test deployment may pass it (ADR-0033).
func AllowOffset() Option {
	return func(c *Clock) { c.allowOffset = true }
}

// New returns the real clock, with no offset.
func New(opts ...Option) *Clock {
	c := &Clock{}
	for _, o := range opts {
		o(c)
	}
	return c
}

// Now is the business "now", in UTC: the real time plus the offset. Take it once per decision
// (once per transaction) and pass it on.
func (c *Clock) Now() time.Time {
	return time.Now().Add(c.Offset()).UTC()
}

// Since is Now minus t.
func (c *Clock) Since(t time.Time) time.Duration { return c.Now().Sub(t) }

// Until is t minus Now.
func (c *Clock) Until(t time.Time) time.Duration { return t.Sub(c.Now()) }

// Offset is how far the business clock is from real time.
func (c *Clock) Offset() time.Duration {
	return time.Duration(c.offsetMs.Load()) * time.Millisecond
}

// OffsetAllowed reports whether the offset can be moved.
func (c *Clock) OffsetAllowed() bool { return c.allowOffset }

// SetOffset moves the clock by d from real time, truncated to the millisecond. A zero d resets it.
func (c *Clock) SetOffset(d time.Duration) error {
	if !c.allowOffset {
		return ErrOffsetDisabled
	}
	c.offsetMs.Store(d.Milliseconds())
	return nil
}

// SetNow moves the clock so that Now reads at, to the millisecond, from this moment on.
func (c *Clock) SetNow(at time.Time) error {
	return c.SetOffset(time.Until(at))
}
