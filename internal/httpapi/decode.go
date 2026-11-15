// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package httpapi

import (
	"bytes"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"
)

// MaxBodyBytes bounds a JSON request body. The largest legitimate create (every optional member,
// four rails, ten additional-information pairs) is a few kilobytes.
const MaxBodyBytes = 1 << 20

// DecodeJSON reads r's body into dst as the Java build's mapper reads it, and returns the refusal
// Java gives when it cannot:
//
//   - a Content-Type that is not application/json (or application/*+json), or none, is Spring's 415;
//   - a body that is empty, not JSON, not an object, or not the shape dst declares, is a 400
//     malformed-json, as is a number past what its member holds (2^63−1 for an amount, X9-AMT-422).
//
// Keys match case-sensitively (Jackson), unknown keys are ignored unless dst's type captures them,
// a repeated key keeps its last value, a UTF-8 byte-order mark is skipped and anything after the
// first JSON value is ignored, all as Jackson does. dst's member types decide how a scalar of
// another JSON type is read (see internal/contract).
func DecodeJSON(r *http.Request, dst any) error {
	if !isJSON(r.Header.Get("Content-Type")) {
		return UnsupportedMediaType()
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, MaxBodyBytes+1))
	if err != nil || len(body) > MaxBodyBytes {
		return MalformedJSON()
	}
	return Unmarshal(body, dst)
}

// Unmarshal is DecodeJSON on bytes already read: nil, or a *Problem malformed-json.
func Unmarshal(body []byte, dst any) error {
	body = bytes.TrimPrefix(body, []byte("\xef\xbb\xbf"))
	dec := jsontext.NewDecoder(bytes.NewReader(body), jsontext.AllowDuplicateNames(true))
	if dec.PeekKind() != '{' {
		return MalformedJSON()
	}
	if err := json.UnmarshalDecode(dec, dst); err != nil {
		return MalformedJSON()
	}
	return nil
}

// IsMalformed reports whether err is the malformed-json refusal.
func IsMalformed(err error) bool {
	var p *Problem
	return errors.As(err, &p) && p.Type == TypeMalformedJSON
}

// isJSON accepts what Spring's Jackson converter reads: application/json and application/*+json,
// with any parameters.
func isJSON(contentType string) bool {
	media, _, err := mime.ParseMediaType(contentType)
	if err != nil {
		return false
	}
	typ, sub, ok := strings.Cut(media, "/")
	return ok && typ == "application" && (sub == "json" || strings.HasSuffix(sub, "+json"))
}
