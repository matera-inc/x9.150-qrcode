// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package signature puts this deployment's signatures on the wire (Story 10.2): it publishes the
// signing key, as a JWK set and as a PEM certificate, at the addresses every jku and x5u this
// service signs points at, and it serves the two utility endpoints that sign any JSON and verify any
// JWS. Each answer is the Java build's (ADR-0028).
//
// The key is read from the key store on every request, so a reloaded keystore is published, and
// signs, without a restart. Signing and verifying are internal/jws's: this package only reads the
// requests and writes the answers.
package signature

import (
	"context"
	"encoding/json/jsontext"
	"encoding/pem"
	"log/slog"
	"net/http"
	"path"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
)

// The routes, as openapi.yaml names them.
const (
	GeneratePath = "/api/v1/signature/generate"
	ValidatePath = "/api/v1/signature/validate"
	// CertificatePrefix is where the certificate is served; its one file name is CertificateFileName.
	CertificatePrefix = "/pub/.well-known/certificate/"
)

// CertificateFileName is the one file name the certificate is served under: the one every x5u this
// service signs names (jws.CertificatePath).
var CertificateFileName = path.Base(jws.CertificatePath)

// The headers of the generate and validate endpoints.
const (
	HeaderCorrelationID = "Correlation-Id"
	HeaderTTLSeconds    = "TTL-Seconds"
)

// ContentTypeJOSE is what the Java build writes on a compact JWS answer.
const ContentTypeJOSE = "application/jose;charset=UTF-8"

// ContentTypePEM is the certificate's media type (openapi.yaml).
const ContentTypePEM = "application/x-pem-file"

// The Java build's refusals of the two utility endpoints, word for word.
const (
	violationTTL       = "Should inform a valid TTL time in seconds for generation !!"
	violationGenCID    = "Should inform the JWS Token for generation !!"
	violationToken     = "Should inform the JWS Token for validation !!"
	violationSignature = "Signature validation failed for content !!!"
)

// Keys is where the signing key and its chain are read from, on every request: *keys.Store.
type Keys interface {
	Current() *keys.Keys
}

// Verifier verifies a JWS: *jws.Verifier.
type Verifier interface {
	Verify(ctx context.Context, token string, want jws.Expect) (*jws.Verified, error)
}

// Service answers the signature routes.
type Service struct {
	keys     Keys
	signer   *jws.Signer
	verifier Verifier
	logger   *slog.Logger
}

// New returns the service. signer and verifier are the deployment's one signer and one verifier
// (S10.2-AC1).
func New(k Keys, signer *jws.Signer, verifier Verifier, logger *slog.Logger) *Service {
	return &Service{keys: k, signer: signer, verifier: verifier, logger: logger}
}

// Register adds the routes to api.
func (s *Service) Register(api *httpapi.API) {
	api.Handle("GET "+jws.JWKSetPath, s.jwkSet)
	api.Handle("GET "+CertificatePrefix+"{pemFileName}", s.certificate)
	api.Handle("POST "+GeneratePath, s.generate)
	api.Handle("POST "+ValidatePath, s.validate)
}

// jwkSet is GET /pub/.well-known/jwks (S10.2-AC2): the signing key, public, with its chain.
func (s *Service) jwkSet(w http.ResponseWriter, _ *http.Request) error {
	set, err := JWKSet(s.keys.Current().Identity, s.signer.Algorithm())
	if err != nil {
		return err
	}
	return httpapi.WriteJSON(w, http.StatusOK, set)
}

// certificate is GET /pub/.well-known/certificate/{pemFileName} (S10.2-AC3): the signing chain in
// PEM, under the one name x5u gives it.
func (s *Service) certificate(w http.ResponseWriter, r *http.Request) error {
	if r.PathValue("pemFileName") != CertificateFileName {
		return httpapi.SpringError(http.StatusNotFound, nil)
	}
	var body []byte
	for _, c := range s.keys.Current().Identity.Chain() {
		body = append(body, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: c.Raw})...)
	}
	w.Header().Set("Content-Disposition", `attachment; filename="`+CertificateFileName+`"`)
	w.Header().Set("Content-Type", ContentTypePEM)
	w.WriteHeader(http.StatusOK)
	_, err := w.Write(body)
	return err
}

// generate is POST /api/v1/signature/generate (S10.2-AC4): the JSON object sent, signed as every
// JWS of this service is, with the caller's correlationId and ttl. The checks run in Spring's order:
// the media type, each header, the body, then the ttl's value.
func (s *Service) generate(w http.ResponseWriter, r *http.Request) error {
	if err := httpapi.CheckJSONContentType(r); err != nil {
		return err
	}
	correlationID, err := correlationIDHeader(r.Header)
	if err != nil {
		return err
	}
	ttlSeconds, err := ttlHeader(r.Header)
	if err != nil {
		return err
	}
	var raw jsontext.Value
	if err := httpapi.DecodeJSON(r, &raw); err != nil {
		return err
	}
	payload, err := normalize(raw)
	if err != nil {
		return httpapi.MalformedJSON()
	}
	if ttlSeconds <= 0 {
		return httpapi.BusinessRule("ttlTimeSeconds", violationTTL)
	}
	if correlationID == uuid.Nil {
		// The Java build signs the nil UUID; this one has nothing to tie the JWS to, so it refuses
		// it as Java refuses a correlationId it does not have.
		return httpapi.BusinessRule("correlationId", violationGenCID)
	}
	token, err := s.signer.Sign(s.keys.Current().Identity, jws.Message{
		Payload:       payload,
		CorrelationID: correlationID,
		TTL:           time.Duration(ttlSeconds) * time.Second,
	})
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", ContentTypeJOSE)
	w.WriteHeader(http.StatusOK)
	_, err = w.Write([]byte(token))
	return err
}

// validationRequest is SignatureValidationRequest.
type validationRequest struct {
	Content contract.String `json:"content"`
}

// validate is POST /api/v1/signature/validate (S10.2-AC5): 200 with the JWS's correlationId in
// Correlation-Id when it verifies, and the Java build's refusal when it does not.
func (s *Service) validate(w http.ResponseWriter, r *http.Request) error {
	var req validationRequest
	if err := httpapi.DecodeJSON(r, &req); err != nil {
		return err
	}
	if req.Content.IsNull() {
		return httpapi.Validation("content: must not be null")
	}
	if strings.TrimSpace(req.Content.Value) == "" {
		return httpapi.BusinessRule("jwsToken", violationToken)
	}
	verified, err := s.verifier.Verify(r.Context(), req.Content.Value, jws.Expect{})
	if err != nil {
		s.logger.WarnContext(r.Context(), "x9-qrcode signature did not verify", "problem", jws.ProblemOf(err),
			"err", err)
		return httpapi.BusinessRule("", violationSignature)
	}
	w.Header().Set(HeaderCorrelationID, verified.Header.CorrelationID.String())
	w.WriteHeader(http.StatusOK)
	return nil
}
