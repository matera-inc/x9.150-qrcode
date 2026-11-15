// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package notification is the payee's side of a payer's payment notification (Story 10.4):
// POST /pub/api/v1/payment-notification. It verifies the JWS, reads it, refuses by name what this
// QR Code cannot take, and hands the rest to the transition map; and every answer it gives, a yes
// or a no, is a compact JWS signed with this payee's key (INTERPRETATION I-12, ADR-0027).
//
// The order, for every request:
//
//  1. the method, the media type (application/jose) and a body: refused before anything is
//     read, under a fresh correlationId (a signed 405, 415 or 400);
//  2. the signature (internal/jws): a 401 with the fixed signatureProblem code and no words, under
//     a fresh correlationId, since nothing in an unverified header is worth echoing;
//  3. from here on the answer echoes the notification's correlationId. The payload: a body that is
//     not a PaymentNotificationData is a 400, and the contract's constraints a 400 naming each;
//  4. in one transaction (the store, ADR-0025): the QR Code (404), the refusals by name
//     (RefuseByName), the acceptance gate of a pre-commit (Gate), and domain.Notify, which decides
//     by ADR-0035's map and ADR-0036's matrix and stores the notification with its events.
//
// Nothing below the signature check reads the payload, and nothing before step 4 reads a QR Code.
package notification

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"strings"
	"time"
	"unicode/utf16"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/paymentrequest"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

// Path is the route, as openapi.yaml names it (x9.public-endpoints.payment-notification-path).
const Path = "/pub/api/v1/payment-notification"

// ContentTypeJOSE is the media type of the question and of every answer. The Java build writes it
// without a charset on this endpoint.
const ContentTypeJOSE = "application/jose"

// msgUnreadable is the Java build's refusal of a verified payload that is not a notification.
const msgUnreadable = "Payment notification payload could not be read."

// Keys is where the signing key is read from, on every answer: *keys.Store.
type Keys interface {
	Current() *keys.Keys
}

// Verifier verifies the notification: *jws.Verifier.
type Verifier interface {
	Verify(ctx context.Context, token string, want jws.Expect) (*jws.Verified, error)
}

// Store runs the transition: *store.Store.
type Store interface {
	Transition(ctx context.Context, id uuid.UUID, evidence *store.Evidence,
		decide func(row domain.Row, last *domain.NotificationRecord, now time.Time) (domain.Decision, error),
	) (store.Result, error)
}

// Service answers the notification route.
type Service struct {
	store    Store
	keys     Keys
	signer   *jws.Signer
	verifier Verifier
	settings domain.Settings
	logger   *slog.Logger
}

// New returns the service. signer and verifier are the deployment's one signer and one verifier
// (S10.2-AC1); settings carry x9.reservation.ttl-seconds.
func New(st Store, k Keys, signer *jws.Signer, verifier Verifier, settings domain.Settings, logger *slog.Logger) *Service {
	return &Service{store: st, keys: k, signer: signer, verifier: verifier, settings: settings, logger: logger}
}

// Register adds the route to api. It takes every method, so that a wrong one is answered signed too.
func (s *Service) Register(api *httpapi.API) {
	api.Handle(Path, s.serve)
}

// Accepted is a notification that verified and reads as one: what the transition decides on.
type Accepted struct {
	QRCodeID uuid.UUID
	// Data is the payload as read: what qr_code_notification.notification_json keeps.
	Data contract.NotificationData
	// Notification is what the domain reads of it.
	Notification domain.Notification
}

// exchange is one request: the correlationId the answer goes out under, and what the logs name.
type exchange struct {
	correlationID uuid.UUID
	accepted      *Accepted
}

// serve answers one request, always signed. A panic is answered as a signed 500 that says nothing
// of its cause, under the notification's correlationId once it verified.
func (s *Service) serve(w http.ResponseWriter, r *http.Request) error {
	if r.Method == http.MethodOptions {
		// Spring answers OPTIONS itself, with no body to sign.
		w.Header().Set("Allow", http.MethodPost+","+http.MethodOptions)
		w.WriteHeader(http.StatusOK)
		return nil
	}
	x := &exchange{correlationID: jws.NewCorrelationID()}
	defer func() {
		v := recover()
		if v == nil {
			return
		}
		if v == http.ErrAbortHandler { //nolint:errorlint // the sentinel net/http panics with
			panic(v)
		}
		s.logger.ErrorContext(r.Context(), "x9-qrcode payment notification panicked", "panic", v)
		s.answer(w, r, x, httpapi.InternalError())
	}()
	s.answer(w, r, x, s.handle(r, x))
	return nil
}

// handle decides the request: nil for a yes, otherwise the refusal.
func (s *Service) handle(r *http.Request, x *exchange) *httpapi.Problem {
	if p := checkRequest(r); p != nil {
		return p
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, httpapi.MaxBodyBytes+1))
	if err != nil || len(body) == 0 || len(body) > httpapi.MaxBodyBytes {
		return httpapi.MalformedJSON() // Spring's "Required request body is missing" or unreadable
	}

	verified, err := s.verifier.Verify(r.Context(), string(body), jws.Expect{})
	if err != nil {
		problem := jws.ProblemOf(err)
		s.logger.WarnContext(r.Context(), "x9-qrcode payment notification did not verify",
			"signatureProblem", problem, "err", err)
		return httpapi.InvalidSignature(string(problem))
	}
	x.correlationID = verified.Header.CorrelationID

	accepted, p := read(verified)
	if p != nil {
		s.refused(r.Context(), verified, nil, p)
		return p
	}
	x.accepted = accepted

	p = s.decide(r.Context(), verified, accepted, body)
	if p != nil {
		s.refused(r.Context(), verified, accepted, p)
	}
	return p
}

// checkRequest is what Spring refuses before the Java build's controller runs: a method other than
// POST, and a body that is not application/jose (a missing Content-Type is application/octet-stream).
func checkRequest(r *http.Request) *httpapi.Problem {
	if r.Method != http.MethodPost {
		return &httpapi.Problem{Type: "about:blank", Title: "Method Not Allowed", Status: http.StatusMethodNotAllowed,
			Detail: fmt.Sprintf("Method '%s' is not supported.", r.Method)}
	}
	contentType := r.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	if media, _, err := mime.ParseMediaType(contentType); err != nil || !strings.EqualFold(media, ContentTypeJOSE) {
		return &httpapi.Problem{Type: "about:blank", Title: "Unsupported Media Type", Status: http.StatusUnsupportedMediaType,
			Detail: fmt.Sprintf("Content-Type '%s' is not supported.", contentType)}
	}
	return nil
}

// read is step 3: the verified payload as a PaymentNotificationData, its constraints checked. The
// inbound notification is another bank's message, read tolerantly: a key it does not declare is
// not refused.
func read(v *jws.Verified) (*Accepted, *httpapi.Problem) {
	var data contract.NotificationData
	if err := httpapi.Unmarshal(v.Payload, &data); err != nil {
		return nil, httpapi.BusinessRule("", msgUnreadable)
	}
	if v := javaValueRules(&data); v != "" {
		return nil, httpapi.Validation(v)
	}
	if violations := data.Validate(); violations != nil {
		return nil, httpapi.Validation(violations...)
	}
	id, ok := httpapi.ParseID(data.Payment.Value.QRCodeID.Value)
	if !ok {
		return nil, httpapi.InvalidQRCodeID()
	}
	return &Accepted{QRCodeID: id, Data: data, Notification: toDomain(&data, v.Signer)}, nil
}

// The Java build's words for a notification's values, which the black-box suite and its payers
// read (ADR-0005 for the 18 digits; ANSI X9.150-2026 Table 4, row 3.1 for payer.info).
const (
	maxAmount         = 999_999_999_999_999_999
	maxPayerInfo      = 254
	msgNegative       = "monetary values should not be negative."
	msgTooManyDigits  = "monetary values should not exceed 18 digits (999999999999999999)."
	msgACHExpected    = "PaymentNotification ExpectedDate must not be null when network is ACH"
	msgPayerInfoLimit = "payer.info must be at most %d characters (ANSI X9.150-2026 Table 4, row 3.1), but %d were given."
)

// javaValueRules are the checks the Java build makes as it builds the notification's values, in
// its order, ahead of the contract's own: each amount not negative and at most 18 digits (signed
// bodies included, ADR-0005), payer.info at most 254 characters, and an expectedDate on ACH. ""
// when they hold.
func javaValueRules(data *contract.NotificationData) string {
	pay := data.Payment.Value
	for _, a := range []contract.Long{pay.Amount, pay.TipAmount} {
		switch {
		case !a.IsSet():
		case a.Value < 0:
			return msgNegative
		case a.Value > maxAmount:
			return msgTooManyDigits
		}
	}
	if data.Payer.IsSet() {
		if n := len(utf16.Encode([]rune(data.Payer.Value.Info.Value))); n > maxPayerInfo { // Java counts UTF-16 units
			return fmt.Sprintf(msgPayerInfoLimit, maxPayerInfo, n)
		}
	}
	if strings.EqualFold(pay.Network.Value, railACH) && !data.ExpectedDate.IsSet() {
		return msgACHExpected
	}
	return ""
}

// toDomain is what the transitions read of a notification.
func toDomain(data *contract.NotificationData, signer string) domain.Notification {
	pay := data.Payment.Value
	n := domain.Notification{
		Network:       pay.Network.Value,
		Currency:      pay.Currency.Value,
		Amount:        pay.Amount.Value,
		TransactionID: pay.TransactionID.Value,
		SignerSubject: signer,
	}
	if pay.TipAmount.IsSet() {
		tip := pay.TipAmount.Value
		n.TipAmount = &tip
	}
	if data.Payer.IsSet() && data.Payer.Value.Info.IsSet() {
		info := data.Payer.Value.Info.Value
		n.PayerInfo = &info
	}
	if data.Blockchain.IsSet() && data.Blockchain.Value.Action.IsSet() {
		n.Action = domain.Action(data.Blockchain.Value.Action.Value)
	}
	return n
}

// decide is step 4, in one transaction: the evidence stored is the payload as read and the compact
// JWS as received.
func (s *Service) decide(ctx context.Context, v *jws.Verified, a *Accepted, token []byte) *httpapi.Problem {
	notificationJSON, err := httpapi.Marshal(a.Data)
	if err != nil {
		return s.failed(ctx, err)
	}
	evidence := &store.Evidence{JSON: notificationJSON, JWSCompact: strings.TrimSpace(string(token))}
	res, err := s.store.Transition(ctx, a.QRCodeID, evidence,
		func(row domain.Row, last *domain.NotificationRecord, now time.Time) (domain.Decision, error) {
			return Decide(row, a, last, now, s.settings)
		})
	if errors.Is(err, store.ErrNotFound) {
		return httpapi.NotFound("Could not find QR code with id: " + httpapi.FormatID(a.QRCodeID))
	}
	if err != nil {
		if p := problemOf(err); p != nil {
			return p
		}
		return s.failed(ctx, err)
	}
	if n := res.Decision.Notification; n != nil && len(n.Receipt.Deviations) > 0 {
		// A post-commit on a rail or in a currency the QR Code does not offer is recorded too, and
		// then refused: the warning names it either way.
		s.logger.WarnContext(ctx, "x9-qrcode payment notification recorded from a payer that broke the protocol",
			"qrCodeId", httpapi.FormatID(a.QRCodeID), "payerInfo", payerInfo(a), "signerSubject", v.Signer,
			"receivedInStatus", n.Receipt.ReceivedIn, "deviations", domain.FormatDeviations(n.Receipt.Deviations))
	}
	if r := res.Decision.Refusal; r != nil {
		return refusalProblem(r)
	}
	return nil
}

// Decide is how a verified notification changes its QR Code, given the row as read in the
// transaction, the stored notification an event body is built from, and the payee's clock: the
// refusals by name, then for a pre-commit the acceptance gate, then domain.Notify, which applies
// ADR-0035's map and ADR-0036's matrix. A refusal by name changes nothing; the gate's refusal is
// the domain's to apply, since a holder's refused retry releases the QR Code.
//
// A rail or a currency the QR Code does not offer, each rail on its own (ADR-0036, amended
// 2026-11-15, as the Java build does it), is a 400 naming it: a NOT_SENT naming one is refused
// here, before its status is read, since nothing moved; a pre-commit's is the acceptance gate's
// (Gate), once the status admits it, so another payer's hold is still a 409 first; a post-commit
// naming one goes on to domain.Notify, which records it and refuses it.
func Decide(row domain.Row, a *Accepted, last *domain.NotificationRecord, now time.Time, s domain.Settings) (domain.Decision, error) {
	req, err := paymentrequest.StoredRequest(row.RequestJSON)
	if err != nil {
		return domain.Decision{}, err
	}
	refuse := func(r *domain.Refusal) domain.Decision {
		// The refusal is the answer, carried in the decision, not a failure of the transition.
		return domain.Decision{Row: row, Status: domain.EffectiveStatus(row, now), Refusal: r}
	}
	rules := paymentrequest.BillingRequest(&req)
	if r := RefuseByName(req, &a.Data, now); r != nil {
		return refuse(r), nil
	}
	phase := a.Notification.Phase()
	if phase == domain.PhaseRelease {
		offer, err := domain.OfferOf(row, a.Notification)
		if err != nil {
			return domain.Decision{}, err
		}
		if r := offer.Refusal(a.Notification); r != nil {
			return refuse(r), nil
		}
	}
	var gate *domain.Refusal
	if phase == domain.PhasePreCommit {
		if gate, err = Gate(row, rules, &a.Data, now); err != nil {
			return domain.Decision{}, err
		}
	}
	return domain.Notify(row, a.Notification, gate, last, now, s)
}

// refusalProblem is a domain refusal as the Java build answers it.
func refusalProblem(r *domain.Refusal) *httpapi.Problem {
	if r.Kind == domain.KindConflict {
		return httpapi.StatusConflict(string(r.CurrentStatus), r.Message)
	}
	return httpapi.BusinessRule(r.Field, r.Message)
}

// problemOf is the answer to a failure that is an answer: a refusal (the busy 409 among them), an
// amount past 18 digits, a statement that ran out of time. nil for anything else, which is a 500.
func problemOf(err error) *httpapi.Problem {
	var (
		refusal   *domain.Refusal
		violation *billing.Violation
		problem   *httpapi.Problem
	)
	switch {
	case errors.As(err, &refusal):
		return refusalProblem(refusal)
	case errors.As(err, &violation):
		if violation.Kind == billing.Invalid {
			return httpapi.Validation(violation.Error())
		}
		return httpapi.BusinessRule(violation.Field, violation.Message)
	case errors.Is(err, store.ErrUnavailable):
		return httpapi.Unavailable()
	case errors.As(err, &problem):
		return problem
	}
	return nil
}

func (s *Service) failed(ctx context.Context, err error) *httpapi.Problem {
	s.logger.ErrorContext(ctx, "x9-qrcode payment notification failed", "err", err)
	return httpapi.InternalError()
}

// refused logs a refusal of a verified notification with who sent it and why, so that a payee can
// count refusals per payer (S10.4-AC4).
func (s *Service) refused(ctx context.Context, v *jws.Verified, a *Accepted, p *httpapi.Problem) {
	attrs := []any{"status", p.Status, "signerSubject", v.Signer, "correlationId", v.Header.CorrelationID.String(),
		"reason", p.Error()}
	if a != nil {
		attrs = append(attrs, "qrCodeId", httpapi.FormatID(a.QRCodeID), "payerInfo", payerInfo(a))
		if p.CurrentStatus != "" {
			attrs = append(attrs, "currentStatus", p.CurrentStatus)
		}
	}
	s.logger.WarnContext(ctx, "x9-qrcode payment notification refused", attrs...)
}

func payerInfo(a *Accepted) string {
	if a.Notification.PayerInfo == nil {
		return ""
	}
	return *a.Notification.PayerInfo
}

// answer writes the signed answer: {"statusCode":200} for a yes, the problem with statusCode added
// for a no, under x's correlationId, signed now, after the decision (the iat is the payee's clock
// at the decision). Only a failure to sign leaves an answer unsigned: a 500 internal-error, since
// what was decided cannot be vouched for.
func (s *Service) answer(w http.ResponseWriter, r *http.Request, x *exchange, p *httpapi.Problem) {
	status, body := http.StatusOK, jws.AnswerBody(http.StatusOK)
	if p != nil {
		status, body = p.Status, httpapi.AnswerBody(p)
	}
	token, err := s.signer.Sign(s.keys.Current().Identity, jws.Message{
		Payload: body, CorrelationID: x.correlationID, StatusCode: status,
	})
	if err != nil {
		s.logger.ErrorContext(r.Context(), "x9-qrcode could not sign the answer to a payment notification",
			"status", status, "err", err)
		httpapi.WriteProblem(w, r, httpapi.InternalError())
		return
	}
	if p != nil && p.Status == http.StatusMethodNotAllowed {
		w.Header().Set("Allow", http.MethodPost)
	}
	w.Header().Set("Content-Type", ContentTypeJOSE)
	w.WriteHeader(status)
	_, _ = w.Write([]byte(token))
}
