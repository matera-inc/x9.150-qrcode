// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

// S9.2-AC5: the Go counterpart of others/blackbox/contract.py. It reads openapi.yaml, generates one
// case for every constraint of a request schema (required, minLength, maxLength, pattern, minimum,
// maximum and their exclusive forms, enum, minItems, maxItems, format: email, and the generator's
// @NotNull extension), breaks exactly that one member of a full sample, and fails unless the Go
// build refuses it: a violation naming the member's path, or, for an enum, an unreadable body
// (malformed-json, as the Java build answers it). Each numeric bound also gets its edge, which must
// not be refused. A constraint added to the contract without a check here fails this test.

import (
	"bytes"
	jsonv1 "encoding/json"
	"errors"
	"fmt"
	"maps"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"testing"

	"go.yaml.in/yaml/v3"

	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

var openapiPath = filepath.Join("..", "..", "x9-qrcode-infrastructure", "src", "main", "resources", "apis", "openapi.yaml")

// validator is a request type: read by httpapi.Unmarshal, checked by Validate.
type validator interface{ Validate() []string }

// operation is a request schema, the Go type that reads it, and the sample a case breaks.
type operation struct {
	name, schema, sample string
	target               func() validator
}

var operations = []operation{
	{"create", "PaymentRequestInput", sampleCreate, func() validator { return &CreateRequest{} }},
	{"patch", "PatchPaymentRequestReplacement", samplePatch, func() validator { return &PatchRequest{} }},
	{"status-update", "StatusUpdate", sampleStatusUpdate, func() validator { return &StatusUpdate{} }},
	{"pre-payment", "OutboundPaymentNotification", sampleOutbound, func() validator { return &OutboundNotification{} }},
	{"signature-validate", "SignatureValidationRequest", sampleSignatureValidation,
		func() validator { return &SignatureValidation{} }},
	{"emv-decoder", "QRCodeEmvDecoder", sampleEmvDecoder, func() validator { return &EmvDecoder{} }},
}

type contractCase struct {
	op        *operation
	path      []string // "[]" for an array's items
	kind      string
	limit     any
	pattern   string
	minLength *int
	maxLength *int
	exclusive bool
}

func (c contractCase) field() string {
	return strings.ReplaceAll(strings.Join(c.path, "."), ".[]", "[]")
}

func (c contractCase) id() string { return c.op.name + ":" + c.field() + ":" + c.kind }

// edge reports whether the case is a value that must be accepted.
func (c contractCase) edge() bool { return strings.HasSuffix(c.kind, "-accepted") }

type spec map[string]any

func loadSpec(t *testing.T) spec {
	t.Helper()
	raw, err := os.ReadFile(openapiPath) //nolint:gosec // a fixed path into this repository
	if err != nil {
		t.Fatal(err)
	}
	var s map[string]any // not spec: yaml would make every nested mapping a spec too
	if err := yaml.Unmarshal(raw, &s); err != nil {
		t.Fatal(err)
	}
	return s
}

func (s spec) schemas() map[string]any {
	return s["components"].(map[string]any)["schemas"].(map[string]any)
}

func (s spec) resolve(schema map[string]any) map[string]any {
	for {
		ref, ok := schema["$ref"].(string)
		if !ok {
			return schema
		}
		schema = s.schemas()[ref[strings.LastIndex(ref, "/")+1:]].(map[string]any)
	}
}

// merged folds allOf parts into the schema, as contract.py does.
func (s spec) merged(schema map[string]any) map[string]any {
	schema = s.resolve(schema)
	parts, ok := schema["allOf"].([]any)
	if !ok {
		return schema
	}
	out := map[string]any{}
	props := map[string]any{}
	var required []any
	for k, v := range schema {
		switch k {
		case "allOf":
		case "properties":
			maps.Copy(props, v.(map[string]any))
		case "required":
			required = append(required, v.([]any)...)
		default:
			out[k] = v
		}
	}
	for _, p := range parts {
		part := s.merged(p.(map[string]any))
		for k, v := range part {
			switch k {
			case "properties":
				for name, prop := range v.(map[string]any) {
					if _, ok := props[name]; !ok {
						props[name] = prop
					}
				}
			case "required":
				for _, r := range v.([]any) {
					if !slices.Contains(required, r) {
						required = append(required, r)
					}
				}
			default:
				if _, ok := out[k]; !ok {
					out[k] = v
				}
			}
		}
	}
	if len(props) > 0 {
		out["properties"] = props
	}
	if len(required) > 0 {
		out["required"] = required
	}
	return out
}

func intPtr(v any) *int {
	if n, ok := v.(int); ok {
		return &n
	}
	return nil
}

func (s spec) walk(schema map[string]any, path []string, op *operation, out *[]contractCase, depth int) {
	if depth > 15 {
		return
	}
	schema = s.merged(schema)
	at := slices.Clone(path)
	add := func(c contractCase) {
		c.op, c.path = op, at
		*out = append(*out, c)
	}
	pat, _ := schema["pattern"].(string)
	for _, kind := range []string{"maxLength", "minLength", "pattern"} {
		if v, ok := schema[kind]; ok {
			add(contractCase{kind: kind, limit: v, pattern: pat, minLength: intPtr(schema["minLength"]),
				maxLength: intPtr(schema["maxLength"])})
		}
	}
	if typ, _ := schema["type"].(string); typ == "integer" || typ == "number" {
		for _, kind := range []string{"maximum", "minimum"} {
			if v, ok := schema[kind]; ok {
				exclusive, _ := schema["exclusive"+strings.ToUpper(kind[:1])+kind[1:]].(bool)
				add(contractCase{kind: kind, limit: v, exclusive: exclusive})
				add(contractCase{kind: kind + "-accepted", limit: v, exclusive: exclusive})
			}
		}
	}
	if values, ok := schema["enum"].([]any); ok && len(path) > 0 {
		add(contractCase{kind: "enum", limit: values})
	}
	if f, _ := schema["format"].(string); f == "email" {
		add(contractCase{kind: "email"})
	}
	if closed, ok := schema["additionalProperties"].(bool); ok && !closed {
		c := contractCase{kind: "closed"}
		c.op, c.path = op, append(slices.Clone(path), "zzUnknown")
		*out = append(*out, c)
	}
	if extra, _ := schema["x-field-extra-annotation"].(string); strings.Contains(extra, "@NotNull") {
		add(contractCase{kind: "notNull"})
	}
	if typ, _ := schema["type"].(string); typ == "array" {
		for _, kind := range []string{"minItems", "maxItems"} {
			if v, ok := schema[kind]; ok {
				add(contractCase{kind: kind, limit: v})
			}
		}
	}
	if required, ok := schema["required"].([]any); ok {
		for _, name := range required {
			c := contractCase{kind: "required"}
			c.op, c.path = op, append(slices.Clone(path), name.(string))
			*out = append(*out, c)
		}
	}
	if props, ok := schema["properties"].(map[string]any); ok {
		for _, name := range slices.Sorted(maps.Keys(props)) {
			s.walk(props[name].(map[string]any), append(slices.Clone(path), name), op, out, depth+1)
		}
	}
	if items, ok := schema["items"].(map[string]any); ok {
		s.walk(items, append(slices.Clone(path), "[]"), op, out, depth+1)
	}
}

func (s spec) cases() []contractCase {
	var out []contractCase
	for i := range operations {
		op := &operations[i]
		s.walk(s.schemas()[op.schema].(map[string]any), nil, op, &out, 0)
	}
	seen := map[string]bool{}
	unique := out[:0]
	for _, c := range out {
		if len(c.path) > 0 && !seen[c.id()] {
			seen[c.id()] = true
			unique = append(unique, c)
		}
	}
	return unique
}

var errUnbuildable = errors.New("unbuildable")

// build breaks the case's member in a copy of the sample, and returns the body and the path the
// refusal must name (with real indices).
func (c contractCase) build() ([]byte, string, error) {
	dec := jsonv1.NewDecoder(strings.NewReader(c.op.sample))
	dec.UseNumber()
	var body any
	if err := dec.Decode(&body); err != nil {
		return nil, "", err
	}
	parent, key, named, err := locate(body, c.path, nil)
	if err != nil {
		return nil, "", err
	}
	get := func() any {
		if list, ok := parent.([]any); ok {
			return list[key.(int)]
		}
		return parent.(map[string]any)[key.(string)]
	}
	set := func(v any) {
		if list, ok := parent.([]any); ok {
			list[key.(int)] = v
			return
		}
		parent.(map[string]any)[key.(string)] = v
	}
	switch c.kind {
	case "required":
		m, ok := parent.(map[string]any)
		if !ok {
			return nil, "", errUnbuildable
		}
		if _, present := m[key.(string)]; !present {
			return nil, "", fmt.Errorf("%w: %s is absent from the sample", errUnbuildable, named)
		}
		delete(m, key.(string))
	case "notNull":
		set(nil)
	case "closed":
		set(true) // a key the closed schema does not declare
	case "maximum", "minimum", "maximum-accepted", "minimum-accepted":
		set(jsonv1.Number(strconv.FormatInt(c.number(), 10)))
	case "enum":
		set("NOT_A_VALUE_OF_THIS_ENUM")
	case "email":
		set("not-an-email")
	case "minItems", "maxItems":
		list, ok := get().([]any)
		if !ok || len(list) == 0 {
			return nil, "", fmt.Errorf("%w: %s is not a non-empty array in the sample", errUnbuildable, named)
		}
		if c.kind == "minItems" {
			set(list[:c.limit.(int)-1])
		} else {
			grown := slices.Clone(list)
			for len(grown) <= c.limit.(int) {
				grown = append(grown, list[0])
			}
			set(grown)
		}
	default:
		current, _ := get().(string)
		v, err := c.stringValue(current)
		if err != nil {
			return nil, "", err
		}
		set(v)
	}
	raw, err := jsonv1.Marshal(body)
	return raw, named, err
}

// number is one past the bound (the bound itself when exclusive), or, for an edge, the bound
// itself (one inside it when exclusive).
func (c contractCase) number() int64 {
	limit := int64(c.limit.(int))
	step := int64(1)
	if strings.HasPrefix(c.kind, "minimum") {
		step = -1
	}
	switch {
	case c.edge() && c.exclusive:
		return limit - step
	case c.edge():
		return limit
	case c.exclusive:
		return limit
	default:
		return limit + step
	}
}

func (c contractCase) stringValue(seed string) (string, error) {
	filler := "A"
	if seed != "" {
		r := []rune(seed)
		filler = string(r[len(r)-1])
	}
	n := c.limit
	switch c.kind {
	case "maxLength":
		v := []rune(seed + strings.Repeat(filler, n.(int)+1))
		return string(v[:n.(int)+1]), nil
	case "minLength":
		if n.(int) <= 0 {
			return "", fmt.Errorf("%w: minLength 0 cannot be violated", errUnbuildable)
		}
		base := seed
		if base == "" {
			base = strings.Repeat(filler, n.(int))
		}
		return string([]rune(base)[:n.(int)-1]), nil
	case "pattern":
		return violating(c.pattern, c.minLength, c.maxLength, seed)
	}
	return "", fmt.Errorf("%w: kind %s", errUnbuildable, c.kind)
}

// violating is a value of an allowed length that the pattern refuses, so the pattern is what fires.
func violating(pattern string, minLength, maxLength *int, seed string) (string, error) {
	re := regexp.MustCompile(`\A(?:` + strings.TrimSuffix(strings.TrimPrefix(pattern, "^"), "$") + `)\z`)
	length := max(len([]rune(seed)), 1)
	if minLength != nil {
		length = max(length, *minLength)
	}
	if maxLength != nil {
		length = min(length, *maxLength)
	}
	for _, ch := range []string{"é", "!", "-", " ", "a", "0", "Z", "+"} {
		if v := strings.Repeat(ch, length); !re.MatchString(v) {
			return v, nil
		}
	}
	return "", fmt.Errorf("%w: no candidate violates %s", errUnbuildable, pattern)
}

// locate walks path in body, choosing for "[]" the first element where the rest of the path exists.
func locate(node any, path []string, named []string) (parent any, key any, name string, err error) {
	join := func(parts []string) string { return strings.Join(parts, ".") }
	indexed := func(i int) []string {
		out := slices.Clone(named)
		out[len(out)-1] += "[" + strconv.Itoa(i) + "]"
		return out
	}
	if len(path) == 1 && path[0] == "[]" {
		list, ok := node.([]any)
		if !ok || len(list) == 0 {
			return nil, nil, "", fmt.Errorf("%w: no element at %s", errUnbuildable, join(named))
		}
		return list, 0, join(indexed(0)), nil
	}
	if len(path) == 1 {
		m, ok := node.(map[string]any)
		if !ok {
			return nil, nil, "", fmt.Errorf("%w: %s is not an object", errUnbuildable, join(named))
		}
		return m, path[0], join(append(slices.Clone(named), path[0])), nil
	}
	step, rest := path[0], path[1:]
	if step == "[]" {
		list, ok := node.([]any)
		if !ok {
			return nil, nil, "", fmt.Errorf("%w: no array at %s", errUnbuildable, join(named))
		}
		for i, element := range list {
			if p, k, n, err := locate(element, rest, indexed(i)); err == nil {
				return p, k, n, nil
			}
		}
		return nil, nil, "", fmt.Errorf("%w: no element of %s has %s", errUnbuildable, join(named), join(rest))
	}
	m, ok := node.(map[string]any)
	if !ok || m[step] == nil {
		return nil, nil, "", fmt.Errorf("%w: the sample has no %s", errUnbuildable,
			join(append(slices.Clone(named), step)))
	}
	return locate(m[step], rest, append(slices.Clone(named), step))
}

// run sends the case through the Go build's reader and checks, and returns why it was not answered
// as the contract asks, or nil.
func (c contractCase) run() error {
	body, named, err := c.build()
	if err != nil {
		return err
	}
	target := c.op.target()
	decodeErr := httpapi.Unmarshal(body, target)
	if c.kind == "enum" {
		if !httpapi.IsMalformed(decodeErr) {
			return fmt.Errorf("a value outside the enum is not refused as malformed-json (got %w)", decodeErr)
		}
		return nil
	}
	if decodeErr != nil {
		return fmt.Errorf("the body is unreadable (%w): %s", decodeErr, body)
	}
	violations := target.Validate()
	naming := slices.ContainsFunc(violations, func(v string) bool { return strings.HasPrefix(v, named+": ") })
	switch {
	case c.edge() && naming:
		return fmt.Errorf("the bound itself is refused: %q", violations)
	case !c.edge() && !naming:
		return fmt.Errorf("no check: nothing refuses it naming %s (violations %q)", named, violations)
	}
	return nil
}

func TestEveryRequestConstraintOfTheContractHasACheck(t *testing.T) {
	cases := loadSpec(t).cases()
	kinds := map[string]int{}
	for _, c := range cases {
		kinds[c.kind]++
		t.Run(c.id(), func(t *testing.T) {
			if err := c.run(); err != nil {
				t.Error(err)
			}
		})
	}
	// Every kind of constraint the contract uses today is generated (a walker that stopped
	// generating one would pass vacuously).
	for _, kind := range []string{"required", "maxLength", "minLength", "pattern", "maximum", "minimum",
		"maximum-accepted", "minimum-accepted", "enum", "email", "notNull", "minItems", "maxItems", "closed"} {
		if kinds[kind] == 0 {
			t.Errorf("no %s case was generated from openapi.yaml", kind)
		}
	}
	t.Logf("%d contract cases generated and run: %v", len(cases), kinds)
}

// A constraint added to the contract gets its case, and that case fails until a check is written.
func TestANewConstraintWithoutACheckFails(t *testing.T) {
	s := loadSpec(t)
	probe := map[string]any{"type": "string", "maxLength": 3}
	exclusive := map[string]any{"type": "integer", "maximum": 10, "exclusiveMaximum": true}
	status := s.schemas()["StatusUpdate"].(map[string]any)
	props := status["properties"].(map[string]any)
	props["network"] = map[string]any{"type": "string", "maxLength": 64}
	props["probe"], props["probeBound"] = probe, exclusive

	found := 0
	for _, c := range s.cases() {
		switch c.field() {
		case "probe":
			found++
			if err := c.run(); err == nil || !strings.Contains(err.Error(), "no check") {
				t.Errorf("%s: a constraint with no check passed: %v", c.id(), err)
			}
		case "probeBound":
			found++
			body, _, err := c.build()
			if err != nil {
				t.Fatal(err)
			}
			want := map[string]string{"maximum": `"probeBound":10`, "maximum-accepted": `"probeBound":9`}[c.kind]
			if !bytes.Contains(body, []byte(want)) {
				t.Errorf("%s: an exclusive bound is built as %s, want %s", c.id(), body, want)
			}
		}
	}
	if found != 3 {
		t.Errorf("found %d cases for the probes, want 3 (maxLength, maximum, maximum-accepted)", found)
	}
}

// The control: every unbroken sample is read and passes every check, so no case passes because the
// sample was already refused.
func TestTheSamplesPassTheContract(t *testing.T) {
	for _, op := range operations {
		target := op.target()
		if err := httpapi.Unmarshal([]byte(op.sample), target); err != nil {
			t.Fatalf("%s: %v", op.name, err)
		}
		if v := target.Validate(); v != nil {
			t.Errorf("%s: the sample is refused: %q", op.name, v)
		}
	}
}
