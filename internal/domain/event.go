// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import (
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/google/uuid"
)

// EventType is a payment event's type, by its wire name.
type EventType string

// The five event types.
const (
	EventInitiated EventType = "payment.initiated"
	EventSent      EventType = "payment.sent"
	EventFailed    EventType = "payment.failed"
	EventCleared   EventType = "payment.cleared"
	EventCancelled EventType = "payment.cancelled"
)

// Code is the type's code in event_type (INIT, SENT, FAIL, CLRD, CANC), or "" for a value that is
// not an event type.
func (t EventType) Code() string {
	switch t {
	case EventInitiated:
		return "INIT"
	case EventSent:
		return "SENT"
	case EventFailed:
		return "FAIL"
	case EventCleared:
		return "CLRD"
	case EventCancelled:
		return "CANC"
	default:
		return ""
	}
}

// ErrUnknownEventType is returned for a code that is not one of the five event types.
var ErrUnknownEventType = errors.New("unknown event type")

// EventTypeFromCode reads an event type by its code in event_type (INIT, SENT, FAIL, CLRD, CANC).
func EventTypeFromCode(code string) (EventType, error) {
	for _, t := range []EventType{EventInitiated, EventSent, EventFailed, EventCleared, EventCancelled} {
		if t.Code() == code {
			return t, nil
		}
	}
	return "", fmt.Errorf("%w: code %q", ErrUnknownEventType, code)
}

// SchemaVersion is the event body's schema: 1.1 added deviations and receivedInStatus (ADR-0036).
const SchemaVersion = "1.1"

// Event is a payment event as the outbox stores it, before the store gives it its sequence number
// and its event id (ADR-0026, ADR-0037). Only BuildEvent constructs one (ADR-0035); a test fails if
// another package does. A nil pointer is a field absent from the body.
type Event struct {
	Type           EventType
	OccurredAt     time.Time
	QRCodeID       uuid.UUID
	QRCodeRevision int
	LocationID     uuid.UUID

	Amount        *int64
	TipAmount     *int64
	PayerInfo     *string
	Currency      *string
	Network       *string
	TransactionID *string
	InvoiceNumber *string
	OrderNumber   *string
	Reason        *string

	// Deviations is nil when the payer followed the rules, and on events the status-update API
	// produced: absent, never an empty list.
	Deviations []Deviation
	// ReceivedInStatus is nil on events the status-update API produced.
	ReceivedInStatus *Status
	SchemaVersion    string
}

// Cause is why an event is built: the reason payment.failed carries ("" for none), and the
// receipt of the payer's notification behind it (nil for an event the status-update API
// produced).
type Cause struct {
	Reason  string
	Receipt *Receipt
}

// BuildEvent is the one door every payment event goes through (ADR-0035). Field for field as Java
// builds it:
//
//   - the body (amount, tip, payer, currency, network, transaction id) comes from last, the stored
//     notification EventBodySource picked (the latest carrying a transactionId, else the last
//     stored), when there is one; otherwise the amount and currency of the first payment method,
//     and the network and End-to-End ID of the payment details, with no tip and no payer;
//   - the invoice and order numbers come from the bill, the revision and location from the row;
//   - receivedInStatus and deviations come from the cause's receipt; deviations are absent rather
//     than empty;
//   - schemaVersion is 1.1.
//
// It fails only when the row's request_json cannot be read.
func BuildEvent(eventType EventType, row Row, last *NotificationRecord, now time.Time, cause Cause) (Event, error) {
	req, err := readRequest(row.RequestJSON)
	if err != nil {
		return Event{}, err
	}

	e := Event{
		Type:           eventType,
		OccurredAt:     now,
		QRCodeID:       row.ID,
		QRCodeRevision: row.Revision,
		LocationID:     row.LocationID,
		InvoiceNumber:  req.invoiceNumber(),
		OrderNumber:    req.orderNumber(),
		Reason:         present(cause.Reason),
		SchemaVersion:  SchemaVersion,
	}

	if last != nil {
		n := last.Notification
		e.Amount = &n.Amount
		e.TipAmount = clone(n.TipAmount)
		e.PayerInfo = clone(n.PayerInfo)
		e.Currency = &n.Currency
		e.Network = &n.Network
		e.TransactionID = present(n.TransactionID)
	} else {
		if first := req.firstMethod(); first != nil {
			e.Amount = clone(first.Amount)
			e.Currency = clone(first.Currency)
		}
		e.Network = present(row.PaymentNetwork)
		e.TransactionID = present(row.EndToEndID)
	}

	if cause.Receipt != nil {
		received := cause.Receipt.ReceivedIn
		e.ReceivedInStatus = &received
		if len(cause.Receipt.Deviations) > 0 {
			e.Deviations = slices.Clone(cause.Receipt.Deviations)
		}
	}
	return e, nil
}

func present(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func clone[T any](p *T) *T {
	if p == nil {
		return nil
	}
	v := *p
	return &v
}

// requestView is the little of request_json the domain reads: what an event body needs and which
// networks the payment methods offer. The full type is the HTTP layer's (ADR-0024, Story 9.2).
type requestView struct {
	Bill *struct {
		Invoice *struct {
			Number *string `json:"number"`
		} `json:"invoice"`
		Order *struct {
			Number *string `json:"number"`
		} `json:"order"`
	} `json:"bill"`
	PaymentMethods []methodView `json:"paymentMethods"`
}

type methodView struct {
	Currency *string                    `json:"currency"`
	Amount   *int64                     `json:"amount"`
	Networks map[string]json.RawMessage `json:"networks"`
}

func readRequest(raw json.RawMessage) (requestView, error) {
	var v requestView
	if len(raw) == 0 {
		return v, nil
	}
	if err := json.Unmarshal(raw, &v); err != nil {
		return requestView{}, fmt.Errorf("reading request_json: %w", err)
	}
	return v, nil
}

func (v requestView) invoiceNumber() *string {
	if v.Bill == nil || v.Bill.Invoice == nil {
		return nil
	}
	return clone(v.Bill.Invoice.Number)
}

func (v requestView) orderNumber() *string {
	if v.Bill == nil || v.Bill.Order == nil {
		return nil
	}
	return clone(v.Bill.Order.Number)
}

func (v requestView) firstMethod() *methodView {
	if len(v.PaymentMethods) == 0 {
		return nil
	}
	return &v.PaymentMethods[0]
}

// offers reports whether a payment method publishes network, matched whatever its case, as Java's
// PaymentNetworksVO.supports does: the network's key is present and not null.
func (v requestView) offers(network string) bool {
	for _, m := range v.PaymentMethods {
		for key, value := range m.Networks {
			if strings.EqualFold(key, network) && len(value) > 0 && string(value) != "null" {
				return true
			}
		}
	}
	return false
}
