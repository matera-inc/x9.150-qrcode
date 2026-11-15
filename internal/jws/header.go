// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/url"
	"slices"
	"strconv"
	"strings"

	"github.com/google/uuid"
)

// This file is ADR-0027's verification steps 2 and 3 as pure functions: the compact string split
// by hand, the protected header decoded with every number kept as written (json.Number, so a
// millisecond iat never passes through float64), and the X9.150 profile checked. Nothing here
// fetches, verifies a signature or reads a clock; FuzzParseHeader and FuzzCheckProfile drive it.

// compact is a compact JWS split at its two dots.
type compact struct {
	header    []byte // the protected header, decoded
	payload   []byte // decoded
	signature []byte // decoded
	input     string // the signing input: the first two parts as sent
}

// splitCompact splits token at its dots and decodes each part (base64url, no padding), with the
// codes Java's parser gives: an empty body is ABSENT_BODY; five parts with an "enc" are
// ENCRYPTED; anything that is not three parts, or whose parts do not decode, is NOT_JWS.
func splitCompact(token string) (*compact, *Refusal) {
	if token == "" {
		return nil, refusal(AbsentBody, nil, "there is no JWS: the body is empty")
	}
	parts := strings.Split(token, ".")
	if len(parts) == 5 {
		if h, err := base64.RawURLEncoding.DecodeString(parts[0]); err == nil {
			var fields map[string]json.RawMessage
			if json.Unmarshal(h, &fields) == nil && present(fields, "enc") {
				return nil, refusal(Encrypted, nil, "the body is an encrypted JOSE object (JWE), not a signed one")
			}
		}
		return nil, refusal(NotJWS, nil, "the body is not a JWS: five parts, and not a JWE")
	}
	if len(parts) != 3 {
		return nil, refusal(NotJWS, nil, "the body is not a JWS: %d parts, not three", len(parts))
	}
	header, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return nil, refusal(NotJWS, err, "the body is not a JWS: the header is not base64url")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, refusal(NotJWS, err, "the body is not a JWS: the payload is not base64url")
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return nil, refusal(NotJWS, err, "the body is not a JWS: the signature is not base64url")
	}
	return &compact{header: header, payload: payload, signature: signature, input: parts[0] + "." + parts[1]}, nil
}

// protected is the protected header as decoded: every parameter as written, and the registered
// ones Java's parser types, already checked for their JSON type. A JSON null is an absent
// parameter, as it is to Java.
type protected struct {
	fields  map[string]json.RawMessage
	alg     string
	typ     string
	kid     string
	hasKid  bool
	jku     string
	x5u     string
	x5tS256 *string
	x5c     []string
	hasX5C  bool
	crit    []string
	hasCrit bool
}

// parseHeader decodes a protected header. A header Java's parser cannot read is NOT_JWS: not a
// JSON object, no alg, or a registered parameter of the wrong JSON type (crit and x5c not lists
// of strings; typ, cty, kid, jku, x5u, x5t and x5t#S256 not strings; jku and x5u not URIs; jwk
// not an object; b64 not a boolean). alg none is ALG_NONE, but only with an empty signature, as
// Java reads an unsecured object; with one it is NOT_JWS. Whether alg is one this verifier
// accepts is the caller's next check.
func parseHeader(raw []byte, signature []byte) (*protected, *Refusal) {
	fields, err := decodeObject(raw)
	if err != nil {
		return nil, refusal(NotJWS, err, "the body is not a JWS: the header is not a JSON object")
	}
	h := &protected{fields: fields}
	alg, ok, bad := stringField(fields, "alg")
	if bad || !ok {
		return nil, refusal(NotJWS, nil, "the body is not a JWS: the header has no alg")
	}
	h.alg = alg
	if alg == "none" {
		if len(signature) != 0 {
			return nil, refusal(NotJWS, nil, "the body is not a JWS: an unsecured object (alg none) with a signature")
		}
		return nil, refusal(AlgNone, nil, "the body is an unsecured JOSE object (alg none): it carries no signature")
	}
	for _, name := range []string{"typ", "cty", "kid", "jku", "x5u", "x5t", HeaderX5TS256} {
		value, ok, bad := stringField(fields, name)
		if bad {
			return nil, refusal(NotJWS, nil, "the body is not a JWS: the header's %s is not a string", name)
		}
		if !ok {
			continue
		}
		switch name {
		case "typ":
			h.typ = value
		case "kid":
			h.kid, h.hasKid = value, true
		case HeaderJKU, HeaderX5U:
			if _, err := url.Parse(value); err != nil {
				return nil, refusal(NotJWS, err, "the body is not a JWS: the header's %s is not a URI", name)
			}
			if name == HeaderJKU {
				h.jku = value
			} else {
				h.x5u = value
			}
		case HeaderX5TS256:
			h.x5tS256 = &value
		}
	}
	if h.crit, h.hasCrit, bad = stringsField(fields, "crit"); bad {
		return nil, refusal(NotJWS, nil, "the body is not a JWS: the header's crit is not a list of strings")
	}
	if h.x5c, h.hasX5C, bad = stringsField(fields, HeaderX5C); bad {
		return nil, refusal(NotJWS, nil, "the body is not a JWS: the header's x5c is not a list of strings")
	}
	if present(fields, "jwk") && !isObject(fields["jwk"]) {
		return nil, refusal(NotJWS, nil, "the body is not a JWS: the header's jwk is not an object")
	}
	if present(fields, "b64") {
		var b bool
		if json.Unmarshal(fields["b64"], &b) != nil {
			return nil, refusal(NotJWS, nil, "the body is not a JWS: the header's b64 is not a boolean")
		}
	}
	return h, nil
}

// supportedCritical are the parameters this verifier understands in crit (manifest.json,
// verifier.supportedCritical), and requiredCritical the ones crit must name.
var (
	supportedCritical = []string{HeaderCorrelationID, HeaderIssuedAt, HeaderTTL, HeaderStatusCode}
	requiredCritical  = []string{HeaderCorrelationID, HeaderIssuedAt, HeaderTTL}
)

// profile is what step 3 reads from a header that passed it.
type profile struct {
	correlationID uuid.UUID
	iat           int64
	ttl           int64
	statusCode    int
}

// checkProfile is ADR-0027 step 3, in Java's order: crit, correlationId, iat, ttl, the time
// window at nowMillis (FUTURE_IAT when iat is more than skewMillis ahead, EXPIRED from iat + ttl),
// then statusCode when crit names it. Every refusal but the two time codes is BAD_HEADER. typ is
// not checked (owner, 2026-11-14).
func checkProfile(h *protected, nowMillis, skewMillis int64) (profile, *Refusal) {
	var p profile
	if !h.hasCrit || len(h.crit) == 0 {
		return p, refusal(BadHeader, nil, "the header has no crit: X9.150 requires it to name correlationId, iat and ttl")
	}
	for _, name := range requiredCritical {
		if !slices.Contains(h.crit, name) {
			return p, refusal(BadHeader, nil, "crit must name at least correlationId, iat and ttl; it does not name %s", name)
		}
	}
	for _, name := range h.crit {
		if !slices.Contains(supportedCritical, name) {
			return p, refusal(BadHeader, nil, "crit names %q, a parameter this verifier does not understand", name)
		}
	}

	id, ok, bad := stringField(h.fields, HeaderCorrelationID)
	switch {
	case !present(h.fields, HeaderCorrelationID):
		return p, refusal(BadHeader, nil, "the header has no correlationId")
	case bad || !ok || !isUUID(id):
		return p, refusal(BadHeader, nil, "correlationId is not a UUID: %s", clip(h.fields[HeaderCorrelationID]))
	}
	p.correlationID = uuid.MustParse(id)

	var r *Refusal
	if p.iat, r = positiveMillis(h.fields, HeaderIssuedAt); r != nil {
		return p, r
	}
	if p.ttl, r = positiveMillis(h.fields, HeaderTTL); r != nil {
		return p, r
	}
	if r := window(p.iat, p.ttl, nowMillis, skewMillis); r != nil {
		return p, r
	}

	if slices.Contains(h.crit, HeaderStatusCode) {
		if p.statusCode, r = headerStatusCode(h.fields); r != nil {
			return p, r
		}
	}
	return p, nil
}

// window is the time rule (manifest.json, verifier.rule): FUTURE_IAT when iat > now + skew,
// EXPIRED when now >= iat + ttl. An iat + ttl past the largest millisecond is EXPIRED, as Java's
// long addition wraps it negative.
func window(iat, ttl, now, skew int64) *Refusal {
	if skew < 0 {
		skew = 0
	}
	if now <= math.MaxInt64-skew && iat > now+skew {
		return refusal(FutureIAT, nil, "iat %d is ahead of this verifier's clock (%d) by more than the allowed %d ms", iat, now, skew)
	}
	if iat > math.MaxInt64-ttl {
		return refusal(Expired, nil, "iat %d + ttl %d is past the largest instant", iat, ttl)
	}
	if now >= iat+ttl {
		return refusal(Expired, nil, "the JWS expired at %d (iat + ttl); it is %d", iat+ttl, now)
	}
	return nil
}

// positiveMillis reads iat or ttl: a JSON integer, at least 1, that fits in 64 bits. A string, a
// number with a fraction or an exponent, or zero is BAD_HEADER, as in Java, whose header holds
// such a value as something other than a Long.
func positiveMillis(fields map[string]json.RawMessage, name string) (int64, *Refusal) {
	if !present(fields, name) {
		return 0, refusal(BadHeader, nil, "the header has no %s", name)
	}
	n, ok := integerOf(fields[name])
	if !ok {
		return 0, refusal(BadHeader, nil, "%s is not an integer number of milliseconds: %s", name, clip(fields[name]))
	}
	if n <= 0 {
		return 0, refusal(BadHeader, nil, "%s must be a positive number of milliseconds; it is %d", name, n)
	}
	return n, nil
}

// headerStatusCode reads the statusCode header crit names. Java reads it as text (an integer or
// a string of digits) and accepts it only when Spring knows the status; anything else is
// BAD_HEADER.
func headerStatusCode(fields map[string]json.RawMessage) (int, *Refusal) {
	if !present(fields, HeaderStatusCode) {
		return 0, refusal(BadHeader, nil, "crit names statusCode and the header has none")
	}
	raw := fields[HeaderStatusCode]
	var text string
	if s, ok, _ := stringField(fields, HeaderStatusCode); ok {
		text = s
	} else if n, ok := integerOf(raw); ok {
		text = strconv.FormatInt(n, 10)
	} else {
		return 0, refusal(BadHeader, nil, "statusCode is not an HTTP status code: %s", clip(raw))
	}
	code, err := strconv.ParseInt(text, 10, 32)
	if err != nil || !knownStatus(int(code)) {
		return 0, refusal(BadHeader, nil, "statusCode is not an HTTP status code: %s", clip(raw))
	}
	return int(code), nil
}

// knownStatuses are the HTTP statuses Spring 6.2's HttpStatus names, which Java's
// HttpStatus.resolve accepts in a statusCode header.
var knownStatuses = []int{
	100, 101, 102, 103,
	200, 201, 202, 203, 204, 205, 206, 207, 208, 226,
	300, 301, 302, 303, 304, 305, 307, 308,
	400, 401, 402, 403, 404, 405, 406, 407, 408, 409, 410, 411, 412, 413, 414, 415, 416, 417, 418,
	419, 420, 421, 422, 423, 424, 425, 426, 428, 429, 431, 451,
	500, 501, 502, 503, 504, 505, 506, 507, 508, 509, 510, 511,
}

func knownStatus(code int) bool { return slices.Contains(knownStatuses, code) }

// isUUID reports whether s is a UUID in its canonical text form, 8-4-4-4-12 hexadecimal digits in
// either case (ANSI X9.150-2026 §10.7 3.1: "a valid UUID format"). uuid.Parse alone would also
// take the 32-digit, braced and urn:uuid: forms.
func isUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i := range len(s) {
		c := s[i]
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return false
			}
		default:
			if (c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F') {
				return false
			}
		}
	}
	return true
}

// decodeObject decodes a JSON object, keeping every value raw. Trailing data is an error.
func decodeObject(raw []byte) (map[string]json.RawMessage, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var fields map[string]json.RawMessage
	if err := dec.Decode(&fields); err != nil {
		return nil, err
	}
	if fields == nil {
		return nil, errors.New("null, not an object")
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return nil, errors.New("data after the object")
	}
	return fields, nil
}

// integerOf reads raw as a JSON integer that fits in 64 bits: never through float64.
func integerOf(raw json.RawMessage) (int64, bool) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var v any
	if dec.Decode(&v) != nil {
		return 0, false
	}
	n, ok := v.(json.Number)
	if !ok {
		return 0, false
	}
	i, err := strconv.ParseInt(n.String(), 10, 64)
	if err != nil {
		return 0, false
	}
	return i, true
}

// present reports whether name is in fields with a value other than null.
func present(fields map[string]json.RawMessage, name string) bool {
	raw, ok := fields[name]
	return ok && !isNull(raw)
}

func isNull(raw json.RawMessage) bool { return string(bytes.TrimSpace(raw)) == "null" }

func isObject(raw json.RawMessage) bool {
	var m map[string]json.RawMessage
	return json.Unmarshal(raw, &m) == nil && m != nil
}

// stringField reads a string parameter: ok when present, bad when present and not a string.
func stringField(fields map[string]json.RawMessage, name string) (value string, ok, bad bool) {
	if !present(fields, name) {
		return "", false, false
	}
	if err := json.Unmarshal(fields[name], &value); err != nil {
		return "", false, true
	}
	return value, true, false
}

// stringsField reads a list of strings: ok when present, bad when present and not one.
func stringsField(fields map[string]json.RawMessage, name string) (values []string, ok, bad bool) {
	if !present(fields, name) {
		return nil, false, false
	}
	var raw []json.RawMessage
	if err := json.Unmarshal(fields[name], &raw); err != nil {
		return nil, false, true
	}
	values = make([]string, 0, len(raw))
	for _, r := range raw {
		var s string
		if isNull(r) || json.Unmarshal(r, &s) != nil {
			return nil, false, true
		}
		values = append(values, s)
	}
	return values, true, false
}

// clip is a header value for a refusal's detail, cut short.
func clip(raw json.RawMessage) string {
	const most = 64
	s := string(raw)
	if len(s) > most {
		return s[:most] + "…"
	}
	return s
}
