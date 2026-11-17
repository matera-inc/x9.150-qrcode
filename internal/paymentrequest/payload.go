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
	"io"
	"log/slog"
	"mime"
	"net/http"
	"strings"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

// The payload route, its operationId and the header it reads and writes (openapi.yaml).
const (
	PayloadPath = "/pub/api/v1/loc/{id}"
	opPayload   = "retrievePaymentPayloadByLocation"
	// HeaderDateForPayment is the date the payer intends to pay, yyyy-MM-dd (INTERPRETATION I-14).
	HeaderDateForPayment = "dateForPayment"
	// HeaderPayloadBasedDate is the date the payload was priced for: dateForPayment, or sentAt's
	// date in UTC when the payer sent none.
	HeaderPayloadBasedDate = "X-Payload-Based-Date"
	// NotificationPath is where a payer sends its payment notification on this deployment.
	NotificationPath = "/pub/api/v1/payment-notification"
)

// mediaJOSE is the request's media type; contentTypeJOSE is what the Java build writes on its answer.
const (
	mediaJOSE       = "application/jose"
	contentTypeJOSE = "application/jose;charset=UTF-8"
)

// Java's words for the payload's refusals, word for word. msgSignatureRefused and msgUnreadable are
// the one generic reason each: the words of a signature check stay in the log (openapi.yaml).
const (
	msgSignatureRefused = "Invalid JWS signature for payment payload request."
	msgUnreadable       = "Error processing payment payload retrieve."
	msgContentNotIssued = "The QR content in the signed request is not the content issued for this location."
	msgExpired          = "payment payload with ID: %s is expired."
	msgPaid             = "payment payload with ID: %s has already been paid."
	msgCancelled        = "payment payload with ID: %s was cancelled by the biller."
	msgNotPayable       = "payment payload with ID: %s is not open for payment."
)

// PayloadKeys is where the signing key is read from, on every request: *keys.Store.
type PayloadKeys interface {
	Current() *keys.Keys
}

// PayloadVerifier verifies the payer's signed request: *jws.Verifier.
type PayloadVerifier interface {
	Verify(ctx context.Context, token string, want jws.Expect) (*jws.Verified, error)
}

// Payloads answers a payer's request for the signed payload of a location (Story 10.3). It reads
// one row and writes nothing: the quote is not stored (ADR-0011 is not built).
type Payloads struct {
	store    *store.Store
	keys     PayloadKeys
	signer   *jws.Signer
	verifier PayloadVerifier
	host     string
	now      func() time.Time
	logger   *slog.Logger
}

// NewPayloads returns the route's service. signer and verifier are the deployment's one signer and
// one verifier (S10.2-AC1); host is the public host the notification endpoint is derived from; now is
// the business clock.
func NewPayloads(st *store.Store, k PayloadKeys, signer *jws.Signer, verifier PayloadVerifier, host string,
	now func() time.Time, logger *slog.Logger,
) *Payloads {
	return &Payloads{store: st, keys: k, signer: signer, verifier: verifier, host: host, now: now, logger: logger}
}

// Register adds the route to api.
func (p *Payloads) Register(api *httpapi.API) {
	api.Handle("POST "+PayloadPath, p.fetch)
}

// fetch is POST /pub/api/v1/loc/{id} (S10.3-AC1 to AC5), in Java's order: the media type, the body,
// the dateForPayment header and the id's length (Spring's binding); then the signature, which also
// checks that the signed content names this location (ADR-0027); then the row, the content byte for
// byte, the status, the expiry; then the quote at one instant, signed.
func (p *Payloads) fetch(w http.ResponseWriter, r *http.Request) error {
	if !isJOSE(r.Header.Get("Content-Type")) {
		return httpapi.SpringError(http.StatusUnsupportedMediaType, http.Header{"Accept": {mediaJOSE}})
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, httpapi.MaxBodyBytes+1))
	if err != nil || len(body) == 0 || len(body) > httpapi.MaxBodyBytes {
		return httpapi.MalformedJSON() // Spring: the required body is missing or unreadable
	}
	dateForPayment, err := dateForPaymentHeader(r.Header)
	if err != nil {
		return err
	}
	raw, err := httpapi.PathID(r, opPayload, "id")
	if err != nil {
		return err
	}

	// S10.3-AC1: a request that does not verify is a 400, not a 401, with one generic reason.
	verified, err := p.verifier.Verify(r.Context(), string(body), jws.Expect{LocationID: raw})
	if err != nil {
		p.logger.WarnContext(r.Context(), "x9-qrcode payload request did not verify", "location", raw,
			"problem", jws.ProblemOf(err), "err", err)
		return httpapi.BusinessRule("", msgSignatureRefused)
	}
	location, ok := httpapi.ParseID(raw)
	if !ok {
		return httpapi.BusinessRule("", msgUnreadable)
	}
	q, err := p.store.GetByLocation(r.Context(), location)
	switch {
	case errors.Is(err, store.ErrUnavailable):
		return httpapi.Unavailable()
	case err != nil:
		// An unknown location is a 400 with the generic reason, as Java answers it: the owner kept
		// 400 over 404 (openapi.yaml).
		p.logger.WarnContext(r.Context(), "x9-qrcode payload refused", "location", raw, "err", err)
		return httpapi.BusinessRule("", msgUnreadable)
	}

	// S10.3-AC2: the content signed must be the content issued, byte for byte.
	if verified.QRCodeContent != q.EMVContent {
		return httpapi.BusinessRule("qrCodeContent", msgContentNotIssued)
	}
	now := p.now()
	if refusal := notServed(q.Row, now); refusal != nil {
		return refusal
	}

	at := now
	if dateForPayment != nil {
		at = billing.PricingInstant(*dateForPayment, now)
	}
	payload, err := p.payload(q, now, at)
	if err != nil {
		p.logger.ErrorContext(r.Context(), "x9-qrcode payload could not be composed", "location", raw, "err", err)
		return httpapi.BusinessRule("", msgUnreadable)
	}
	token, err := p.signer.Sign(r.Context(), p.keys.Current().Identity, jws.Message{
		Payload:       payload,
		CorrelationID: verified.Header.CorrelationID,
		StatusCode:    http.StatusOK,
	})
	if err != nil {
		return err
	}
	based := sentAt(q.Row, now)
	if dateForPayment != nil {
		based = *dateForPayment
	}
	w.Header().Set(HeaderPayloadBasedDate, based.UTC().Format(time.DateOnly))
	w.Header().Set("Content-Type", contentTypeJOSE)
	w.WriteHeader(http.StatusOK)
	_, err = w.Write([]byte(token))
	return err
}

// notServed is S10.3-AC3: a PAID or CANCELLED QR Code is refused naming which, and one past its
// validUntil (exclusive) with its expiry reason, the row being kept (ADR-0029; tolerated difference
// 2 of ADR-0028). A lapsed reservation reads ACTIVE and is served (ADR-0019).
func notServed(row domain.Row, now time.Time) *httpapi.Problem {
	id := httpapi.FormatID(row.LocationID)
	if row.Status.Closed() {
		switch domain.EffectiveStatus(row, now) {
		case domain.StatusPaid:
			return httpapi.BusinessRule("", fmt.Sprintf(msgPaid, id))
		case domain.StatusCancelled:
			return httpapi.BusinessRule("", fmt.Sprintf(msgCancelled, id))
		case domain.StatusActive, domain.StatusPaymentInitiated:
		}
		return httpapi.BusinessRule("", fmt.Sprintf(msgNotPayable, id))
	}
	if row.ValidUntil.Before(now) {
		return httpapi.BusinessRule("", fmt.Sprintf(msgExpired, id))
	}
	return nil
}

// sentAt is when the payload is sent to the payer's PSP (X9.150 §6): now on the business clock,
// never earlier than revisedAt, so that sentAt ≥ revisedAt holds even on a clock moved backwards
// (owner, 2026-11-15). validUntil ≥ sentAt holds because an expired QR Code is not served.
func sentAt(row domain.Row, now time.Time) time.Time {
	if now.Before(row.RevisedAt) {
		return row.RevisedAt
	}
	return now
}

// payload is Java's PaymentPayloadResponse, member for member and in its order (S10.3-AC4): the
// lifecycle at now, the request as stored with Java's defaults, the bill's adjustment and each
// method's amount priced at the instant at, and only the methods still valid at that same instant
// (INTERPRETATION I-14).
func (p *Payloads) payload(q store.QRCode, now, at time.Time) ([]byte, error) {
	req, err := readRequest(q.RequestJSON)
	if err != nil {
		return nil, err
	}
	javaDefaults(&req)
	rules := billingRequest(&req)
	rules.ValidUntil = q.ValidUntil
	quote, err := billing.QuoteAt(rules, at)
	if err != nil {
		return nil, err
	}

	var buf bytes.Buffer
	w := &writer{enc: jsontext.NewEncoder(&buf)}
	w.token(jsontext.BeginObject)
	w.member("id", httpapi.FormatID(q.ID))
	w.member("revision", q.Revision)
	w.member("createdAt", contract.FormatInstant(q.CreatedAt))
	w.member("revisedAt", contract.FormatInstant(q.RevisedAt))
	w.member("sentAt", contract.FormatInstant(sentAt(q.Row, now)))
	w.member("validUntil", contract.FormatInstant(q.ValidUntil))
	w.member("status", string(domain.EffectiveStatus(q.Row, now)))
	w.nonNull("creditor", req.Creditor)
	w.token(jsontext.String("bill"))
	p.bill(w, req.Bill.Value, quote.Adjustment)
	if req.Unstructured.IsSet() {
		w.member("unstructured", req.Unstructured.Value)
	}
	w.nonNull("additionalInformation", req.AdditionalInformation)
	if endpoint := p.notificationEndpoint(req.PaymentNotification); endpoint != "" {
		w.member("paymentNotification", endpoint)
	}
	if len(quote.Methods) > 0 {
		w.token(jsontext.String("paymentMethods"))
		w.token(jsontext.BeginArray)
		for _, m := range quote.Methods {
			method := req.PaymentMethods.Items[m.Index]
			w.token(jsontext.BeginObject)
			w.member("currency", method.Currency.Value)
			w.member("validUntil", contract.FormatInstant(method.ValidUntil.Value))
			w.member("amount", m.Amount)
			w.nonNull("editable", method.Editable)
			w.nonNull("networks", method.Networks)
			w.token(jsontext.EndObject)
		}
		w.token(jsontext.EndArray)
	}
	w.token(jsontext.EndObject)
	if w.err != nil {
		return nil, w.err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}

// adjustment is AdjustmentPayload.
type adjustment struct {
	Explanation string `json:"explanation"`
	Amount      int64  `json:"amount"`
	ValidUntil  string `json:"validUntil"`
}

// bill is BillPayloadResponse in Java's order: amountDue (the bill's own amount, with the
// adjustment that applies at the pricing instant), paymentTiming, description, order, invoice, tip.
func (p *Payloads) bill(w *writer, b contract.Bill, price *billing.Price) {
	w.token(jsontext.BeginObject)
	w.token(jsontext.String("amountDue"))
	w.token(jsontext.BeginObject)
	w.member("amount", b.AmountDue.Value.Amount.Value)
	w.member("currency", b.AmountDue.Value.Currency.Value)
	if price != nil {
		w.member("adjustment", []adjustment{{
			Explanation: price.Explanation, Amount: price.Adjustment, ValidUntil: contract.FormatInstant(price.ValidUntil),
		}})
	}
	w.token(jsontext.EndObject)
	w.member("paymentTiming", b.PaymentTiming.Value)
	if b.Description.IsSet() {
		w.member("description", b.Description.Value)
	}
	w.nonNull("order", b.Order)
	w.nonNull("invoice", b.Invoice)
	tip := b.Tip
	if tip.IsSet() {
		tip.Value.Presets = nonEmpty(tip.Value.Presets) // Java reads an empty list as none
	}
	w.nonNull("tip", tip)
	w.token(jsontext.EndObject)
}

// notificationEndpoint is where the payer sends its payment notification: the creditor's own
// endpoint for kind EXTERNAL, this deployment's for any other kind, none when the QR Code was
// created without paymentNotification (ADR-0006). This deployment's is written as Java writes it:
// with https:// in front when the host carries no scheme.
func (p *Payloads) notificationEndpoint(n contract.Object[contract.PaymentNotification]) string {
	if !n.IsSet() {
		return ""
	}
	if n.Value.Kind.Value == "EXTERNAL" {
		return n.Value.Endpoint.Value
	}
	url := p.host + NotificationPath
	if strings.Contains(p.host, "://") {
		return url
	}
	return "https://" + url
}

// nonNull writes the member when v is set, without its null members (Jackson's NON_NULL).
func (w *writer) nonNull(name string, v interface{ IsSet() bool }) {
	if w.err != nil || !v.IsSet() {
		return
	}
	value, err := withoutNulls(v)
	if err != nil {
		w.err = err
		return
	}
	w.member(name, value)
}

// dateForPaymentHeader reads dateForPayment as Spring binds an optional LocalDate header: absent or
// blank is none; otherwise an ISO date, or "has invalid value". It is read as that day's UTC start.
func dateForPaymentHeader(h http.Header) (*time.Time, error) {
	values := h.Values(HeaderDateForPayment)
	if len(values) == 0 || strings.TrimSpace(values[0]) == "" {
		return nil, nil
	}
	text := strings.TrimSpace(values[0])
	d, ok := contract.ParseDate(text)
	if !ok || len(text) != len(time.DateOnly) {
		return nil, httpapi.Validation(HeaderDateForPayment + " has invalid value.")
	}
	return &d, nil
}

// isJOSE accepts application/jose, with any parameters: what the route consumes.
func isJOSE(contentType string) bool {
	media, _, err := mime.ParseMediaType(contentType)
	return err == nil && media == mediaJOSE
}
