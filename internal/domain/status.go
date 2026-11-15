// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import (
	"errors"
	"fmt"
)

// Status is a QR Code's status, by its wire name (QRCodeStatus in openapi.yaml). It is parsed at
// the boundary, with ParseStatus from the wire and StatusFromCode from the database.
type Status string

// The four statuses. PAID and CANCELLED are terminal.
const (
	StatusActive           Status = "ACTIVE"
	StatusPaymentInitiated Status = "PAYMENT_INITIATED"
	StatusPaid             Status = "PAID"
	StatusCancelled        Status = "CANCELLED"
)

// ErrUnknownStatus is returned for a name or code that is not one of the four statuses.
var ErrUnknownStatus = errors.New("unknown QR Code status")

// ParseStatus reads a status by its wire name, exactly as written.
func ParseStatus(name string) (Status, error) {
	switch s := Status(name); s {
	case StatusActive, StatusPaymentInitiated, StatusPaid, StatusCancelled:
		return s, nil
	default:
		return "", fmt.Errorf("%w: %q", ErrUnknownStatus, name)
	}
}

// StatusFromCode reads a status by its code in qr_code_status (ACTV, INIT, PAID, CANC).
func StatusFromCode(code string) (Status, error) {
	switch code {
	case "ACTV":
		return StatusActive, nil
	case "INIT":
		return StatusPaymentInitiated, nil
	case "PAID":
		return StatusPaid, nil
	case "CANC":
		return StatusCancelled, nil
	default:
		return "", fmt.Errorf("%w: code %q", ErrUnknownStatus, code)
	}
}

// Code is the status's code in qr_code_status, or "" for a value that is not a status.
func (s Status) Code() string {
	switch s {
	case StatusActive:
		return "ACTV"
	case StatusPaymentInitiated:
		return "INIT"
	case StatusPaid:
		return "PAID"
	case StatusCancelled:
		return "CANC"
	default:
		return ""
	}
}

// Closed reports whether the status is terminal: PAID or CANCELLED.
func (s Status) Closed() bool {
	switch s {
	case StatusPaid, StatusCancelled:
		return true
	case StatusActive, StatusPaymentInitiated:
		return false
	default:
		return false
	}
}
