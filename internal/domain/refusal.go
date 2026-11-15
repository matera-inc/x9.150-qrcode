// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import "errors"

// Kind is what sort of "no" a refusal is. The HTTP layer maps it to a status and a problem type.
type Kind int

// The kinds of refusal a transition can give.
const (
	// KindBusinessRule is a request the rules refuse (400, business-rule-violation).
	KindBusinessRule Kind = iota + 1
	// KindConflict is a well-formed request the QR Code's status refuses (409, status-conflict):
	// the caller should come back later, not look for a mistake it did not make (ADR-0021).
	KindConflict
	// KindPreconditionFailed is an If-Match that no longer matches the ETag (412).
	KindPreconditionFailed
)

// Sentinels a Refusal matches with errors.Is, one per Kind.
var (
	ErrBusinessRule       = errors.New("business rule violated")
	ErrConflict           = errors.New("status conflict")
	ErrPreconditionFailed = errors.New("precondition failed")
)

// Refusal is a transition's "no". Its texts are the Java build's, so the answer is the same.
type Refusal struct {
	Kind Kind
	// Field is the request path the refusal names, or "" when it names none.
	Field   string
	Message string
	// CurrentStatus is the effective status, carried on a conflict and a failed precondition.
	CurrentStatus Status
	// CurrentRevision is carried on a failed precondition.
	CurrentRevision int
}

// Error returns the field and the message.
func (r *Refusal) Error() string {
	if r.Field == "" {
		return r.Message
	}
	return r.Field + ": " + r.Message
}

// Is matches the sentinel of the refusal's Kind.
func (r *Refusal) Is(target error) bool {
	switch r.Kind {
	case KindBusinessRule:
		return target == ErrBusinessRule
	case KindConflict:
		return target == ErrConflict
	case KindPreconditionFailed:
		return target == ErrPreconditionFailed
	default:
		return false
	}
}

func businessRule(field, message string) *Refusal {
	return &Refusal{Kind: KindBusinessRule, Field: field, Message: message}
}

func conflict(current Status, message string) *Refusal {
	return &Refusal{Kind: KindConflict, Message: message, CurrentStatus: current}
}
