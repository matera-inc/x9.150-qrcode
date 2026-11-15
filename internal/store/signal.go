// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package store

import "sync"

// signal wakes every waiter at once: wait returns a channel that the next notify closes. A waiter
// holds no database connection while it waits (ADR-0025); it queries once woken.
type signal struct {
	mu sync.Mutex
	ch chan struct{}
}

func newSignal() *signal {
	return &signal{ch: make(chan struct{})}
}

func (s *signal) wait() <-chan struct{} {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ch
}

func (s *signal) notify() {
	s.mu.Lock()
	defer s.mu.Unlock()
	close(s.ch)
	s.ch = make(chan struct{})
}
