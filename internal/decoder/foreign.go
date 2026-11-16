// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package decoder

import (
	"bytes"
	"encoding/json/jsontext"
	"errors"
	"strconv"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
)

// Reading another bank's payload: strict to generate, tolerant to read (S10.7-AC5, INTERPRETATION
// I-9 and I-16).
//
// The payload is projected onto PaymentPayloadResponse, member by member in the contract's order:
//
//   - a member the contract declares is presented; one it does not declare is dropped, at every
//     level, and never refused;
//   - inside networks, the rails this service interprets (fednow, rtp, ach, solana) are read as
//     their contract declares them, an unknown field inside one dropped; any other network is
//     carried intact, its value exactly as written, whatever its shape (I-9);
//   - no value is judged against a rule of what this deployment generates: a currency outside
//     supported-currencies.json, a status or protectionType it would not write, an id of another
//     length, are presented as written;
//   - a member whose JSON type is not the contract's (a string where a number is declared, an
//     object where a string is) has nothing this service can present, and is dropped like an
//     unknown one, as is a null (Jackson's NON_NULL); a list left empty is not written, as the Java
//     build's DecoderResponseMapper does not write one;
//   - a date-time the X9.150 format reads is written as this service writes an instant; one it
//     does not read is presented as written;
//   - one value is added: bill.paymentTiming "immediate" when the bill has neither it nor an
//     invoice.dueDate, as the standard requires the member and our contract documents its default
//     (withPaymentTiming says why).
//
// The only refusal is a payload that is not a JSON object: there is nothing in it to present.
//
// internal/contract's request types are not used here: they keep an unknown member aside so that
// the create and PATCH checks can refuse it (I-16), which is the opposite of what reading needs.

// errNotObject is a payload that is not a JSON object.
var errNotObject = errors.New("the payload is not a JSON object")

// kind is how a member is read.
type kind uint8

const (
	kString kind = iota
	kInt
	kBool
	kInstant // format: date-time
	kDate    // format: date
	kObject
	kObjects  // an array of objects
	kInts     // an array of integers
	kNetworks // PayloadNetworks: known rails typed, any other carried intact
)

// member is a declared member: its name, how it is read and, for an object or a list of objects,
// the object's own members.
type member struct {
	name   string
	kind   kind
	fields []member
}

func str(name string) member                   { return member{name: name, kind: kString} }
func integer(name string) member               { return member{name: name, kind: kInt} }
func instant(name string) member               { return member{name: name, kind: kInstant} }
func object(name string, ms ...member) member  { return member{name: name, kind: kObject, fields: ms} }
func objects(name string, ms ...member) member { return member{name: name, kind: kObjects, fields: ms} }

// The payload side of openapi.yaml: PaymentPayloadResponse and its Payload* objects, in the order
// the Java build's generated classes write their members.
var (
	payloadAddress = []member{str("line1"), str("line2"), str("city"), str("state"), str("postalCode"), str("country")}
	integerRange   = []member{integer("min"), integer("max")}

	payloadCreditor = []member{
		str("name"), str("phone"), str("email"),
		object("address", payloadAddress...),
		object("ultimateCreditor",
			object("account", str("id"), str("schemaName")),
			str("name"), str("phone"), str("email"),
			object("address", payloadAddress...)),
		str("MCC"),
	}

	payloadBill = []member{
		object("amountDue",
			integer("amount"), str("currency"),
			objects("adjustment", str("explanation"), integer("amount"), instant("validUntil"))),
		str("paymentTiming"),
		str("description"),
		object("order", str("number"), member{name: "date", kind: kDate}),
		object("invoice",
			str("number"), member{name: "date", kind: kDate}, instant("dueDate"),
			object("invoicee", str("name"), str("phone"), str("email"), object("address", payloadAddress...))),
		object("tip",
			member{name: "allowed", kind: kBool},
			object("range", integerRange...),
			member{name: "presets", kind: kInts}),
	}

	payloadMethod = []member{
		str("currency"),
		instant("validUntil"),
		integer("amount"),
		object("editable", object("range", integerRange...)),
		{name: "networks", kind: kNetworks},
	}

	paymentPayloadResponse = []member{
		str("id"),
		integer("revision"),
		instant("createdAt"),
		instant("revisedAt"),
		instant("sentAt"),
		instant("validUntil"),
		str("status"),
		object("creditor", payloadCreditor...),
		object("bill", payloadBill...),
		str("unstructured"),
		objects("additionalInformation", str("key"), str("value")),
		str("paymentNotification"),
		objects("paymentMethods", payloadMethod...),
	}

	bankAddress = []member{str("routingNumber"), str("accountNumber"), str("protectionType")}

	// knownRails are the networks this service interprets (PayloadFedNow, PayloadRTP, PayloadACH,
	// PayloadSolana), in the order they are written; any other network follows them, as sent.
	knownRails = []member{
		object("fednow", bankAddress...),
		object("rtp", bankAddress...),
		object("ach", bankAddress...),
		object("solana", str("recipient"), str("memo")),
	}
)

// readForeign is the decoder's answer for a payload another bank (or this one) signed.
func readForeign(payload []byte) ([]byte, error) {
	top, ok, err := members(payload)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errNotObject
	}
	if bill, sent := top.values["bill"]; sent {
		if top.values["bill"], err = withPaymentTiming(bill); err != nil {
			return nil, err
		}
	}
	var buf bytes.Buffer
	enc := jsontext.NewEncoder(&buf)
	if err := writeObject(enc, top, paymentPayloadResponse); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}

// immediate is bill.paymentTiming's value when another bank's payload leaves it out.
var immediate = jsontext.Value(`"immediate"`)

// withPaymentTiming is the bill as it is presented: paymentTiming "immediate" is added when the
// other bank sent none (or null, or a value that is not a string) and the bill has no
// invoice.dueDate (owner decision, 2026-11-16).
//
// Why a value is added here, by a decoder that otherwise presents only what the other bank wrote:
// ANSI X9.150-2026 §13.1 makes bill.paymentTiming mandatory ("SHALL be present"). "immediate" means
// due when the QR Code is scanned; "deferred" means due at a future date, and then
// bill.invoice.dueDate SHALL be present. Our openapi.yaml promises that paymentTiming is always
// present in a payload response, and that it defaults to "immediate" on create. So when another
// bank leaves it out, the decoder presents "immediate": the only safe reading, since a deferred
// bill needs a due date the payer could defer to. It does so only when there is no dueDate: with
// one, the bill may be meant as deferred, the intent is ambiguous, and we do not guess, so
// paymentTiming stays absent.
//
// This does not break "strict to generate, tolerant to read" (INTERPRETATION I-16): that rule is
// about not refusing a payload for vocabulary we do not know. This fills a field the standard
// requires, with the default our contract documents. The Java build applies the same rule
// (DecoderResponseMapper), so both builds present the same bill.
func withPaymentTiming(raw jsontext.Value) (jsontext.Value, error) {
	bill, ok, err := members(raw)
	if err != nil || !ok {
		return raw, err // not an object: writeMember drops it
	}
	timing, sent := bill.values["paymentTiming"]
	if sent && timing.Kind() == '"' {
		return raw, nil
	}
	if invoice, hasInvoice := bill.values["invoice"]; hasInvoice {
		inv, isObject, err := members(invoice)
		if err != nil {
			return nil, err
		}
		if due, hasDue := inv.values["dueDate"]; isObject && hasDue && due.Kind() != 'n' {
			return raw, nil
		}
	}
	if !sent {
		bill.names = append(bill.names, "paymentTiming")
	}
	bill.values["paymentTiming"] = immediate
	var buf bytes.Buffer
	enc := jsontext.NewEncoder(&buf)
	if err := enc.WriteToken(jsontext.BeginObject); err != nil {
		return nil, err
	}
	for _, name := range bill.names {
		if err := enc.WriteToken(jsontext.String(name)); err != nil {
			return nil, err
		}
		if err := enc.WriteValue(bill.values[name]); err != nil {
			return nil, err
		}
	}
	if err := enc.WriteToken(jsontext.EndObject); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}

// fields are an object's members as sent: its names in order, and each one's last value (Jackson
// keeps the last of a repeated key, at the place it first appeared).
type fields struct {
	names  []string
	values map[string]jsontext.Value
}

// members reads raw as an object; ok is false when it is another JSON value.
func members(raw []byte) (fields, bool, error) {
	dec := jsontext.NewDecoder(bytes.NewReader(raw), jsontext.AllowDuplicateNames(true))
	if dec.PeekKind() != '{' {
		_, err := dec.ReadValue() // a malformed payload is an error, another value is not an object
		return fields{}, false, err
	}
	if _, err := dec.ReadToken(); err != nil {
		return fields{}, false, err
	}
	f := fields{values: map[string]jsontext.Value{}}
	for dec.PeekKind() != '}' {
		tok, err := dec.ReadToken()
		if err != nil {
			return fields{}, false, err
		}
		name := tok.String()
		value, err := dec.ReadValue()
		if err != nil {
			return fields{}, false, err
		}
		if _, seen := f.values[name]; !seen {
			f.names = append(f.names, name)
		}
		f.values[name] = value.Clone()
	}
	if _, err := dec.ReadToken(); err != nil {
		return fields{}, false, err
	}
	return f, true, nil
}

// writeObject writes the declared members of obj that can be presented, in the declared order.
func writeObject(enc *jsontext.Encoder, obj fields, declared []member) error {
	if err := enc.WriteToken(jsontext.BeginObject); err != nil {
		return err
	}
	for _, m := range declared {
		raw, ok := obj.values[m.name]
		if !ok {
			continue
		}
		if err := writeMember(enc, m, raw); err != nil {
			return err
		}
	}
	return enc.WriteToken(jsontext.EndObject)
}

// writeMember writes m with its value, or nothing when the value cannot be presented as m.
func writeMember(enc *jsontext.Encoder, m member, raw jsontext.Value) error {
	value, ok, err := present(m, raw)
	if err != nil || !ok {
		return err
	}
	if err := enc.WriteToken(jsontext.String(m.name)); err != nil {
		return err
	}
	return enc.WriteValue(value)
}

// present is raw as m presents it, or false when it cannot be.
func present(m member, raw jsontext.Value) (jsontext.Value, bool, error) {
	switch m.kind {
	case kString:
		return raw, raw.Kind() == '"', nil
	case kInt:
		return raw, isInt(raw), nil
	case kBool:
		return raw, raw.Kind() == 't' || raw.Kind() == 'f', nil
	case kInstant:
		return dated(raw, func(s string) (string, bool) {
			t, ok := contract.ParseTimestamp(s)
			return contract.FormatInstant(t), ok
		})
	case kDate:
		return dated(raw, func(s string) (string, bool) {
			t, ok := contract.ParseDate(s)
			return t.Format(time.DateOnly), ok
		})
	case kObject:
		return nested(raw, func(enc *jsontext.Encoder, obj fields) error { return writeObject(enc, obj, m.fields) })
	case kObjects:
		return list(raw, func(enc *jsontext.Encoder, item jsontext.Value) (bool, error) {
			obj, ok, err := members(item)
			if err != nil || !ok {
				return false, err
			}
			return true, writeObject(enc, obj, m.fields)
		})
	case kInts:
		return list(raw, func(enc *jsontext.Encoder, item jsontext.Value) (bool, error) {
			if !isInt(item) {
				return false, nil
			}
			return true, enc.WriteValue(item)
		})
	case kNetworks:
		return nested(raw, writeNetworks)
	}
	return nil, false, nil
}

// writeNetworks writes the rails this service interprets, each as declared, then every other
// network exactly as written (INTERPRETATION I-9: "ignore" means do not interpret, not discard).
func writeNetworks(enc *jsontext.Encoder, networks fields) error {
	if err := enc.WriteToken(jsontext.BeginObject); err != nil {
		return err
	}
	known := make(map[string]bool, len(knownRails))
	for _, rail := range knownRails {
		known[rail.name] = true
		if raw, ok := networks.values[rail.name]; ok {
			if err := writeMember(enc, rail, raw); err != nil {
				return err
			}
		}
	}
	for _, name := range networks.names {
		if known[name] {
			continue
		}
		if err := enc.WriteToken(jsontext.String(name)); err != nil {
			return err
		}
		if err := enc.WriteValue(networks.values[name]); err != nil {
			return err
		}
	}
	return enc.WriteToken(jsontext.EndObject)
}

// isInt reports whether raw is a JSON number an int64 holds exactly.
func isInt(raw jsontext.Value) bool {
	if raw.Kind() != '0' {
		return false
	}
	_, err := strconv.ParseInt(string(raw), 10, 64)
	return err == nil
}

// dated is a date string written as this service writes it, or as sent when format cannot read it.
func dated(raw jsontext.Value, format func(string) (string, bool)) (jsontext.Value, bool, error) {
	if raw.Kind() != '"' {
		return nil, false, nil
	}

	dec := jsontext.NewDecoder(bytes.NewReader(raw))
	tok, err := dec.ReadToken()
	if err != nil {
		return nil, false, err
	}
	s := tok.String()
	written, ok := format(s)
	if !ok {
		return raw, true, nil
	}
	v, err := jsontext.AppendQuote(nil, written)
	return v, err == nil, err
}

// nested is an object value written by write, or false when raw is not an object.
func nested(raw jsontext.Value, write func(*jsontext.Encoder, fields) error) (jsontext.Value, bool, error) {
	obj, ok, err := members(raw)
	if err != nil || !ok {
		return nil, false, err
	}
	var buf bytes.Buffer
	enc := jsontext.NewEncoder(&buf)
	if err := write(enc, obj); err != nil {
		return nil, false, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), true, nil
}

// list is an array written item by item, the items write declines left out; false when raw is not
// an array, or no item is left (the Java build writes no empty list).
func list(raw jsontext.Value, write func(*jsontext.Encoder, jsontext.Value) (bool, error)) (jsontext.Value, bool, error) {
	if raw.Kind() != '[' {
		return nil, false, nil
	}
	dec := jsontext.NewDecoder(bytes.NewReader(raw), jsontext.AllowDuplicateNames(true))
	if _, err := dec.ReadToken(); err != nil {
		return nil, false, err
	}
	var buf bytes.Buffer
	enc := jsontext.NewEncoder(&buf)
	if err := enc.WriteToken(jsontext.BeginArray); err != nil {
		return nil, false, err
	}
	written := 0
	for dec.PeekKind() != ']' {
		item, err := dec.ReadValue()
		if err != nil {
			return nil, false, err
		}
		ok, err := write(enc, item.Clone())
		if err != nil {
			return nil, false, err
		}
		if ok {
			written++
		}
	}
	if err := enc.WriteToken(jsontext.EndArray); err != nil {
		return nil, false, err
	}
	if written == 0 {
		return nil, false, nil
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), true, nil
}
