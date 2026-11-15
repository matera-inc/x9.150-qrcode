// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package httpapi

import (
	"bytes"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"fmt"
	"maps"
	"net/http"
	"slices"
	"strconv"
	"time"
)

// ContentTypeJSON is what the Java build writes on a JSON answer: no charset parameter.
const ContentTypeJSON = "application/json"

// Marshal writes v as the Java build's mapper would, whitespace aside (tolerated difference 1).
//
// The NON_NULL rule (ADR-0028) is a property of the types, not of this function: a value is omitted
// only when it is null. Declare an optional member as a pointer, a slice, a map or a type with an
// IsZero method, tagged `omitzero`, and never `omitempty`: omitzero omits a nil pointer, a nil slice
// and a nil map and keeps 0, false, "" and an empty slice, which is exactly Jackson's NON_NULL.
// omitempty would also drop an empty list and an empty string, which Jackson keeps. A required member carries no omit option at all. Amounts are int64 and are written
// as JSON numbers (X9.150 §2.1). HTML characters are not escaped, as Jackson does not escape them.
func Marshal(v any) ([]byte, error) {
	return json.Marshal(v, json.Deterministic(true))
}

// WriteJSON writes v with status as application/json.
func WriteJSON(w http.ResponseWriter, status int, v any) error {
	body, err := Marshal(v)
	if err != nil {
		return fmt.Errorf("writing the answer: %w", err)
	}
	w.Header().Set("Content-Type", ContentTypeJSON)
	w.WriteHeader(status)
	_, err = w.Write(body)
	return err
}

// SetETag sets the ETag header to tag, quoted, as Spring's ResponseEntity.eTag writes it. tag is
// what ETag(row, now) builds, such as 0-ACTIVE.
func SetETag(w http.ResponseWriter, tag string) {
	w.Header().Set("ETag", strconv.Quote(tag))
}

// WriteProblem writes p as application/problem+json. instance is the request's path, as Spring
// fills it.
func WriteProblem(w http.ResponseWriter, r *http.Request, p *Problem) {
	body := problemBody(p, r.URL.Path, false)
	w.Header().Set("Content-Type", ContentTypeProblem)
	w.WriteHeader(p.Status)
	_, _ = w.Write(body)
}

// AnswerBody is p as the signed body of a refused payment notification (INTERPRETATION I-12):
// statusCode first, then the problem's members as WriteProblem writes them, without instance, which
// the Java build signs before Spring would fill it.
func AnswerBody(p *Problem) []byte { return problemBody(p, "", true) }

// problemBody encodes p with its members in the Java build's order: type, title, status, detail,
// instance, then the extension members; statusCode first when the body is a signed answer's.
func problemBody(p *Problem, instance string, statusCode bool) []byte {
	var buf bytes.Buffer
	enc := jsontext.NewEncoder(&buf)
	put := func(tokens ...jsontext.Token) {
		for _, t := range tokens {
			_ = enc.WriteToken(t)
		}
	}
	put(jsontext.BeginObject)
	if statusCode {
		put(jsontext.String("statusCode"), jsontext.Int(int64(p.Status)))
	}
	if p.Type != "" {
		put(jsontext.String("type"), jsontext.String(p.Type))
	}
	if p.Title != "" {
		put(jsontext.String("title"), jsontext.String(p.Title))
	}
	put(jsontext.String("status"), jsontext.Int(int64(p.Status)))
	if p.Detail != "" {
		put(jsontext.String("detail"), jsontext.String(p.Detail))
	}
	if instance != "" {
		put(jsontext.String("instance"), jsontext.String(instance))
	}
	if p.CurrentStatus != "" {
		put(jsontext.String("currentStatus"), jsontext.String(p.CurrentStatus))
	}
	if p.CurrentRevision != nil {
		put(jsontext.String("currentRevision"), jsontext.Int(*p.CurrentRevision))
	}
	if p.SignatureProblem != "" {
		put(jsontext.String("signatureProblem"), jsontext.String(p.SignatureProblem))
	}
	if p.Violations != nil {
		put(jsontext.String("violations"), jsontext.BeginArray)
		for _, v := range p.Violations {
			put(jsontext.String(v))
		}
		put(jsontext.EndArray)
	}
	for _, k := range slices.Sorted(mapKeys(p.Extra)) {
		value, err := json.Marshal(p.Extra[k], json.Deterministic(true))
		if err != nil {
			continue
		}
		put(jsontext.String(k))
		_ = enc.WriteValue(value)
	}
	put(jsontext.EndObject)
	return bytes.TrimRight(buf.Bytes(), "\n")
}

func mapKeys(m map[string]any) func(func(string) bool) {
	return func(yield func(string) bool) {
		for k := range m {
			if !yield(k) {
				return
			}
		}
	}
}

// springError is the body Spring Boot's BasicErrorController writes for an answer no controller
// gave: an unknown path, a wrong method, an unsupported media type.
type springError struct {
	status int
	header http.Header // headers Spring sets with it: Allow, Accept
}

func (e *springError) Error() string { return http.StatusText(e.status) }

// SpringError is an error answered with Spring Boot's own error body, as the Java build answers
// what no controller handles. header holds the headers Spring adds (Allow on a 405, Accept on a 415).
func SpringError(status int, header http.Header) error {
	return &springError{status: status, header: header}
}

// UnsupportedMediaType is the 415 of a body whose Content-Type is not JSON, as Spring answers it.
func UnsupportedMediaType() error {
	return SpringError(http.StatusUnsupportedMediaType, http.Header{"Accept": {ContentTypeJSON}})
}

// writeSpringError writes Spring Boot's error body:
// {"timestamp":"2026-11-15T13:58:00.403+00:00","status":404,"error":"Not Found","path":"/x"}.
func writeSpringError(w http.ResponseWriter, r *http.Request, e *springError, now time.Time) {
	maps.Copy(w.Header(), e.header)
	var buf bytes.Buffer
	enc := jsontext.NewEncoder(&buf)
	for _, t := range []jsontext.Token{
		jsontext.BeginObject,
		jsontext.String("timestamp"), jsontext.String(now.UTC().Format("2006-01-02T15:04:05.000-07:00")),
		jsontext.String("status"), jsontext.Int(int64(e.status)),
		jsontext.String("error"), jsontext.String(http.StatusText(e.status)),
		jsontext.String("path"), jsontext.String(r.URL.Path),
		jsontext.EndObject,
	} {
		_ = enc.WriteToken(t)
	}
	w.Header().Set("Content-Type", ContentTypeJSON)
	w.WriteHeader(e.status)
	if r.Method != http.MethodHead {
		_, _ = w.Write(bytes.TrimRight(buf.Bytes(), "\n"))
	}
}
