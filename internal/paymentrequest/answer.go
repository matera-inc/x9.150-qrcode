// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"errors"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

// Java's words for the refusals this package gives itself.
const (
	msgCreditorChanged = "Creditor information must not change when locationId is informed"
	// msgLocationTaken answers a unique violation on location_id that survived the re-run: another
	// request took the location in between. Java's own refusal of a live holder, without the status
	// it could not read.
	msgLocationTaken = "The QRCode needs to be inactive to reuse location."
)

// answer turns what a handler failed with into the answer the Java build gives: a billing
// Violation and a domain Refusal as their problems, a statement timeout as a 503, a unique
// violation on the location as a refused rebind. A *httpapi.Problem is answered as it is; anything
// else is a 500.
func answer(err error) error {
	var (
		violation *billing.Violation
		refusal   *domain.Refusal
	)
	switch {
	case errors.As(err, &violation):
		return violationProblem(violation)
	case errors.As(err, &refusal):
		return refusalProblem(refusal)
	case errors.Is(err, store.ErrDuplicate):
		return httpapi.BusinessRule("", msgLocationTaken)
	case errors.Is(err, store.ErrUnavailable):
		return httpapi.Unavailable()
	}
	return err
}

func violationProblem(v *billing.Violation) *httpapi.Problem {
	switch v.Kind {
	case billing.Invalid:
		return httpapi.Validation(v.Error())
	case billing.BusinessRule:
		return httpapi.BusinessRule(v.Field, v.Message)
	}
	return httpapi.BusinessRule(v.Field, v.Message)
}

func refusalProblem(r *domain.Refusal) *httpapi.Problem {
	switch r.Kind {
	case domain.KindConflict:
		return httpapi.StatusConflict(string(r.CurrentStatus), r.Message)
	case domain.KindPreconditionFailed:
		return httpapi.PreconditionFailed(string(r.CurrentStatus), int64(r.CurrentRevision), r.Message)
	case domain.KindBusinessRule:
		return httpapi.BusinessRule(r.Field, r.Message)
	}
	return httpapi.BusinessRule(r.Field, r.Message)
}

// notFound is Java's 404 for a QR Code id nobody issued, or a revision it is not at.
func notFound(id uuid.UUID) *httpapi.Problem {
	return httpapi.NotFound("Could not find QR code with id: " + httpapi.FormatID(id))
}
