// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package signature

import (
	"bytes"
	"encoding/binary"
	"encoding/json/jsontext"
	"errors"
	"io"
	"math"
	"net/http"
	"strconv"
	"strings"
	"unicode"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

// correlationIDHeader reads Correlation-Id as Spring binds it to a UUID: absent, or blank, is a
// missing header; otherwise UUID.fromString of the trimmed value, or "has invalid value".
func correlationIDHeader(h http.Header) (uuid.UUID, error) {
	values := h.Values(HeaderCorrelationID)
	if len(values) == 0 {
		return uuid.Nil, httpapi.MissingHeader(HeaderCorrelationID, "UUID")
	}
	v := strings.TrimSpace(values[0])
	if v == "" {
		return uuid.Nil, convertedToNull(HeaderCorrelationID, "UUID")
	}
	id, ok := javaUUID(v)
	if !ok {
		return uuid.Nil, httpapi.Validation(HeaderCorrelationID + " has invalid value.")
	}
	return id, nil
}

// ttlHeader reads TTL-Seconds as Spring binds it to an Integer: absent or empty is a missing
// header; otherwise Spring's NumberUtils.parseNumber, or "has invalid value".
func ttlHeader(h http.Header) (int64, error) {
	values := h.Values(HeaderTTLSeconds)
	if len(values) == 0 {
		return 0, httpapi.MissingHeader(HeaderTTLSeconds, "Integer")
	}
	if values[0] == "" {
		return 0, convertedToNull(HeaderTTLSeconds, "Integer")
	}
	n, ok := javaInteger(values[0])
	if !ok {
		return 0, httpapi.Validation(HeaderTTLSeconds + " has invalid value.")
	}
	return n, nil
}

// convertedToNull is Spring's refusal of a required header that is sent but converts to null.
func convertedToNull(name, javaType string) error {
	return httpapi.HeaderValidation("Required request header '" + name + "' for method parameter type " + javaType +
		" is present but converted to null")
}

// javaUUID is java.util.UUID.fromString: five hexadecimal fields separated by four dashes, the
// whole at most 36 characters. Each field is read as a long in base 16 (a leading + is allowed) and
// keeps only its low 32, 16, 16, 16 and 48 bits, so 1-2-3-4-5 is 00000001-0002-0003-0004-000000000005.
func javaUUID(s string) (uuid.UUID, bool) {
	if len(s) > 36 {
		return uuid.Nil, false
	}
	fields := strings.Split(s, "-")
	if len(fields) != 5 {
		return uuid.Nil, false
	}
	var parts [5]uint64
	for i, f := range fields {
		v, err := strconv.ParseInt(f, 16, 64)
		if err != nil { // an empty field, a non-hexadecimal digit, or past a long
			return uuid.Nil, false
		}
		parts[i] = uint64(v) //nolint:gosec // reinterpreted as Java's long, then masked as Java masks it
	}
	msb := (parts[0]&0xffffffff)<<32 | (parts[1]&0xffff)<<16 | parts[2]&0xffff
	lsb := (parts[3]&0xffff)<<48 | parts[4]&0xffffffffffff
	var id uuid.UUID
	binary.BigEndian.PutUint64(id[:8], msb)
	binary.BigEndian.PutUint64(id[8:], lsb)
	return id, true
}

// javaInteger is Spring's NumberUtils.parseNumber(text, Integer.class): every whitespace character
// removed, then Integer.decode when the value is hexadecimal (0x, 0X or #, after an optional -),
// otherwise Integer.valueOf, in decimal with an optional sign. The result must fit an int.
func javaInteger(text string) (int64, bool) {
	s := strings.Map(func(r rune) rune {
		if unicode.IsSpace(r) {
			return -1
		}
		return r
	}, text)
	body, negative := s, false
	if rest, ok := strings.CutPrefix(body, "-"); ok {
		body, negative = rest, true
	}
	var digits string
	switch {
	case strings.HasPrefix(body, "0x"), strings.HasPrefix(body, "0X"):
		digits = body[2:]
	case strings.HasPrefix(body, "#"):
		digits = body[1:]
	default:
		n, err := strconv.ParseInt(s, 10, 32)
		return n, err == nil
	}
	if digits == "" || digits[0] == '+' || digits[0] == '-' {
		return 0, false // Integer.decode: "Sign character in wrong position"
	}
	n, err := strconv.ParseInt(digits, 16, 64)
	if err != nil {
		return 0, false
	}
	if negative {
		n = -n
	}
	return n, n >= math.MinInt32 && n <= math.MaxInt32
}

// errNotObject is a body whose first JSON value is not an object.
var errNotObject = errors.New("signature: the body is not a JSON object")

// normalize is the payload the Java build signs for a generate body: the first JSON value, an
// object, as Jackson reads it into a Map and writes it back. A repeated member keeps its first
// position and its last value, and a member whose value is null is dropped, at every depth; null
// array elements stay. Numbers and strings are kept as sent; the whitespace is not (tolerated
// difference 1).
func normalize(raw []byte) ([]byte, error) {
	dec := jsontext.NewDecoder(bytes.NewReader(raw), jsontext.AllowDuplicateNames(true))
	if dec.PeekKind() != jsontext.KindBeginObject {
		return nil, errNotObject
	}
	n, err := readNode(dec)
	if err != nil {
		return nil, err
	}
	var out bytes.Buffer
	enc := jsontext.NewEncoder(&out)
	if err := n.write(enc); err != nil {
		return nil, err
	}
	return bytes.TrimRight(out.Bytes(), "\n"), nil
}

// node is one JSON value: an object (names and members, in order), an array, or a scalar.
type node struct {
	kind    jsontext.Kind
	names   []string
	members map[string]*node
	items   []*node
	scalar  jsontext.Value
}

func readNode(dec *jsontext.Decoder) (*node, error) {
	switch kind := dec.PeekKind(); kind {
	case jsontext.KindBeginObject:
		if _, err := dec.ReadToken(); err != nil {
			return nil, err
		}
		n := &node{kind: kind, members: map[string]*node{}}
		for dec.PeekKind() != jsontext.KindEndObject {
			tok, err := dec.ReadToken()
			if err != nil {
				return nil, err
			}
			name := tok.String()
			v, err := readNode(dec)
			if err != nil {
				return nil, err
			}
			if _, seen := n.members[name]; !seen {
				n.names = append(n.names, name)
			}
			n.members[name] = v
		}
		_, err := dec.ReadToken()
		return n, err
	case jsontext.KindBeginArray:
		if _, err := dec.ReadToken(); err != nil {
			return nil, err
		}
		n := &node{kind: kind}
		for dec.PeekKind() != jsontext.KindEndArray {
			v, err := readNode(dec)
			if err != nil {
				return nil, err
			}
			n.items = append(n.items, v)
		}
		_, err := dec.ReadToken()
		return n, err
	case jsontext.KindNull, jsontext.KindFalse, jsontext.KindTrue, jsontext.KindString, jsontext.KindNumber:
		v, err := dec.ReadValue()
		if err != nil {
			return nil, err
		}
		return &node{kind: kind, scalar: v.Clone()}, nil
	case jsontext.KindInvalid, jsontext.KindEndObject, jsontext.KindEndArray:
		// No value here: ReadToken says why.
		_, err := dec.ReadToken()
		if err == nil {
			err = io.ErrUnexpectedEOF
		}
		return nil, err
	}
	return nil, errNotObject
}

func (n *node) write(enc *jsontext.Encoder) error {
	switch n.kind {
	case jsontext.KindBeginObject:
		if err := enc.WriteToken(jsontext.BeginObject); err != nil {
			return err
		}
		for _, name := range n.names {
			v, ok := n.members[name]
			if !ok || v == nil || v.kind == jsontext.KindNull {
				continue // Jackson's NON_NULL
			}
			if err := enc.WriteToken(jsontext.String(name)); err != nil {
				return err
			}
			if err := v.write(enc); err != nil {
				return err
			}
		}
		return enc.WriteToken(jsontext.EndObject)
	case jsontext.KindBeginArray:
		if err := enc.WriteToken(jsontext.BeginArray); err != nil {
			return err
		}
		for _, v := range n.items {
			if err := v.write(enc); err != nil {
				return err
			}
		}
		return enc.WriteToken(jsontext.EndArray)
	case jsontext.KindNull, jsontext.KindFalse, jsontext.KindTrue, jsontext.KindString, jsontext.KindNumber:
		return enc.WriteValue(n.scalar)
	case jsontext.KindInvalid, jsontext.KindEndObject, jsontext.KindEndArray:
	}
	return errNotObject
}
