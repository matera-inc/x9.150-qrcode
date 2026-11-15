// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package store

import (
	"fmt"
	"sync"

	"github.com/google/uuid"
)

// The ids of new rows (ADR-0026), all bound as UUID. A create without locationId uses the QR Code's
// own id as its location (ADR-0024); NewLocationID is for the fresh location a rebind moves a
// holder to.

// NewQRCodeID mints a QR Code id: a UUIDv7, so the primary key grows in insert order.
func NewQRCodeID() (uuid.UUID, error) {
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.Nil, fmt.Errorf("minting a QR Code id: %w", err)
	}
	return id, nil
}

// NewLocationID mints a fresh location id: a random UUIDv4.
func NewLocationID() (uuid.UUID, error) {
	id, err := uuid.NewRandom()
	if err != nil {
		return uuid.Nil, fmt.Errorf("minting a location id: %w", err)
	}
	return id, nil
}

// eventIDs is the one generator of event ids: UUIDv7, each greater than the one before it in this
// process. github.com/google/uuid already orders its v7 ids within a process (millisecond and a
// 12-bit sub-millisecond counter, moved forward when the clock does not); this keeps that a
// checked property rather than a library detail.
var eventIDs struct {
	mu   sync.Mutex
	last uuid.UUID
}

// newEventID mints the next event id. The store calls it under its gate, after which the event
// batch takes the sequence row's lock, so event_id order is seq order (ADR-0037).
func newEventID() (uuid.UUID, error) {
	eventIDs.mu.Lock()
	defer eventIDs.mu.Unlock()
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.Nil, fmt.Errorf("minting an event id: %w", err)
	}
	if string(id[:]) <= string(eventIDs.last[:]) {
		return uuid.Nil, fmt.Errorf("minting an event id: %s does not follow %s", id, eventIDs.last)
	}
	eventIDs.last = id
	return id, nil
}
