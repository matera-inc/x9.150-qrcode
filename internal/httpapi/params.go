// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package httpapi

import (
	"encoding/hex"
	"net/http"
	"strconv"
	"strings"
	"unicode/utf16"

	"github.com/google/uuid"
)

// IDLength is the length of a QR Code or location id on the wire: 32 hexadecimal characters, no
// dashes (ADR-0026, as X9.150 defines the QR Code id).
const IDLength = 32

// PathID reads the path wildcard name of a route whose contract declares it as an id (minLength and
// maxLength 32). A value of another length is Java's 400, which names the operation as Bean
// Validation names a method parameter: "getPaymentRequest.id: size must be between 32 and 32".
// operation is the operationId of openapi.yaml. The value is returned as sent; ParseID reads it.
func PathID(r *http.Request, operation, name string) (string, error) {
	v := r.PathValue(name)
	if javaLength(v) != IDLength {
		return "", Validation(operation + "." + name + ": size must be between 32 and 32")
	}
	return v, nil
}

// ParseID reads a QR Code or location id in the forms ADR-0026 names and the Java build accepts:
// exactly 32 hexadecimal characters, upper or lower case, no dashes. ok is false for anything else,
// which the caller refuses with InvalidQRCodeID or InvalidLocationID.
func ParseID(s string) (id uuid.UUID, ok bool) {
	if len(s) != IDLength {
		return uuid.UUID{}, false
	}
	if _, err := hex.Decode(id[:], []byte(s)); err != nil {
		return uuid.UUID{}, false
	}
	return id, true
}

// FormatID writes an id as every answer carries it: 32 upper-case hexadecimal characters, no
// dashes (ADR-0026).
func FormatID(id uuid.UUID) string {
	return strings.ToUpper(hex.EncodeToString(id[:]))
}

// InvalidQRCodeID is Java's refusal of a 32-character QR Code id that is not hexadecimal.
func InvalidQRCodeID() *Problem { return Validation("QRCodeId is invalid.") }

// InvalidLocationID is Java's refusal of a 32-character location id that is not hexadecimal.
func InvalidLocationID() *Problem { return Validation("LocationId is invalid.") }

// ParseIfMatch reads an If-Match header as the Java build reads it (UpdateQRCodeStatusRequestMapper
// .parseIfMatch, used by PATCH and status-update): none, blank or "*" is no condition; otherwise the
// value is trimmed, a leading W/ is dropped, then a leading and a trailing double quote. So "0-ACTIVE",
// 0-ACTIVE, W/"0-ACTIVE" and W/0-ACTIVE all read 0-ACTIVE, and anything else (abc, a list) is a tag
// that matches nothing, which the caller answers 412. Several If-Match headers are joined with a
// comma, as Spring joins them for a String parameter.
func ParseIfMatch(h http.Header) (tag string, conditional bool) {
	values := h.Values("If-Match")
	if len(values) == 0 {
		return "", false
	}
	v := strings.TrimSpace(strings.Join(values, ","))
	if v == "" || v == "*" {
		return "", false
	}
	v = strings.TrimPrefix(v, "W/")
	v = strings.TrimPrefix(v, `"`)
	v = strings.TrimSuffix(v, `"`)
	return v, true
}

// IntBounds are a query parameter's minimum and maximum from openapi.yaml; nil is no bound:
// IntBounds{Min: new(int64(1)), Max: new(int64(500))}.
type IntBounds struct {
	Min, Max *int64
}

// QueryInt reads the integer query parameter name as Spring converts it and Bean Validation checks
// it. Absent or empty is ok == false. A value that is not an integer is Java's 400 "<name> has
// invalid value."; one outside its bounds names the operation: "getPaymentRequest.revision: must be
// greater than or equal to 0".
func QueryInt(r *http.Request, operation, name string, bounds IntBounds) (value int64, ok bool, err error) {
	raw := r.URL.Query().Get(name)
	raw = strings.Join(strings.Fields(raw), "")
	if raw == "" {
		return 0, false, nil
	}
	value, perr := strconv.ParseInt(raw, 10, 32)
	if perr != nil {
		return 0, false, Validation(name + " has invalid value.")
	}
	if bounds.Min != nil && value < *bounds.Min {
		return 0, false, Validation(operation + "." + name + ": must be greater than or equal to " +
			strconv.FormatInt(*bounds.Min, 10))
	}
	if bounds.Max != nil && value > *bounds.Max {
		return 0, false, Validation(operation + "." + name + ": must be less than or equal to " +
			strconv.FormatInt(*bounds.Max, 10))
	}
	return value, true, nil
}

// javaLength is the length Java's String.length gives: UTF-16 code units, which is what
// @Size counts.
func javaLength(s string) int {
	n := 0
	for _, r := range s {
		n += utf16.RuneLen(r)
	}
	return n
}
