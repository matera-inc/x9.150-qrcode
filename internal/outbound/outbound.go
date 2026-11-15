// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package outbound is the payer's side of the two-phase flow (Story 10.6, ADR-0015): a PSP that
// pays somebody else's QR Code sends its notification here as JSON, and this deployment composes
// it, signs it with its own key, posts it to the payee and reports the payee's answer.
//
// Before anything is sent, a notification whose phase contradicts its content is refused: a
// pre-payment carrying a transactionId would arrive as a payment already made (ADR-0004, ADR-0036),
// and a post-payment reporting a payment (anything but NOT_SENT) without one gives the payee
// nothing to reconcile. There is no rail-specific refusal: pre-commit exists on every rail.
//
// Delivery follows no redirect (the endpoint is trusted because it came inside a signed payload;
// a redirect leads somewhere that signature never attested), and gives the payee 15 seconds for
// its whole answer, headers and body. No answer is a 502 notification-undeliverable: a delivery to
// retry, which the payee may nevertheless have received.
//
// The payee's answer is reported, never acted on: its status and body verbatim, accepted when the
// status is 2xx, and what its signature is worth (jws.Verifier.CheckAnswer, Story 2.3). An
// unsigned yes is still a yes; whether to act on it is the payer's call. Nothing in this
// deployment's own state changes.
package outbound

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
)

// The routes, as openapi.yaml names them.
const (
	PrePaymentPath  = "/api/v1/payment-notification/pre-payment"
	PostPaymentPath = "/api/v1/payment-notification/post-payment"
)

// ContentTypeJOSE is the media type of the notification posted to the payee.
const ContentTypeJOSE = "application/jose"

// The delivery's bounds (ADR-0015; Java's notification RestClient).
const (
	// DefaultConnectTimeout bounds opening the connection and the TLS handshake.
	DefaultConnectTimeout = 5 * time.Second
	// DefaultAnswerTimeout bounds the whole answer, headers and body, from the moment the
	// notification is sent.
	// The clock starts once the request has been sent, as in the Java build (#120), so the time
	// spent connecting is not taken from the payee's.
	DefaultAnswerTimeout = 15 * time.Second
	// DefaultMaxAnswerBytes is the largest answer read. A signed {"statusCode": n}, or a refusal
	// with its violations, is a few kilobytes; one past this is no answer this service can report.
	DefaultMaxAnswerBytes = 1 << 20
)

// The refusals made before anything is sent, word for word as the Java build makes them.
const (
	violationPreWithTransaction = "A pre-payment notification must not carry a transactionId: the payee infers the phase " +
		"from its absence, so one here would be read as a payment already made."
	violationPostWithoutTransaction = "A post-payment notification must carry the transactionId — the on-chain hash, or the " +
		"End-to-End ID for an ISO 20022 rail. Reporting a payment without its reference gives the payee nothing to " +
		"reconcile against."
	violationEndpoint = "The notification endpoint must be an absolute URL with a host: "
	// The Java build signs a nil correlationId; this one cannot tie an answer to it, so it refuses
	// it, as Story 10.2's generate does.
	violationNilCorrelationID = "The correlationId must not be the nil UUID: the payee's answer is matched to it."
)

// phase is which half of the payment the caller says it is announcing. The wire format has no
// phase marker (ADR-0004): the payee infers it from the transactionId, which is why the two must
// agree before anything is sent.
type phase int

const (
	prePayment phase = iota
	postPayment
)

// Keys is where the signing key is read from, on every notification: *keys.Store.
type Keys interface {
	Current() *keys.Keys
}

// AnswerChecker checks a payee's answer: *jws.Verifier.
type AnswerChecker interface {
	CheckAnswer(ctx context.Context, httpStatus int, body string, sent uuid.UUID) jws.AnswerCheck
}

// Service answers the two payer-side routes.
type Service struct {
	keys    Keys
	signer  *jws.Signer
	checker AnswerChecker
	logger  *slog.Logger
	client  *Client
}

// New returns the service. signer and checker are the deployment's one signer and one verifier
// (S10.2-AC1).
func New(k Keys, signer *jws.Signer, checker AnswerChecker, logger *slog.Logger, cfg Config) *Service {
	return &Service{keys: k, signer: signer, checker: checker, logger: logger, client: NewClient(cfg)}
}

// Register adds the routes to api.
func (s *Service) Register(api *httpapi.API) {
	api.Handle("POST "+PrePaymentPath, func(w http.ResponseWriter, r *http.Request) error {
		return s.send(w, r, prePayment)
	})
	api.Handle("POST "+PostPaymentPath, func(w http.ResponseWriter, r *http.Request) error {
		return s.send(w, r, postPayment)
	})
}

// CloseIdleConnections closes the connections kept to payees; main calls it once the server has
// stopped.
func (s *Service) CloseIdleConnections() { s.client.CloseIdleConnections() }

// Result is OutboundPaymentNotificationResult: what the payee answered, and what its signature is
// worth.
type Result struct {
	// StatusCode is the status the payee signed when Signature is VERIFIED; otherwise the HTTP
	// status.
	StatusCode int  `json:"statusCode"`
	Accepted   bool `json:"accepted"`
	// Body is the payee's answer verbatim; absent when it had none.
	Body             *string `json:"body,omitzero"`
	Signature        string  `json:"signature"`
	SignatureProblem *string `json:"signatureProblem,omitzero"`
	// SignatureDetail is the problem in words. The caller is the payer this service works for, so
	// the detail is theirs to read (Story 2.3).
	SignatureDetail *string `json:"signatureDetail,omitzero"`
}

// send is POST /api/v1/payment-notification/pre-payment and post-payment (S10.6-AC1 to AC3), in
// Java's order: the body and its contract, the endpoint, the phase against the transactionId; then
// compose, sign, deliver, and report.
func (s *Service) send(w http.ResponseWriter, r *http.Request, p phase) error {
	var req contract.OutboundNotification
	if err := httpapi.DecodeJSON(r, &req); err != nil {
		return err
	}
	if violations := req.Validate(); violations != nil {
		return httpapi.Validation(violations...)
	}
	endpoint, err := checkEndpoint(req.Endpoint.Value)
	if err != nil {
		return err
	}
	note := req.Notification.Value
	if err := checkPhase(p, note); err != nil {
		return err
	}
	correlationID := req.CorrelationID.Value
	if !req.CorrelationID.IsSet() {
		correlationID = jws.NewCorrelationID()
	} else if correlationID == uuid.Nil {
		return httpapi.BusinessRule("correlationId", violationNilCorrelationID)
	}

	payload, err := httpapi.Marshal(wireOf(note))
	if err != nil {
		return fmt.Errorf("composing the notification: %w", err)
	}
	token, err := s.signer.Sign(s.keys.Current().Identity, jws.Message{Payload: payload, CorrelationID: correlationID})
	if err != nil {
		return fmt.Errorf("signing the notification: %w", err)
	}

	s.logger.InfoContext(r.Context(), "x9-qrcode sending a payment notification", "host", endpoint.Host,
		"correlationId", correlationID.String())
	status, body, err := s.client.Post(r.Context(), endpoint, http.Header{"Content-Type": {ContentTypeJOSE}}, token)
	if err != nil {
		s.logger.WarnContext(r.Context(), "x9-qrcode payment notification undeliverable", "host", endpoint.Host,
			"correlationId", correlationID.String(), "err", err)
		return httpapi.NotificationUndeliverable()
	}

	check := s.checker.CheckAnswer(r.Context(), status, body, correlationID)
	if check.Signature != jws.AnswerVerified {
		s.logger.WarnContext(r.Context(), "x9-qrcode payee answer not verified", "signature", string(check.Signature),
			"problem", string(check.Problem), "detail", check.Detail, "httpStatus", status)
	}
	return httpapi.WriteJSON(w, http.StatusOK, resultOf(check, body))
}

// checkEndpoint is the Java build's check: an absolute URL with a host. A relative or schemeless
// value is the caller's mistake, refused before anything is sent, so it never reads as somebody
// else's outage.
func checkEndpoint(raw string) (*url.URL, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme == "" || u.Hostname() == "" {
		return nil, httpapi.BusinessRule("endpoint", violationEndpoint+raw)
	}
	return u, nil
}

// checkPhase refuses a phase that contradicts the transactionId (S10.6-AC1, ADR-0015). It is keyed
// on the action, not the phase alone: NOT_SENT reports that no payment happened, and by its nature
// has no transactionId.
func checkPhase(p phase, note contract.NotificationData) error {
	hasTransaction := strings.TrimSpace(note.Payment.Value.TransactionID.Value) != ""
	switch p {
	case prePayment:
		if hasTransaction {
			return httpapi.BusinessRule("payment.transactionId", violationPreWithTransaction)
		}
	case postPayment:
		givesBack := note.Blockchain.IsSet() && note.Blockchain.Value.Action.Value == string(domain.ActionNotSent)
		if !hasTransaction && !givesBack {
			return httpapi.BusinessRule("payment.transactionId", violationPostWithoutTransaction)
		}
	}
	return nil
}

// resultOf is the report: the checked status, accepted when it is 2xx, the body verbatim (absent
// when there was none, as Java's RestClient gives null for an empty body), and the verdict.
func resultOf(check jws.AnswerCheck, body string) Result {
	res := Result{
		StatusCode: check.StatusCode,
		Accepted:   check.StatusCode >= 200 && check.StatusCode < 300,
		Signature:  string(check.Signature),
	}
	if body != "" {
		res.Body = &body
	}
	if check.Signature != jws.AnswerVerified {
		problem, detail := string(check.Problem), check.Detail
		res.SignatureProblem, res.SignatureDetail = &problem, &detail
	}
	return res
}
