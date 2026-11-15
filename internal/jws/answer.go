// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"context"
	"errors"
	"fmt"
	"math"
	"strings"

	"github.com/google/uuid"
)

// AnswerSignature is what the signature of a payee's answer to a notification this service sent
// is worth (Story 2.3; Java's AnswerSignatureEnumDTO).
type AnswerSignature string

// The three verdicts on an answer.
const (
	// AnswerVerified: a JWS that verified, under the correlationId sent, whose signed statusCode
	// is the HTTP status it came with.
	AnswerVerified AnswerSignature = "VERIFIED"
	// AnswerAbsent: no JWS at all, an empty body or one that is not JOSE.
	AnswerAbsent AnswerSignature = "ABSENT"
	// AnswerInvalid: presented as a JWS, and failed a check.
	AnswerInvalid AnswerSignature = "INVALID"
)

// AnswerCheck is the outbound check of a payee's answer. It reports and never refuses: an
// unsigned or invalid 200 is still a 200, and the payer decides what it is worth.
type AnswerCheck struct {
	Signature AnswerSignature
	// StatusCode is the status the payee signed when VERIFIED; otherwise the HTTP status.
	StatusCode int
	// Problem is the signatureProblem code; empty when VERIFIED.
	Problem Problem
	// Detail is the signatureDetail: words for a log; empty when VERIFIED.
	Detail string
	// Verified is the verified answer; nil unless VERIFIED.
	Verified *Verified
}

// CheckAnswer is Java's checkAnswer (RestClientOutboundNotificationService): the payee's answer
// to a notification sent under sent must be a JWS signed with an X9 key, echoing sent, whose body
// is {"statusCode": integer} equal to httpStatus (ANSI X9.150-2026 A.4.5 note). An empty body or
// one that is NOT_JWS is ABSENT; any other failure is INVALID, with the code: the verifier's,
// STATUS_MISSING or STATUS_MISMATCH.
func (v *Verifier) CheckAnswer(ctx context.Context, httpStatus int, body string, sent uuid.UUID) AnswerCheck {
	if strings.TrimSpace(body) == "" {
		return unsignedAnswer(httpStatus, AbsentBody, "the answer has no body")
	}
	verified, err := v.Verify(ctx, body, Expect{CorrelationID: sent})
	if err != nil {
		detail := "the signature did not verify"
		if r, ok := errors.AsType[*Refusal](err); ok && r.Detail != "" {
			detail = r.Detail
		}
		return unsignedAnswer(httpStatus, ProblemOf(err), detail)
	}
	signed, ok := signedStatusCode(verified.Payload)
	if !ok {
		return unsignedAnswer(httpStatus, StatusMissing, "the signed body has no integer statusCode")
	}
	if signed != httpStatus {
		return unsignedAnswer(httpStatus, StatusMismatch, fmt.Sprintf("statusCode mismatch: HTTP %d, signed %d", httpStatus, signed))
	}
	return AnswerCheck{Signature: AnswerVerified, StatusCode: signed, Verified: verified}
}

func unsignedAnswer(httpStatus int, p Problem, detail string) AnswerCheck {
	signature := AnswerInvalid
	if p == AbsentBody || p == NotJWS {
		signature = AnswerAbsent
	}
	return AnswerCheck{Signature: signature, StatusCode: httpStatus, Problem: p, Detail: detail}
}

// signedStatusCode reads statusCode from a signed body: a JSON integer within 32 bits. Java takes
// any JSON number and truncates it (200.9 reads as 200); the body is to be an integer (ANSI
// X9.150-2026 A.4.5 note), so a fraction is STATUS_MISSING here.
func signedStatusCode(payload []byte) (int, bool) {
	fields, err := decodeObject(payload)
	if err != nil || !present(fields, HeaderStatusCode) {
		return 0, false
	}
	n, ok := integerOf(fields[HeaderStatusCode])
	if !ok || n < math.MinInt32 || n > math.MaxInt32 {
		return 0, false
	}
	return int(n), true
}
