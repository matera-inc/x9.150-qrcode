// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package paymentrequest is a biller's API on a QR Code: create it, and read it back (Story 9.6),
// answered as the Java build answers (ADR-0028).
//
// A create is read into the contract's type, checked by the contract (internal/contract), then by
// the business rules (internal/billing) in Java's order, and written in one transaction
// (internal/store): a holder of the requested location gives it up first when it may (ADR-0024),
// then the QR Code is inserted with its EMV string. No event is written (X9-MAP-001). A GET reads
// one row and composes the answer from its columns and its request_json.
package paymentrequest

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/emv"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

// The operationIds of openapi.yaml, which Java's violations name.
const opGet = "getPaymentRequest"

// Service answers the biller's routes.
type Service struct {
	store      *store.Store
	currencies *billing.Currencies
	host       string
	now        func() time.Time
}

// New returns the service. host is the public host every location URL is built on; now is the
// business clock, read for what a GET shows at that instant.
func New(st *store.Store, currencies *billing.Currencies, host string, now func() time.Time) *Service {
	return &Service{store: st, currencies: currencies, host: host, now: now}
}

// Register adds the routes to api.
func (s *Service) Register(api *httpapi.API) {
	api.Handle("POST /api/v1/payment-request", s.create)
	api.Handle("GET /api/v1/payment-request/{id}", s.get)
}

// create is POST /api/v1/payment-request (S9.6-AC1 to AC3).
func (s *Service) create(w http.ResponseWriter, r *http.Request) error {
	var req contract.CreateRequest
	if err := httpapi.DecodeJSON(r, &req); err != nil {
		return err
	}
	if violations := req.Validate(); violations != nil {
		return httpapi.Validation(violations...)
	}
	rules := billingRequest(&req)
	// Java's request mapper refuses an uninterpreted network before anything else is read.
	if err := billing.CheckNetworks(rules.PaymentMethods); err != nil {
		return answer(err)
	}
	var requested *uuid.UUID
	if req.LocationID.IsSet() {
		id, ok := httpapi.ParseID(req.LocationID.Value)
		if !ok {
			return httpapi.InvalidLocationID()
		}
		requested = &id
	}
	stored, err := storedRequest(req)
	if err != nil {
		return err
	}
	script := func(ctx context.Context, rd *store.Reader, now time.Time) (store.Change, error) {
		return s.decideCreate(ctx, rd, now, &req, rules, requested, stored)
	}
	res, err := s.store.Run(r.Context(), script)
	if errors.Is(err, store.ErrDuplicate) {
		// Another request took the location after this one read it: decide again on what it
		// holds now, which names the holder's status (ADR-0024).
		res, err = s.store.Run(r.Context(), script)
	}
	if err != nil {
		return answer(err)
	}
	if res.Decision.Refusal != nil {
		return answer(res.Decision.Refusal)
	}
	q := res.QRCode
	httpapi.SetETag(w, domain.ETag(q.Row, q.CreatedAt))
	return httpapi.WriteJSON(w, http.StatusCreated, s.created(q))
}

// decideCreate is one attempt of a create, in Java's order: the requested location's holder (the
// same creditor, and not live), then the business rules at now, then the new row. A refusal
// returns an error, so the attempt writes nothing, the holder's release included.
func (s *Service) decideCreate(ctx context.Context, rd *store.Reader, now time.Time, req *contract.CreateRequest,
	rules billing.Request, requested *uuid.UUID, stored []byte,
) (store.Change, error) {
	var release *store.Release
	if requested != nil {
		holder, err := rd.QRCodeByLocation(ctx, *requested)
		switch {
		case errors.Is(err, store.ErrNotFound):
		case err != nil:
			return store.Change{}, err
		default:
			if release, err = s.release(holder, req.Creditor, now); err != nil {
				return store.Change{}, err
			}
		}
	}
	if err := billing.CheckCreate(rules, s.currencies, now); err != nil {
		return store.Change{}, err
	}
	id, err := store.NewQRCodeID()
	if err != nil {
		return store.Change{}, err
	}
	loc := id // the QR Code's own id, as Java does (ADR-0024, ADR-0026)
	if requested != nil {
		loc = *requested
	}
	content, err := s.emv(loc, req.Creditor.Value)
	if err != nil {
		return store.Change{}, err
	}
	d := domain.Create(domain.NewQRCode{ID: id, LocationID: loc, ValidUntil: req.ValidUntil.Value, RequestJSON: stored}, now)
	return store.Change{Release: release, Decision: d, EMVContent: content}, nil
}

// release decides whether holder gives its location up to a create by creditor: only to the same
// creditor, and only when it is not live (ADR-0024; an expired ACTIVE holder is not live, which
// Java does not see, BUG-004). It moves to a fresh location with its new EMV string; its revision
// does not move and nothing is emitted (X9-MAP-050).
func (s *Service) release(holder store.QRCode, creditor contract.Object[contract.Creditor], now time.Time) (*store.Release, error) {
	held, err := readRequest(holder.RequestJSON)
	if err != nil {
		return nil, err
	}
	same, err := sameCreditor(held.Creditor, creditor)
	if err != nil {
		return nil, err
	}
	if !same {
		return nil, httpapi.BusinessRule("", msgCreditorChanged)
	}
	fresh, err := store.NewLocationID()
	if err != nil {
		return nil, err
	}
	d := domain.ReleaseLocation(holder.Row, fresh, now)
	if d.Refusal != nil {
		return nil, d.Refusal
	}
	content, err := s.emv(fresh, held.Creditor.Value)
	if err != nil {
		return nil, err
	}
	return &store.Release{Holder: holder, Decision: d, EMVContent: content}, nil
}

// emv is the EMV string printed for a location: written only with location_id, never recomputed
// (ADR-0024).
func (s *Service) emv(location uuid.UUID, c contract.Creditor) (string, error) {
	a := c.Address.Value
	content, err := emv.Encode(emv.LocationURL(s.host, httpapi.FormatID(location)), emv.Merchant{
		CategoryCode: c.MCC.Value, Country: a.Country.Value, Name: c.Name.Value, City: a.City.Value,
	})
	if err != nil {
		return "", fmt.Errorf("writing the EMV string of location %s: %w", httpapi.FormatID(location), err)
	}
	return content, nil
}

// get is GET /api/v1/payment-request/{id} (S9.6-AC5): one row, read on the read pool, at one
// instant for both the status and the ETag. An expired QR Code is still read (ADR-0029).
func (s *Service) get(w http.ResponseWriter, r *http.Request) error {
	revision, atRevision, err := httpapi.QueryInt(r, opGet, "revision", httpapi.IntBounds{Min: new(int64(0))})
	if err != nil {
		return err
	}
	raw, err := httpapi.PathID(r, opGet, "id")
	if err != nil {
		return err
	}
	id, ok := httpapi.ParseID(raw)
	if !ok {
		return httpapi.InvalidQRCodeID()
	}
	q, err := s.store.Get(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		return notFound(id)
	}
	if err != nil {
		return answer(err)
	}
	// ?revision= matches the current revision only: no endpoint reads the audit log (ADR-0024).
	if atRevision && revision != int64(q.Revision) {
		return notFound(id)
	}
	now := s.now()
	body, err := s.view(q, now)
	if err != nil {
		return err
	}
	httpapi.SetETag(w, domain.ETag(q.Row, now))
	return httpapi.WriteJSON(w, http.StatusOK, body)
}
