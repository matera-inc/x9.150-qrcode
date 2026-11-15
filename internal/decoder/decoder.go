// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package decoder is POST /api/v1/qrcode-emv-decoder (Story 10.7): a payer's app sends the string it
// scanned, and gets the payee's verified payload, or a refusal that tells "do not pay" from "try
// again".
//
// In order, as the Java build does it:
//
//  1. the scanned string is read by internal/emv, which refuses malformed EMV before anything is
//     fetched (ADR-0027): a 400 business-rule-violation, "qrCode: The QR Code is malformed: …"; a
//     well-formed string with no org.x9 template carrying a URL is refused saying so;
//  2. a payload request ({"qrCodeContent": base64 of the string}) is signed with this deployment's
//     key and posted to the template's URL (target.go): read internally when the URL's host, port
//     included, is this deployment's public host, ignoring case; fetched over https, without user
//     information, from any other host;
//  3. the payee's answer: a 4xx is relayed as payload-refused with the payee's own status and its
//     reasons, capped and reduced to plain strings (relay.go); a 5xx, or no whole answer, is a 502
//     payload-unreachable carrying no address;
//  4. a payload's JWS is verified by the deployment's one verifier (S10.2-AC1), and must carry in
//     its header exactly the correlationId the request was sent under, the caller's or the one
//     generated for it (ADR-0027, amendment of 2026-11-15); one that does not verify, or carries
//     no correlationId or another one, is refused, and nothing of it is presented;
//  5. the payload is read with tolerance (foreign.go, INTERPRETATION I-16): an unknown rail is
//     carried intact, an unknown field is dropped, a currency this deployment does not settle is
//     presented as written; it is never refused for what it adds.
//
// The payload is somebody else's: it is never read with internal/contract's create and PATCH
// types, which refuse an unknown member.
package decoder

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"time"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/emv"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/outbound"
)

// Path is the route, as openapi.yaml names it.
const Path = "/api/v1/qrcode-emv-decoder"

// The payload request's media type, and the header that carries the date the payer intends to pay.
const (
	contentTypeJOSE      = "application/jose"
	headerDateForPayment = "dateForPayment"
)

// The refusals the decoder gives itself, word for word as the Java build gives them.
const (
	fieldQRCode         = "qrCode"
	msgMalformed        = "The QR Code is malformed: %s."
	msgNoPayloadURL     = "EMV QR Code does not contain a valid payload URL."
	msgSignatureInvalid = "Payload JWS signature is invalid."
	// msgUnreadable answers a payload that verified and is not a JSON object: there is nothing in it
	// to present. The Java build answers it with a 500.
	msgUnreadable = "The payee's payload is not a JSON object, so this QR Code could not be decoded."
	// msgNilCorrelationID refuses the nil UUID, as the outbound notification does (Story 10.6): the
	// payee's payload is matched to the correlationID the caller gave.
	msgNilCorrelationID = "The correlationID must not be the nil UUID: the payee's payload is matched to it."
)

// Keys is where the signing key is read from, on every request: *keys.Store.
type Keys interface {
	Current() *keys.Keys
}

// Verifier verifies the payee's payload: *jws.Verifier.
type Verifier interface {
	Verify(ctx context.Context, token string, want jws.Expect) (*jws.Verified, error)
}

// Poster posts the payload request and reads the payee's whole answer: *outbound.Client.
type Poster interface {
	Post(ctx context.Context, endpoint *url.URL, header http.Header, body string) (int, string, error)
}

// Config is where the decoder fetches from.
type Config struct {
	// OwnHost is this deployment's public host, with its port if it has one
	// (x9.public-endpoints.host).
	OwnHost string
	// OwnBaseURL is where a URL on OwnHost is read from instead: http://localhost:<port>, as the Java
	// build reads its own payloads. Required.
	OwnBaseURL string
}

// Service answers the decoder's route.
type Service struct {
	keys     Keys
	signer   *jws.Signer
	verifier Verifier
	poster   Poster
	targets  *targets
	logger   *slog.Logger
}

// New returns the service. signer and verifier are the deployment's one signer and one verifier
// (S10.2-AC1); poster is the outbound client of Story 10.6, with its timeouts and answer cap.
func New(k Keys, signer *jws.Signer, verifier Verifier, poster Poster, cfg Config, logger *slog.Logger) (*Service, error) {
	t, err := newTargets(cfg.OwnHost, cfg.OwnBaseURL)
	if err != nil {
		return nil, err
	}
	return &Service{keys: k, signer: signer, verifier: verifier, poster: poster, targets: t, logger: logger}, nil
}

// Register adds the route to api.
func (s *Service) Register(api *httpapi.API) {
	api.Handle("POST "+Path, s.decode)
}

// payloadRequest is PaymentPayloadRequest: the scanned string, base64.
type payloadRequest struct {
	QRCodeContent string `json:"qrCodeContent"`
}

// decode is POST /api/v1/qrcode-emv-decoder (S10.7-AC1 to AC5).
func (s *Service) decode(w http.ResponseWriter, r *http.Request) error {
	var req contract.EmvDecoder
	if err := httpapi.DecodeJSON(r, &req); err != nil {
		return err
	}
	if violations := req.Validate(); violations != nil {
		return httpapi.Validation(violations...)
	}
	scanned := req.QRCode.Value

	// S10.7-AC1: malformed EMV is refused before anything is fetched.
	qr, err := emv.Decode(scanned)
	switch {
	case errors.Is(err, emv.ErrNoX9):
		return httpapi.BusinessRule("", msgNoPayloadURL)
	case err != nil:
		return httpapi.BusinessRule(fieldQRCode, fmt.Sprintf(msgMalformed, emv.Reason(err)))
	}

	// The payee's answer must echo the correlationId the request went out under, the caller's or a
	// fresh one: under none, or under another, it is not an answer to this request (ADR-0027,
	// amendment of 2026-11-15).
	correlationID := req.CorrelationID.Value
	if !req.CorrelationID.IsSet() {
		correlationID = jws.NewCorrelationID()
	} else if correlationID == uuid.Nil {
		return httpapi.BusinessRule("correlationID", msgNilCorrelationID)
	}
	expect := jws.Expect{CorrelationID: correlationID}

	// S10.7-AC2: this deployment's own host is read internally; any other over https.
	target, refused := s.targets.resolve(qr.URL)
	if target == nil {
		return httpapi.Validation(refused)
	}
	payload, err := httpapi.Marshal(payloadRequest{QRCodeContent: base64.StdEncoding.EncodeToString([]byte(scanned))})
	if err != nil {
		return fmt.Errorf("composing the payload request: %w", err)
	}
	token, err := s.signer.Sign(s.keys.Current().Identity, jws.Message{Payload: payload, CorrelationID: correlationID})
	if err != nil {
		return fmt.Errorf("signing the payload request: %w", err)
	}
	header := http.Header{"Content-Type": {contentTypeJOSE}}
	if req.DateForPayment.IsSet() {
		header.Set(headerDateForPayment, req.DateForPayment.Value.Format(time.DateOnly))
	}

	s.logger.InfoContext(r.Context(), "x9-qrcode fetching a payload", "host", target.Host,
		"correlationId", correlationID.String())
	status, body, err := s.poster.Post(r.Context(), target, header, token)
	// S10.7-AC3: no answer, or a 5xx, is a payee that could not be reached; the address stays in
	// the log, since it may have been resolved against this deployment's internals.
	switch {
	case err != nil:
		s.logger.WarnContext(r.Context(), "x9-qrcode payload unreachable", "url", target.Redacted(), "err", err)
		return httpapi.PayloadUnreachable()
	case status >= 500:
		s.logger.WarnContext(r.Context(), "x9-qrcode payload unreachable", "url", target.Redacted(),
			"httpStatus", status)
		return httpapi.PayloadUnreachable()
	case status >= 400:
		violations := relayed(body)
		s.logger.InfoContext(r.Context(), "x9-qrcode payload refused by the payee", "url", target.Redacted(),
			"httpStatus", status, "violations", violations)
		return httpapi.PayloadRefused(status, violations)
	}

	// S10.7-AC2: the payload's JWS is verified before anything in it is read.
	verified, err := s.verifier.Verify(r.Context(), body, expect)
	if err != nil {
		s.logger.WarnContext(r.Context(), "x9-qrcode payload did not verify", "host", target.Host,
			"httpStatus", status, "problem", jws.ProblemOf(err), "err", err)
		return httpapi.BusinessRule("", msgSignatureInvalid)
	}

	// S10.7-AC5: read with tolerance.
	answer, err := readForeign(verified.Payload)
	if err != nil {
		s.logger.WarnContext(r.Context(), "x9-qrcode payload unreadable", "host", target.Host, "err", err)
		return httpapi.BusinessRule("", msgUnreadable)
	}
	w.Header().Set("Content-Type", httpapi.ContentTypeJSON)
	w.WriteHeader(http.StatusOK)
	_, err = w.Write(answer)
	return err
}

// The outbound client is the one Poster the service is built with in main.
var _ Poster = (*outbound.Client)(nil)
