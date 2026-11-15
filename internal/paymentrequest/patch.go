// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"bytes"
	"context"
	"encoding/json/jsontext"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

const opPatch = "patchPaymentRequest"

// Java's words for the refusals of a PATCH this package gives itself.
const (
	// msgPatchCreditorChanged is Java's, typo included, so that both builds name it alike.
	msgPatchCreditorChanged = "Creditor information must be equal to original location whe updating location id"
	msgNothingToUpdate      = "can not find any paymentMethod to be updated."
	msgBillNull             = "must not be updated with null."
	msgLocationNull         = "LocationId can not be null"
	msgValidUntilNull       = "ValidUntil must not be null."
)

// The tags a PATCH can change (ADR-0024), in the order an audit row lists them.
const (
	tagLocationID            = "locationId"
	tagValidUntil            = "validUntil"
	tagUnstructured          = "unstructured"
	tagAdditionalInformation = "additionalInformation"
	tagBill                  = "bill"
	tagPaymentMethods        = "paymentMethods"
)

// patch is PATCH /api/v1/payment-request/{id} (Story 9.7): the request merged into the stored
// one, held to the rules a create is held to on the merged result, and written with its audit row
// in one transaction (ADR-0024). Nothing is written when it is refused, so a refused PATCH never
// moves the revision or the ETag.
func (s *Service) patch(w http.ResponseWriter, r *http.Request) error {
	var req contract.PatchRequest
	if err := httpapi.DecodeJSON(r, &req); err != nil {
		return err
	}
	if violations := req.Validate(); violations != nil {
		return httpapi.Validation(violations...)
	}
	raw, err := httpapi.PathID(r, opPatch, "id")
	if err != nil {
		return err
	}
	// Java's request mapper refuses an uninterpreted network before the QR Code is read.
	methods := make([]billing.PaymentMethod, 0, len(req.PaymentMethods.Items))
	for _, m := range req.PaymentMethods.Items {
		methods = append(methods, billingMethod(m))
	}
	if err := billing.CheckNetworks(methods); err != nil {
		return answer(err)
	}
	id, ok := httpapi.ParseID(raw)
	if !ok {
		return httpapi.InvalidQRCodeID()
	}
	var ifMatch *string
	if tag, conditional := httpapi.ParseIfMatch(r.Header); conditional {
		ifMatch = &tag
	}

	script := func(ctx context.Context, rd *store.Reader, now time.Time) (store.Change, error) {
		return s.decidePatch(ctx, rd, now, id, &req, ifMatch)
	}
	res, err := s.store.Run(r.Context(), script)
	if errors.Is(err, store.ErrDuplicate) {
		// Another request took the location after this one read it: decide again on what it
		// holds now (ADR-0024).
		res, err = s.store.Run(r.Context(), script)
	}
	if errors.Is(err, store.ErrNotFound) {
		return notFound(id)
	}
	if err != nil {
		return answer(err)
	}
	if res.Decision.Refusal != nil {
		return answer(res.Decision.Refusal)
	}
	q := res.QRCode
	httpapi.SetETag(w, domain.ETag(q.Row, q.RevisedAt))
	return httpapi.WriteJSON(w, http.StatusOK, s.created(q))
}

// decidePatch is one attempt of a PATCH, in Java's order: If-Match and the stored status
// (domain.Patch), the location, then each tag applied to the stored request and the merged result
// checked. A refusal after domain.Patch returns an error, so the attempt writes nothing, the
// holder's release included.
func (s *Service) decidePatch(ctx context.Context, rd *store.Reader, now time.Time, id uuid.UUID,
	req *contract.PatchRequest, ifMatch *string,
) (store.Change, error) {
	before, err := rd.QRCode(ctx, id)
	if err != nil {
		return store.Change{}, err
	}
	// What a GET carries but a PATCH cannot change is accepted when it equals the stored value and
	// refused by name when it does not (INTERPRETATION I-16, owner 2026-11-15, option B), compared
	// with the GET answer at now, before anything else, as the Java build does.
	if readOnly := req.ReadOnly(); len(readOnly) > 0 {
		notification, err := rd.LatestNotification(ctx, id)
		if err != nil {
			return store.Change{}, err
		}
		answer, err := s.view(before, notification, now)
		if err != nil {
			return store.Change{}, err
		}
		changed, err := contract.ChangedReadOnly(answer, readOnly)
		if err != nil {
			return store.Change{}, err
		}
		if changed != nil {
			return store.Change{}, httpapi.Validation(changed...)
		}
	}
	d := domain.Patch(before.Row, ifMatch, now)
	if !d.Write { // refused: the refusal is the answer, in the decision, and nothing is written
		return store.Change{Before: &before, Decision: d}, nil
	}
	stored, err := readRequest(before.RequestJSON)
	if err != nil {
		return store.Change{}, err
	}

	loc, release, err := s.patchLocation(ctx, rd, now, before, stored.Creditor, req.LocationID)
	if err != nil {
		return store.Change{}, err
	}
	merged, err := s.merge(stored, req, now)
	if err != nil {
		return store.Change{}, err
	}
	audit, err := auditRow(before, stored, req, merged)
	if err != nil {
		return store.Change{}, err
	}
	requestJSON, err := storedRequest(merged)
	if err != nil {
		return store.Change{}, err
	}

	d.Row.RequestJSON = requestJSON
	d.Row.ValidUntil = merged.ValidUntil.Value
	// A reservation cannot outlive what it reserves (ADR-0019): an earlier validUntil cuts it back.
	d.Row = domain.CapReservation(d.Row)
	change := store.Change{Release: release, Before: &before, Audit: audit}
	if loc != before.LocationID {
		d.Row.LocationID = loc
		if change.EMVContent, err = s.emv(loc, stored.Creditor.Value); err != nil {
			return store.Change{}, err
		}
	}
	change.Decision = d
	return change, nil
}

// patchLocation is where the PATCH puts the QR Code: where it is when locationId is absent or is
// its own location; otherwise the location asked for, taken from its holder when the holder may
// give it up (S9.6-AC2's rules, with Java's PATCH wording for another creditor).
func (s *Service) patchLocation(ctx context.Context, rd *store.Reader, now time.Time, before store.QRCode,
	creditor contract.Object[contract.Creditor], requested contract.String,
) (uuid.UUID, *store.Release, error) {
	if !requested.Present {
		return before.LocationID, nil, nil
	}
	if requested.Null {
		return uuid.UUID{}, nil, httpapi.Validation(msgLocationNull)
	}
	loc, ok := httpapi.ParseID(requested.Value)
	if !ok {
		return uuid.UUID{}, nil, httpapi.InvalidLocationID()
	}
	if loc == before.LocationID {
		return loc, nil, nil
	}
	holder, err := rd.QRCodeByLocation(ctx, loc)
	switch {
	case errors.Is(err, store.ErrNotFound):
		return loc, nil, nil
	case err != nil:
		return uuid.UUID{}, nil, err
	}
	release, err := s.release(holder, creditor, now, msgPatchCreditorChanged)
	if err != nil {
		return uuid.UUID{}, nil, err
	}
	return loc, release, nil
}

// merge applies a PATCH to the stored request and checks the result, in Java's order: validUntil,
// unstructured and additionalInformation; the bill, checked as soon as it is applied (its values,
// its due date, its tip); the currencies the PATCH names (ADR-0018); each merged payment method;
// then whether anything changed, whether a changed method is already past, and the merged request
// as a whole, a date the PATCH changed included (billing.CheckPatch).
func (s *Service) merge(stored contract.CreateRequest, p *contract.PatchRequest, now time.Time) (contract.CreateRequest, error) {
	out := stored
	if p.ValidUntil.Present {
		if p.ValidUntil.Null {
			return out, &billing.Violation{Kind: billing.Invalid, Message: msgValidUntilNull}
		}
		out.ValidUntil = p.ValidUntil
	}
	if p.AdditionalInformation.Present {
		out.AdditionalInformation = nonEmpty(p.AdditionalInformation)
	}
	if p.Unstructured.Present {
		out.Unstructured = p.Unstructured
		if p.Unstructured.Null {
			out.Unstructured = contract.String{}
		}
	}
	if p.Bill.Present {
		if p.Bill.Null {
			return out, &billing.Violation{Kind: billing.BusinessRule, Field: tagBill, Message: msgBillNull}
		}
		out.Bill = contract.Obj(mergeBill(stored.Bill.Value, p.Bill.Value))
		rules := billingRequest(&out)
		if err := billing.CheckBill(rules.Bill); err != nil {
			return out, err
		}
		if err := billing.CheckInvoiceDueDate(rules); err != nil {
			return out, err
		}
		// ADR-0017 as on create, on the merged tip: presets the PATCH left out are kept, so an
		// allowed tip with neither a range nor presets of its own may still be an offer.
		if err := billing.CheckTip(rules.Bill.Tip); err != nil {
			return out, err
		}
	}

	if err := requireCurrencies(stored.PaymentMethods.Items, p.PaymentMethods.Items); err != nil {
		return out, err
	}
	methods := make([]contract.PaymentMethod, len(stored.PaymentMethods.Items))
	for i, old := range stored.PaymentMethods.Items {
		j := slices.IndexFunc(p.PaymentMethods.Items, func(m contract.PaymentMethod) bool {
			return m.Currency.Value == old.Currency.Value
		})
		methods[i] = mergeMethod(old, p.PaymentMethods.Items[j]) // requireCurrencies found each one
		if err := billing.CheckPaymentMethod(i, billingMethod(methods[i])); err != nil {
			return out, err
		}
	}
	out.PaymentMethods = contract.Of(methods...)

	changed, err := changedMethods(stored.PaymentMethods.Items, methods)
	if err != nil {
		return out, err
	}
	otherTags := p.LocationID.Present || p.ValidUntil.Present || p.Bill.Present || p.Unstructured.Present ||
		p.AdditionalInformation.Present
	if !otherTags && !slices.Contains(changed, true) {
		return out, &billing.Violation{Kind: billing.BusinessRule, Field: tagPaymentMethods, Message: msgNothingToUpdate}
	}
	for i, m := range methods {
		if changed[i] && m.ValidUntil.Value.Before(now) {
			return out, &billing.Violation{Kind: billing.BusinessRule, Message: fmt.Sprintf(
				"Can not update paymentMethods with currency %s. ValidUntil must be after or equal to actual date.",
				m.Currency.Value)}
		}
	}
	return out, billing.CheckPatch(billingRequest(&out), billingRequest(&stored), s.currencies, now)
}

// requireCurrencies is ADR-0018: a PATCH names every currency the QR Code offers, and no other.
// A currency it does not offer is named; then one left out is named; then one named twice, which
// Java keeps the last of in silence.
func requireCurrencies(stored, patched []contract.PaymentMethod) error {
	var offered, informed []string
	for _, m := range stored {
		if !slices.Contains(offered, m.Currency.Value) {
			offered = append(offered, m.Currency.Value)
		}
	}
	for _, m := range patched {
		if !slices.Contains(informed, m.Currency.Value) {
			informed = append(informed, m.Currency.Value)
		}
	}
	missingFrom := func(set, of []string) []string {
		var out []string
		for _, c := range of {
			if !slices.Contains(set, c) {
				out = append(out, c)
			}
		}
		return out
	}
	if notOffered := missingFrom(offered, informed); len(notOffered) > 0 {
		return &billing.Violation{Kind: billing.BusinessRule, Field: tagPaymentMethods, Message: fmt.Sprintf(
			"This QR Code offers %s. A patch may change the amount of a currency already on it, never add one — %s is not here.",
			strings.Join(offered, ", "), strings.Join(notOffered, ", "))}
	}
	if left := missingFrom(informed, offered); len(left) > 0 {
		return &billing.Violation{Kind: billing.BusinessRule, Field: tagPaymentMethods, Message: fmt.Sprintf(
			"A patch that changes amounts must inform every currency this QR Code offers (%s); %s was left out.",
			strings.Join(offered, ", "), strings.Join(left, ", "))}
	}
	if len(patched) != len(informed) {
		for _, c := range informed {
			n := 0
			for _, m := range patched {
				if m.Currency.Value == c {
					n++
				}
			}
			if n > 1 {
				return &billing.Violation{Kind: billing.BusinessRule, Field: tagPaymentMethods,
					Message: "must not contain duplicated currency: " + c}
			}
		}
	}
	return nil
}

// changedMethods reports, method by method, whether the merged method differs from the stored one
// in value: every member compared, an absent one and a null one alike (ADR-0024).
func changedMethods(stored, merged []contract.PaymentMethod) ([]bool, error) {
	out := make([]bool, len(merged))
	for i := range merged {
		a, err := withoutNulls(stored[i])
		if err != nil {
			return nil, err
		}
		b, err := withoutNulls(merged[i])
		if err != nil {
			return nil, err
		}
		out[i] = !bytes.Equal(a, b)
	}
	return out, nil
}

// mergeBill is the stored bill with a PATCH's bill applied, as Java's PATCH mapper applies it: the
// PATCH restates the bill, so description, paymentTiming, the amount and an order, an invoice or a
// tip it leaves out are its own; what it may leave out inside them keeps its stored value (an
// order's date; an invoice's invoicee, its phone, email and address, and the address's line2, state
// and postalCode; a tip's presets; the adjustments, and their discounts). The currency is the
// stored one. A tip that is absent, null or not allowed is no tip, the stored range and presets
// going with it; a tip not allowed that itself sends a range or presets is kept as sent, and the
// tip rule then refuses it.
//
// Java drops an order, an invoice, an invoicee or adjustments sent to a bill that had none, and
// answers 200; Go applies them, and they are then held to the bill's rules.
func mergeBill(old contract.Bill, p contract.PatchBill) contract.Bill {
	out := contract.Bill{PaymentTiming: p.PaymentTiming, Description: p.Description}

	if o := p.Order; o.IsSet() {
		order := o.Value
		if !order.Date.Present && old.Order.IsSet() {
			order.Date = old.Order.Value.Date
		}
		out.Order = contract.Obj(order)
	}

	if i := p.Invoice; i.IsSet() {
		inv := i.Value
		var oldInvoicee contract.Object[contract.Invoicee]
		if old.Invoice.IsSet() {
			oldInvoicee = old.Invoice.Value.Invoicee
		}
		switch {
		case !inv.Invoicee.Present:
			inv.Invoicee = oldInvoicee
		case inv.Invoicee.Null:
			inv.Invoicee = contract.Object[contract.Invoicee]{}
		case oldInvoicee.IsSet():
			inv.Invoicee = contract.Obj(mergeInvoicee(oldInvoicee.Value, inv.Invoicee.Value))
		}
		out.Invoice = contract.Obj(inv)
	}

	switch t := p.Tip; {
	case !t.IsSet():
	case !t.Value.Allowed.IsSet() || t.Value.Allowed.Value:
		tip := contract.Tip{Allowed: contract.Bool(true), Range: t.Value.Range, Presets: t.Value.Presets}
		switch {
		case !tip.Presets.Present && old.Tip.IsSet():
			tip.Presets = old.Tip.Value.Presets
		case tip.Presets.Null:
			tip.Presets = contract.List[contract.Integer]{}
		}
		out.Tip = contract.Obj(tip)
	case t.Value.Range.IsSet() || len(t.Value.Presets.Items) > 0:
		// Tips refused, with a range or presets beside it: kept as sent, for the tip rule to
		// refuse (ADR-0017). It used to be dropped, and the PATCH answered 200.
		out.Tip = contract.Obj(contract.Tip{Allowed: contract.Bool(false), Range: t.Value.Range, Presets: t.Value.Presets})
	}

	pa := p.AmountDue.Value
	amount := contract.AmountDue{Amount: pa.Amount}
	var oldAdjustments contract.Object[contract.Adjustment]
	if old.AmountDue.IsSet() {
		amount.Currency = old.AmountDue.Value.Currency
		oldAdjustments = old.AmountDue.Value.Adjustments
	}
	switch {
	case !pa.Adjustments.Present:
		amount.Adjustments = oldAdjustments
	case pa.Adjustments.Null:
	default:
		adj := pa.Adjustments.Value
		params := adj.Parameters.Value
		switch {
		case !params.Discounts.Present && oldAdjustments.IsSet():
			params.Discounts = oldAdjustments.Value.Parameters.Value.Discounts
		case len(params.Discounts.Items) == 0:
			params.Discounts = contract.List[contract.Discount]{}
		}
		adj.Parameters = contract.Obj(params)
		amount.Adjustments = contract.Obj(adj)
	}
	out.AmountDue = contract.Obj(amount)
	return out
}

// mergeInvoicee keeps the stored phone, email and address when the PATCH leaves them out.
func mergeInvoicee(old, p contract.Invoicee) contract.Invoicee {
	out := p
	if !p.Phone.Present {
		out.Phone = old.Phone
	}
	if !p.Email.Present {
		out.Email = old.Email
	}
	switch {
	case !p.Address.Present:
		out.Address = old.Address
	case p.Address.IsSet() && old.Address.IsSet():
		out.Address = contract.Obj(mergeAddress(old.Address.Value, p.Address.Value))
	}
	return out
}

// mergeAddress keeps the stored line2, state and postalCode when the PATCH leaves them out.
func mergeAddress(old, p contract.Address) contract.Address {
	out := p
	if !p.Line2.Present {
		out.Line2 = old.Line2
	}
	if !p.State.Present {
		out.State = old.State
	}
	if !p.PostalCode.Present {
		out.PostalCode = old.PostalCode
	}
	return out
}

// mergeMethod is a stored payment method with the PATCH's method of the same currency applied:
// its validUntil and amount; editable when sent (its range kept when left out, no editable when
// null); and each rail it sends, a null one removed, the others kept.
func mergeMethod(old, p contract.PaymentMethod) contract.PaymentMethod {
	out := contract.PaymentMethod{
		Currency: old.Currency, ValidUntil: p.ValidUntil, Amount: p.Amount,
		Editable: old.Editable, Networks: old.Networks,
	}
	switch e := p.Editable; {
	case !e.Present:
	case e.Null:
		out.Editable = contract.Object[contract.Editable]{}
	default:
		editable := e.Value
		if !editable.Range.Present && old.Editable.IsSet() {
			editable.Range = old.Editable.Value.Range
		}
		out.Editable = contract.Obj(editable)
	}
	n, pn := old.Networks.Value, p.Networks.Value
	n.FedNow = rail(n.FedNow, pn.FedNow)
	n.RTP = rail(n.RTP, pn.RTP)
	n.ACH = rail(n.ACH, pn.ACH)
	n.Solana = rail(n.Solana, pn.Solana)
	out.Networks = contract.Obj(n)
	return out
}

// rail is the stored rail, replaced when the PATCH sends it and removed when it sends null.
func rail[T any](old, p contract.Object[T]) contract.Object[T] {
	switch {
	case !p.Present:
		return old
	case p.Null:
		return contract.Object[T]{}
	}
	return p
}

// auditRow is the PATCH's audit row (ADR-0024): every tag it changed, with the value it had
// before. A tag counts when it is present; paymentMethods when its value differs. The previous
// locationId is the column's; a tag that had no value is a JSON null.
func auditRow(before store.QRCode, stored contract.CreateRequest, p *contract.PatchRequest,
	merged contract.CreateRequest,
) (*store.Audit, error) {
	var (
		fields []string
		buf    bytes.Buffer
	)
	w := &writer{enc: jsontext.NewEncoder(&buf)}
	add := func(tag string, previous any) {
		fields = append(fields, tag)
		w.member(tag, previous)
	}
	w.token(jsontext.BeginObject)
	if p.LocationID.Present {
		add(tagLocationID, httpapi.FormatID(before.LocationID))
	}
	if p.ValidUntil.Present {
		add(tagValidUntil, stored.ValidUntil)
	}
	if p.Unstructured.Present {
		add(tagUnstructured, valueOrNull(stored.Unstructured.IsSet(), stored.Unstructured))
	}
	if p.AdditionalInformation.Present {
		add(tagAdditionalInformation, valueOrNull(len(stored.AdditionalInformation.Items) > 0, stored.AdditionalInformation))
	}
	if p.Bill.Present {
		add(tagBill, valueOrNull(stored.Bill.IsSet(), stored.Bill))
	}
	changed, err := changedMethods(stored.PaymentMethods.Items, merged.PaymentMethods.Items)
	if err != nil {
		return nil, err
	}
	if slices.Contains(changed, true) {
		add(tagPaymentMethods, stored.PaymentMethods)
	}
	w.token(jsontext.EndObject)
	if w.err != nil {
		return nil, fmt.Errorf("writing the audit row: %w", w.err)
	}
	return &store.Audit{ChangedFields: fields, PreviousValues: bytes.TrimRight(buf.Bytes(), "\n")}, nil
}

// valueOrNull is v, or nil (a JSON null) when it held no value.
func valueOrNull(set bool, v any) any {
	if !set {
		return nil
	}
	return v
}
