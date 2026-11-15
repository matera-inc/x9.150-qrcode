// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"encoding/base64"
	"strings"
	"testing"

	"github.com/google/uuid"
)

// token is a compact JWS whose header is the JSON given; the payload and the signature are
// placeholders, since AnsweredCorrelationID and NotEchoed read the header alone.
func token(header string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(header)) + ".e30.c2ln"
}

// ADR-0027, amendments of 2026-11-15: an answer's correlationId is read for the record as written,
// and "" when there is none to read.
func TestAnsweredCorrelationID(t *testing.T) {
	sent := uuid.MustParse("1b4e28ba-2fa1-11d2-883f-0016d3cca427")
	for _, tc := range []struct {
		name, token, want string
	}{
		{"the one sent", token(`{"alg":"PS512","correlationId":"` + sent.String() + `"}`), sent.String()},
		{"another", token(`{"alg":"PS512","correlationId":"not-ours"}`), "not-ours"},
		{"a number", token(`{"alg":"PS512","correlationId":7}`), "7"},
		{"null", token(`{"alg":"PS512","correlationId":null}`), ""},
		{"none", token(`{"alg":"PS512"}`), ""},
		{"a long one, clipped", token(`{"correlationId":"` + strings.Repeat("a", 100) + `"}`), strings.Repeat("a", 64) + "…"},
		{"not a JWS", `{"statusCode":200}`, ""},
		{"a header that is not base64url", "!!!.e30.c2ln", ""},
		{"a header that is not an object", token(`[1]`), ""},
		{"empty", "", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := AnsweredCorrelationID(tc.token); got != tc.want {
				t.Errorf("got %q, want %q", got, tc.want)
			}
		})
	}
}

// ADR-0027, amendments of 2026-11-15: "a bad correlationId" is CORRELATION_ID_MISMATCH, or a
// BAD_HEADER whose header lacks the id sent. The verifier's code decides first: an answer refused
// for anything else is not one, whatever it carries.
func TestNotEchoed(t *testing.T) {
	sent := uuid.MustParse("1b4e28ba-2fa1-11d2-883f-0016d3cca427")
	ours := token(`{"alg":"PS512","correlationId":"` + sent.String() + `"}`)
	oursUpper := token(`{"alg":"PS512","correlationId":"` + strings.ToUpper(sent.String()) + `"}`)
	other := token(`{"alg":"PS512","correlationId":"` + uuid.NewString() + `"}`)
	none := token(`{"alg":"PS512"}`)
	for _, tc := range []struct {
		name    string
		problem Problem
		token   string
		want    bool
	}{
		{"a mismatch", CorrelationIDMismatch, other, true},
		{"a bad header with none", BadHeader, none, true},
		{"a bad header with another", BadHeader, token(`{"correlationId":"1-2-3-4-5"}`), true},
		{"a bad header with ours (another parameter is wrong)", BadHeader, ours, false},
		{"a bad header with ours in capitals", BadHeader, oursUpper, false},
		{"a bad signature with another", BadSignature, other, false},
		{"an untrusted chain with none", UntrustedChain, none, false},
		{"expired", Expired, other, false},
		{"not a JWS", NotJWS, `{"statusCode":200}`, false},
		{"no body", AbsentBody, "", false},
		{"verified", "", ours, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := NotEchoed(tc.problem, tc.token, sent); got != tc.want {
				t.Errorf("got %v, want %v", got, tc.want)
			}
		})
	}
}
