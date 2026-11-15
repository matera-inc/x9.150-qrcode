// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"encoding/base64"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"math"
	"strconv"
	"strings"

	"github.com/google/uuid"
)

// Every member of a request body is one of the types below, or a List or an Object of them. Each
// remembers whether the key was sent (Present) and whether it was sent as null (Null), which is how
// a PATCH tells "leave it" from "clear it" (ADR-0024) and how Bean Validation's @NotNull is read:
// a member is null when it is absent or null.
//
// Each reads the JSON types Jackson reads into the Java field, with the same coercions, and fails
// (malformed-json) where Jackson fails. Each writes back the value it holds, so the type decoded is
// the type validated and stored.

// errMalformed is any value Jackson would not bind; the reader answers it malformed-json.
var errMalformed = errors.New("malformed")

// presence is what every member knows about its key.
type presence struct {
	Present bool // the key was sent
	Null    bool // its value was null, or Jackson read it as null ("" for a number)
}

// IsNull reports what @NotNull refuses: the member is absent or null.
func (p presence) IsNull() bool { return !p.Present || p.Null }

// IsZero is true when the key was not sent, so a member tagged omitzero is not written back.
func (p presence) IsZero() bool { return !p.Present }

// IsSet reports whether the member holds a value.
func (p presence) IsSet() bool { return p.Present && !p.Null }

// String is a Java String member. Like Jackson, it takes a JSON string, and also a number (its
// text as sent) or a boolean ("true"/"false"); an object or an array is malformed.
type String struct {
	presence
	Value string
}

// Str is a String holding v, for building a body in Go.
func Str(v string) String { return String{Present: true, Value: v} }

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (s *String) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*s = String{Present: true}
	text, null, err := scalarText(dec)
	switch {
	case err != nil:
		return err
	case null:
		s.Null = true
	default:
		s.Value = text
	}
	return nil
}

// MarshalJSONTo implements json.MarshalerTo.
func (s String) MarshalJSONTo(enc *jsontext.Encoder) error {
	if s.Null {
		return enc.WriteToken(jsontext.Null)
	}
	return enc.WriteToken(jsontext.String(s.Value))
}

// Long is a Java Long member (an int64 on the wire). Like Jackson: an integer within int64; a number
// with a fraction or an exponent, truncated toward zero; a string holding an integer, trimmed, and ""
// read as null. A boolean, an object, an array, or any number past int64 is malformed (X9-AMT-422).
type Long struct {
	presence
	Value int64
}

// Int64 is a Long holding v.
func Int64(v int64) Long { return Long{Present: true, Value: v} }

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (l *Long) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*l = Long{Present: true}
	v, null, err := readInteger(dec, math.MinInt64, math.MaxInt64)
	l.Value, l.Null = v, null
	return err
}

// MarshalJSONTo implements json.MarshalerTo.
func (l Long) MarshalJSONTo(enc *jsontext.Encoder) error {
	if l.Null {
		return enc.WriteToken(jsontext.Null)
	}
	return enc.WriteToken(jsontext.Int(l.Value))
}

// Integer is a Java Integer member (openapi.yaml's `type: integer` without `format: int64`): Long's
// rules within int32, so 2147483648 is malformed.
type Integer struct {
	presence
	Value int32
}

// Int32 is an Integer holding v.
func Int32(v int32) Integer { return Integer{Present: true, Value: v} }

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (i *Integer) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*i = Integer{Present: true}
	v, null, err := readInteger(dec, math.MinInt32, math.MaxInt32)
	i.Value, i.Null = int32(v), null //nolint:gosec // readInteger bounded v to int32
	return err
}

// MarshalJSONTo implements json.MarshalerTo.
func (i Integer) MarshalJSONTo(enc *jsontext.Encoder) error {
	if i.Null {
		return enc.WriteToken(jsontext.Null)
	}
	return enc.WriteToken(jsontext.Int(int64(i.Value)))
}

// Boolean is a Java Boolean member. Like Jackson: true or false; an integer (0 is false, any other
// true); the strings "true" and "false" in any of their three casings, trimmed, and "" read as null.
// Anything else is malformed.
type Boolean struct {
	presence
	Value bool
}

// Bool is a Boolean holding v.
func Bool(v bool) Boolean { return Boolean{Present: true, Value: v} }

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (b *Boolean) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*b = Boolean{Present: true}
	tok, err := dec.ReadToken()
	if err != nil {
		return err
	}
	switch tok.Kind() { //nolint:exhaustive // every other JSON kind is the default: malformed
	case 'n':
		b.Null = true
	case 't', 'f':
		b.Value = tok.Bool()
	case '0':
		raw := tok.String()
		if strings.ContainsAny(raw, ".eE") {
			return errMalformed
		}
		b.Value = strings.TrimLeft(strings.TrimPrefix(raw, "-"), "0") != ""
	case '"':
		switch strings.TrimSpace(tok.String()) {
		case "true", "True", "TRUE":
			b.Value = true
		case "false", "False", "FALSE":
		case "":
			b.Null = true
		default:
			return errMalformed
		}
	default:
		return errMalformed
	}
	return nil
}

// MarshalJSONTo implements json.MarshalerTo.
func (b Boolean) MarshalJSONTo(enc *jsontext.Encoder) error {
	if b.Null {
		return enc.WriteToken(jsontext.Null)
	}
	return enc.WriteToken(jsontext.Bool(b.Value))
}

// UUID is a java.util.UUID member (`format: uuid`). Like Jackson: the 36-character dashed form, or
// 16 bytes in Base64; "" is null; anything else is malformed. It is written back dashed, lower case.
type UUID struct {
	presence
	Value uuid.UUID
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (u *UUID) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*u = UUID{Present: true}
	text, null, err := scalarText(dec)
	if err != nil || null {
		u.Null = null
		return err
	}
	text = strings.TrimSpace(text)
	switch len(text) {
	case 0:
		u.Null = true
	case 36:
		for i, c := range text {
			if (i == 8 || i == 13 || i == 18 || i == 23) != (c == '-') {
				return errMalformed
			}
		}
		id, err := uuid.Parse(text)
		if err != nil {
			return errMalformed
		}
		u.Value = id
	case 24:
		raw, err := base64.StdEncoding.DecodeString(text)
		if err != nil || len(raw) != 16 {
			return errMalformed
		}
		copy(u.Value[:], raw)
	default:
		return errMalformed
	}
	return nil
}

// MarshalJSONTo implements json.MarshalerTo.
func (u UUID) MarshalJSONTo(enc *jsontext.Encoder) error {
	if u.Null {
		return enc.WriteToken(jsontext.Null)
	}
	return enc.WriteToken(jsontext.String(u.Value.String()))
}

// URI is a java.net.URI member (`format: uri`): a string that java.net.URI parses. A space, a
// quote, a brace or another character URI refuses, or a bad escape, is malformed, as Jackson
// answers it. "" is a URI (Jackson makes it one), and a relative reference is accepted here, as
// Java accepts it; whether it is absolute enough is the business rules' call.
type URI struct {
	presence
	Value string
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (u *URI) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*u = URI{Present: true}
	text, null, err := scalarText(dec)
	if err != nil || null {
		u.Null = null
		return err
	}
	text = strings.TrimSpace(text)
	if !javaURI(text) {
		return errMalformed
	}
	u.Value = text
	return nil
}

// MarshalJSONTo implements json.MarshalerTo.
func (u URI) MarshalJSONTo(enc *jsontext.Encoder) error {
	if u.Null {
		return enc.WriteToken(jsontext.Null)
	}
	return enc.WriteToken(jsontext.String(u.Value))
}

// readInteger reads Jackson's Long or Integer from the next value.
func readInteger(dec *jsontext.Decoder, lo, hi int64) (v int64, null bool, err error) {
	tok, err := dec.ReadToken()
	if err != nil {
		return 0, false, err
	}
	switch tok.Kind() { //nolint:exhaustive // every other JSON kind is the default: malformed
	case 'n':
		return 0, true, nil
	case '0':
		v, err := integerFromNumber(tok.String(), lo, hi)
		return v, false, err
	case '"':
		text := strings.TrimSpace(tok.String())
		if text == "" {
			return 0, true, nil
		}
		v, err := strconv.ParseInt(text, 10, 64)
		if err != nil || v < lo || v > hi {
			return 0, false, errMalformed
		}
		return v, false, nil
	default:
		return 0, false, errMalformed
	}
}

// integerFromNumber reads a JSON number into an integer within [lo, hi]: exactly when it is written
// as an integer, truncated toward zero when it has a fraction or an exponent (Jackson's
// ACCEPT_FLOAT_AS_INT).
func integerFromNumber(raw string, lo, hi int64) (int64, error) {
	if !strings.ContainsAny(raw, ".eE") {
		v, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || v < lo || v > hi {
			return 0, errMalformed
		}
		return v, nil
	}
	f, err := strconv.ParseFloat(raw, 64)
	if err != nil || math.IsInf(f, 0) || math.IsNaN(f) {
		return 0, errMalformed
	}
	f = math.Trunc(f)
	if f < float64(lo) || f >= float64(hi)+1 {
		return 0, errMalformed
	}
	return int64(f), nil
}

// scalarText reads the next value as Jackson's StringDeserializer does: a string's value, a number's
// text as sent, a boolean's name; null is null; an object or an array is malformed.
func scalarText(dec *jsontext.Decoder) (text string, null bool, err error) {
	tok, err := dec.ReadToken()
	if err != nil {
		return "", false, err
	}
	switch tok.Kind() { //nolint:exhaustive // every other JSON kind is the default: malformed
	case 'n':
		return "", true, nil
	case '"', '0':
		return tok.String(), false, nil
	case 't':
		return "true", false, nil
	case 'f':
		return "false", false, nil
	default:
		return "", false, errMalformed
	}
}

// javaURI reports whether java.net.URI would parse s: only the characters RFC 2396 allows (and the
// non-ASCII ones Java adds), every % followed by two hex digits, at most one #, and a scheme, when
// there is one, that starts with a letter and is followed by something.
func javaURI(s string) bool {
	hashes := 0
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c == '%':
			if i+2 >= len(s) || !isHex(s[i+1]) || !isHex(s[i+2]) {
				return false
			}
		case c == '#':
			hashes++
		case c >= 0x80:
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9':
		case strings.IndexByte(";/?:@&=+$,[]-_.!~*'()", c) >= 0:
		default:
			return false
		}
	}
	if hashes > 1 {
		return false
	}
	end := strings.IndexAny(s, "/?#")
	if end < 0 {
		end = len(s)
	}
	colon := strings.IndexByte(s[:end], ':')
	if colon < 0 {
		return true
	}
	scheme := s[:colon]
	if scheme == "" || !isLetter(scheme[0]) || colon == len(s)-1 {
		return false
	}
	for i := 1; i < len(scheme); i++ {
		c := scheme[i]
		if !isLetter(c) && (c < '0' || c > '9') && c != '+' && c != '-' && c != '.' {
			return false
		}
	}
	return true
}

func isHex(c byte) bool {
	return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'
}

func isLetter(c byte) bool { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' }

// Object is an object member: Present and Null as for a scalar, and Value the object read. A
// repeated key replaces the object, as Jackson replaces it; a value that is not an object is
// malformed.
type Object[T any] struct {
	presence
	Value T
}

// Obj is an Object holding v.
func Obj[T any](v T) Object[T] { return Object[T]{Present: true, Value: v} }

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (o *Object[T]) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*o = Object[T]{Present: true}
	switch dec.PeekKind() { //nolint:exhaustive // every other JSON kind is the default: malformed
	case 'n':
		o.Null = true
		_, err := dec.ReadToken()
		return err
	case '{':
		return json.UnmarshalDecode(dec, &o.Value)
	default:
		return errMalformed
	}
}

// MarshalJSONTo implements json.MarshalerTo.
func (o Object[T]) MarshalJSONTo(enc *jsontext.Encoder) error {
	if o.Null {
		return enc.WriteToken(jsontext.Null)
	}
	return json.MarshalEncode(enc, o.Value)
}

// List is an array member. Absent, it reads as Java's generated default where there is one (an
// empty list, see Items); a value that is not an array is malformed (a bare object included, I-5).
type List[T any] struct {
	presence
	Items []T
}

// Of is a List holding items.
func Of[T any](items ...T) List[T] {
	return List[T]{Present: true, Items: items}
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (l *List[T]) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*l = List[T]{Present: true}
	switch dec.PeekKind() { //nolint:exhaustive // every other JSON kind is the default: malformed
	case 'n':
		l.Null = true
		_, err := dec.ReadToken()
		return err
	case '[':
		l.Items = []T{}
		return json.UnmarshalDecode(dec, &l.Items)
	default:
		return errMalformed
	}
}

// MarshalJSONTo implements json.MarshalerTo.
func (l List[T]) MarshalJSONTo(enc *jsontext.Encoder) error {
	if l.Null {
		return enc.WriteToken(jsontext.Null)
	}
	if l.Items == nil {
		return json.MarshalEncode(enc, []T{})
	}
	return json.MarshalEncode(enc, l.Items)
}
