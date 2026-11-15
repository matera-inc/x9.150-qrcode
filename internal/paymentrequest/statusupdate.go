// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"errors"
	"net/http"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

// The operationId of the status-update route in openapi.yaml, which Java's violations name.
const opStatusUpdate = "putPaymentRequestStatusUpdate"

// StatusUpdates is the payment system's control over its own receivable (Story 9.8): reserve,
// settle, cancel and release a QR Code through PUT /api/v1/payment-request/{id}/status-update.
//
// The decision is domain.UpdateStatus's (ADR-0035, X9-MAP-010 to -013), run by the store as one
// transition (ADR-0025): the If-Match first, then the network, then the status it already has as a
// retry that writes nothing (ADR-0020), then the transition and its event, in the same
// transaction. The revision never moves (ADR-0016); only this route ever marks a QR Code PAID
// (ADR-0036).
type StatusUpdates struct {
	store    *store.Store
	settings domain.Settings
}

// NewStatusUpdates returns the route's service. reservationTTL is x9.reservation.ttl-seconds: the
// window of the unattributed reservation a PAYMENT_INITIATED takes, 0 for none (ADR-0036).
func NewStatusUpdates(st *store.Store, reservationTTL time.Duration) *StatusUpdates {
	return &StatusUpdates{store: st, settings: domain.Settings{ReservationTTL: reservationTTL}}
}

// Register adds the route to api.
func (s *StatusUpdates) Register(api *httpapi.API) {
	api.Handle("PUT /api/v1/payment-request/{id}/status-update", s.update)
}

// statusUpdated is StatusUpdateResponse: the id and the effective status after the update.
type statusUpdated struct {
	ID     string `json:"id"`
	Status string `json:"status"`
}

// update is PUT /api/v1/payment-request/{id}/status-update (S9.8-AC1 to AC6), in Java's order:
// the body and its contract, the id, then the transition. Java writes no ETag on this answer; the
// next GET carries the tag, which follows the status while the revision stays (R-ADR0016-02).
func (s *StatusUpdates) update(w http.ResponseWriter, r *http.Request) error {
	var req contract.StatusUpdate
	if err := httpapi.DecodeJSON(r, &req); err != nil {
		return err
	}
	if violations := req.Validate(); violations != nil {
		return httpapi.Validation(violations...)
	}
	raw, err := httpapi.PathID(r, opStatusUpdate, "id")
	if err != nil {
		return err
	}
	id, ok := httpapi.ParseID(raw)
	if !ok {
		return httpapi.InvalidQRCodeID()
	}
	status, err := domain.ParseStatus(req.Status.Value)
	if err != nil {
		// The contract's enum admits only the four statuses: unreachable, but never a 500.
		return httpapi.Validation("status: " + err.Error())
	}

	update := domain.StatusUpdate{Status: status}
	if req.EndToEndID.IsSet() {
		update.EndToEndID = &req.EndToEndID.Value
	}
	if req.Network.IsSet() {
		update.PaymentNetwork = &req.Network.Value
	}
	if tag, conditional := httpapi.ParseIfMatch(r.Header); conditional {
		update.IfMatch = &tag
	}

	res, err := s.store.Transition(r.Context(), id, nil,
		func(row domain.Row, last *domain.NotificationRecord, now time.Time) (domain.Decision, error) {
			return domain.UpdateStatus(row, update, last, now, s.settings)
		})
	if errors.Is(err, store.ErrNotFound) {
		return notFound(id)
	}
	if err != nil {
		return answer(err)
	}
	if res.Decision.Refusal != nil {
		return answer(res.Decision.Refusal)
	}
	return httpapi.WriteJSON(w, http.StatusOK, statusUpdated{
		ID:     httpapi.FormatID(id),
		Status: string(res.Decision.Status),
	})
}
