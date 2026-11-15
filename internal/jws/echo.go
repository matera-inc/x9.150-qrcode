// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"encoding/base64"
	"encoding/json"
	"strings"

	"github.com/google/uuid"
)

// AnsweredCorrelationID is the correlationId a JWS's protected header carries, read without
// verifying anything, for a record of what came back: the string as written (clipped to 64
// characters), the JSON as written when it is not a string, or "" when the token is not a compact
// JWS, its header is not a JSON object, or it carries no correlationId.
func AnsweredCorrelationID(token string) string {
	first, _, ok := strings.Cut(strings.TrimSpace(token), ".")
	if !ok {
		return ""
	}
	raw, err := base64.RawURLEncoding.DecodeString(first)
	if err != nil {
		return ""
	}
	fields, err := decodeObject(raw)
	if err != nil || !present(fields, HeaderCorrelationID) {
		return ""
	}
	var s string
	if json.Unmarshal(fields[HeaderCorrelationID], &s) == nil {
		return clip(json.RawMessage(s))
	}
	return clip(fields[HeaderCorrelationID])
}

// NotEchoed reports whether a JWS the verifier refused with problem was refused because its header
// does not carry exactly sent, the correlationId of the request it answers (ADR-0027, amendments
// of 2026-11-15): CORRELATION_ID_MISMATCH, or BAD_HEADER with no correlationId or another one. The
// verifier's code decides first, so both builds agree: an answer refused for its signature, its
// chain, its time window or its statusCode is not "a bad correlationId", whatever it carries, and
// one refused BAD_HEADER for another parameter while carrying sent is not either.
func NotEchoed(problem Problem, token string, sent uuid.UUID) bool {
	if problem == CorrelationIDMismatch {
		return true
	}
	return problem == BadHeader && !strings.EqualFold(AnsweredCorrelationID(token), sent.String())
}
