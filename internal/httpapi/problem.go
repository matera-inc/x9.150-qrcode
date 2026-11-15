// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package httpapi

import (
	"net/http"
	"strings"
)

// The problem types of the Java build (ErrorTypeEnum), unchanged (ADR-0028).
const (
	typeBase = "https://x9.matera.com/api/"

	TypeValidation                = typeBase + "validation-error"
	TypeMalformedJSON             = typeBase + "malformed-json"
	TypeBusinessRule              = typeBase + "business-rule-violation"
	TypeResourceNotFound          = typeBase + "resource-not-found"
	TypeStatusConflict            = typeBase + "status-conflict"
	TypePreconditionFailed        = typeBase + "precondition-failed"
	TypeInvalidSignature          = typeBase + "invalid-signature"
	TypePayloadRefused            = typeBase + "payload-refused"
	TypePayloadUnreachable        = typeBase + "payload-unreachable"
	TypeNotificationUndeliverable = typeBase + "notification-undeliverable"
	TypeInternalError             = typeBase + "internal-error"
)

// ContentTypeProblem is the media type of every refusal (RFC 9457).
const ContentTypeProblem = "application/problem+json"

// Problem is a refusal in the Java build's shape: Spring's ProblemDetail, with the extension members
// GlobalControllerAdvice adds at the top level. It is an error, so a handler returns it.
//
// A field left at its zero value is not written, except Status. Violations is written when it is
// not nil, so an empty list stays an empty list.
type Problem struct {
	Type   string
	Title  string
	Status int
	Detail string

	// Extension members, in the order the Java build adds them.
	CurrentStatus    string // 409 and 412: the status the QR Code was found in
	CurrentRevision  *int64 // 412: the revision it was found at
	SignatureProblem string // 401: the closed code of the check that failed
	Violations       []string

	// Extra holds members a later story adds, such as the signed answer's statusCode. They are
	// written after the members above.
	Extra map[string]any
}

func (p *Problem) Error() string {
	if len(p.Violations) == 0 {
		return p.Title
	}
	return p.Title + ": " + strings.Join(p.Violations, "; ")
}

func newProblem(status int, title, typ, detail string, violations []string) *Problem {
	return &Problem{Type: typ, Title: title, Status: status, Detail: detail, Violations: violations}
}

// Validation is the 400 of a request that failed the contract's checks (Java's
// MethodArgumentNotValidException, ConstraintViolationException and ValueObjectRuleException).
// Each violation reads "<field path>: <message>", or only the message where Java names no field.
func Validation(violations ...string) *Problem {
	return newProblem(http.StatusBadRequest, "Validation Failed", TypeValidation,
		"One or more fields failed validation.", nonNil(violations))
}

// HeaderValidation is the 400 of a required header that is missing (Java's
// MissingRequestHeaderException).
func HeaderValidation(violation string) *Problem {
	return newProblem(http.StatusBadRequest, "Validation Failed", TypeValidation,
		"One or more headers failed validation.", []string{violation})
}

// MissingHeader is HeaderValidation with Spring's own words for a required header that is absent.
// javaType is the type the Java method parameter had (UUID, Integer, String), which Spring names.
func MissingHeader(name, javaType string) *Problem {
	return HeaderValidation("Required request header '" + name + "' for method parameter type " + javaType +
		" is not present")
}

// MalformedJSON is the 400 of a body that is not JSON, or not the JSON the operation reads (Java's
// HttpMessageNotReadableException). It carries no violations.
func MalformedJSON() *Problem {
	return newProblem(http.StatusBadRequest, "Malformed JSON", TypeMalformedJSON,
		"The request body is not readable or is incorrectly formatted.", nil)
}

// BusinessRule is the 400 of a request the contract accepts and a business rule refuses (Java's
// BusinessRuleException). An empty field gives a violation that is the message alone.
func BusinessRule(field, message string) *Problem {
	violation := message
	if strings.TrimSpace(field) != "" {
		violation = field + ": " + message
	}
	return newProblem(http.StatusBadRequest, "Business Rule Violation", TypeBusinessRule,
		"A business rule was violated.", []string{violation})
}

// NotFound is the 404 of an unknown resource; message is Java's, such as "Could not find QR code
// with id: <ID>".
func NotFound(message string) *Problem {
	return newProblem(http.StatusNotFound, "Resource not found", TypeResourceNotFound,
		"A resource was not found.", []string{message})
}

// StatusConflict is the 409 of a transition the QR Code's status does not allow.
func StatusConflict(currentStatus, message string) *Problem {
	p := newProblem(http.StatusConflict, "Status Conflict", TypeStatusConflict,
		"The QR Code is not in a status that allows this transition.", []string{message})
	p.CurrentStatus = currentStatus
	return p
}

// PreconditionFailed is the 412 of an If-Match that no longer holds. Nothing was written.
func PreconditionFailed(currentStatus string, currentRevision int64, message string) *Problem {
	p := newProblem(http.StatusPreconditionFailed, "Precondition Failed", TypePreconditionFailed,
		"The QR Code changed after it was read, so the condition on this request no longer holds. Nothing was written.",
		[]string{message})
	p.CurrentStatus, p.CurrentRevision = currentStatus, &currentRevision
	return p
}

// InvalidSignature is the 401 of a JWS that did not verify. signatureProblem is the closed code of
// the check that failed, or empty; the words stay in the log.
func InvalidSignature(signatureProblem string) *Problem {
	p := newProblem(http.StatusUnauthorized, "Invalid Signature", TypeInvalidSignature,
		"The JWS signature could not be verified, so the request was not processed.", nil)
	p.SignatureProblem = signatureProblem
	return p
}

// NotificationUndeliverable is the 502 of a payee that could not be reached.
func NotificationUndeliverable() *Problem {
	return newProblem(http.StatusBadGateway, "Notification Undeliverable", TypeNotificationUndeliverable,
		"The payee could not be reached, so nothing was delivered. Retry.", nil)
}

// PayloadRefused relays a payee's refusal to release a payload, with the payee's own status.
func PayloadRefused(upstreamStatus int, violations []string) *Problem {
	return newProblem(upstreamStatus, "Payload Refused", TypePayloadRefused,
		"The payee's service refused to release this payload. Its own reason is relayed in `violations`.",
		nonNil(violations))
}

// PayloadUnreachable is the 502 of a payee's service that never answered a decode.
func PayloadUnreachable() *Problem {
	return newProblem(http.StatusBadGateway, "Payload Unreachable", TypePayloadUnreachable,
		"The payee's service could not be reached, so this QR Code could not be decoded. Retry.", nil)
}

// InternalError is the 500 of anything unexpected. It never carries the cause: no stack, no SQL, no
// internal detail. The cause goes to the log.
func InternalError() *Problem {
	return newProblem(http.StatusInternalServerError, "Internal Server Error", TypeInternalError,
		"An unexpected error occurred while processing the request.", nil)
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}
