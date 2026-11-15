// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"bytes"
	"encoding/base64"
	"encoding/json/jsontext"
	"strings"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/emv"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

// location is PaymentRequestLocation.
type location struct {
	ID       string `json:"id"`
	Endpoint string `json:"endpoint"`
}

// created is PaymentRequestResponse, the answer to a create, as Java writes it.
type created struct {
	ID        string   `json:"id"`
	QRCode    string   `json:"qrCode"`
	QRCodeB64 string   `json:"qrCodeB64"`
	Location  location `json:"location"`
}

func (s *Service) created(q store.QRCode) created {
	return created{
		ID:        httpapi.FormatID(q.ID),
		QRCode:    q.EMVContent,
		QRCodeB64: base64.StdEncoding.EncodeToString([]byte(q.EMVContent)),
		Location:  s.location(q),
	}
}

func (s *Service) location(q store.QRCode) location {
	id := httpapi.FormatID(q.LocationID)
	return location{ID: id, Endpoint: s.endpoint(id)}
}

// endpoint is the location's URL as Java advertises it: the public host's, with https:// in front
// when the host carries no scheme.
func (s *Service) endpoint(locationID string) string {
	url := emv.LocationURL(s.host, locationID)
	if strings.Contains(s.host, "://") {
		return url
	}
	return "https://" + url
}

// view is PaymentRequestInformation, the answer to a GET, composed from the row and its
// request_json (ADR-0024): the request as Java reads it back, with its defaults filled in and no
// null member (ADR-0028), the location from its column, and the lifecycle at now. The members come
// in Java's order: the response's, the request's, then the lifecycle's.
func (s *Service) view(q store.QRCode, now time.Time) (jsontext.Value, error) {
	req, err := readRequest(q.RequestJSON)
	if err != nil {
		return nil, err
	}
	javaDefaults(&req)
	req.LocationID, req.ValidUntil = contract.String{}, contract.Timestamp{}
	request, err := withoutNulls(req)
	if err != nil {
		return nil, err
	}

	var buf bytes.Buffer
	enc := jsontext.NewEncoder(&buf)
	w := &writer{enc: enc}
	w.token(jsontext.BeginObject)
	head := s.created(q)
	w.member("id", head.ID)
	w.member("qrCode", head.QRCode)
	w.member("qrCodeB64", head.QRCodeB64)
	w.member("location", head.Location)
	w.member("validUntil", contract.FormatInstant(q.ValidUntil))
	w.members(request)
	w.member("revision", q.Revision)
	w.member("createdAt", contract.FormatInstant(q.CreatedAt))
	w.member("revisedAt", contract.FormatInstant(q.RevisedAt))
	w.member("sentAt", contract.FormatInstant(now))
	w.member("status", string(domain.EffectiveStatus(q.Row, now)))
	if q.EndToEndID != "" || q.PaymentNetwork != "" {
		w.member("paymentDetails", paymentDetails{EndToEndID: nonBlank(q.EndToEndID), Network: nonBlank(q.PaymentNetwork)})
	}
	w.token(jsontext.EndObject)
	if w.err != nil {
		return nil, w.err
	}
	return jsontext.Value(bytes.TrimRight(buf.Bytes(), "\n")), nil
}

// paymentDetails is BasePaymentDetails: what status-update PAID recorded.
// The columns hold "" for a detail that was not given, which is not written.
type paymentDetails struct {
	EndToEndID *string `json:"endToEndId,omitzero"`
	Network    *string `json:"network,omitzero"`
}

func nonBlank(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// javaDefaults fills in what Java's read of a stored request shows that the body did not carry:
// paymentTiming immediate; a bill with no tip block as tips refused, and a tip block without
// allowed as allowed; and no empty list of discounts or of additional information.
func javaDefaults(req *contract.CreateRequest) {
	req.AdditionalInformation = nonEmpty(req.AdditionalInformation)
	if !req.Bill.IsSet() {
		return
	}
	b := &req.Bill.Value
	if !b.PaymentTiming.IsSet() {
		b.PaymentTiming = contract.EnumOf[contract.TimingValues]("immediate")
	}
	switch {
	case !b.Tip.IsSet():
		b.Tip = contract.Obj(contract.Tip{Allowed: contract.Bool(false)})
	case !b.Tip.Value.Allowed.IsSet():
		b.Tip.Value.Allowed = contract.Bool(true)
	}
	p := &b.AmountDue.Value.Adjustments.Value.Parameters.Value
	p.Discounts = nonEmpty(p.Discounts)
}

// nonEmpty is l, or no member at all when l holds nothing: Java writes an empty list as null.
func nonEmpty[T any](l contract.List[T]) contract.List[T] {
	if len(l.Items) == 0 {
		return contract.List[T]{}
	}
	return l
}

// writer writes members to an encoder, keeping the first error.
type writer struct {
	enc *jsontext.Encoder
	err error
}

func (w *writer) token(t jsontext.Token) {
	if w.err == nil {
		w.err = w.enc.WriteToken(t)
	}
}

func (w *writer) member(name string, v any) {
	w.token(jsontext.String(name))
	if w.err != nil {
		return
	}
	raw, err := httpapi.Marshal(v)
	if err != nil {
		w.err = err
		return
	}
	w.err = w.enc.WriteValue(raw)
}

// members writes every member of the object obj.
func (w *writer) members(obj jsontext.Value) {
	if w.err != nil {
		return
	}
	dec := jsontext.NewDecoder(bytes.NewReader(obj))
	if _, err := dec.ReadToken(); err != nil {
		w.err = err
		return
	}
	for dec.PeekKind() != '}' {
		tok, err := dec.ReadToken()
		if err != nil {
			w.err = err
			return
		}
		name := tok.Clone() // the next read voids tok
		value, err := dec.ReadValue()
		if err != nil {
			w.err = err
			return
		}
		w.token(name)
		if w.err == nil {
			w.err = w.enc.WriteValue(value)
		}
	}
}
