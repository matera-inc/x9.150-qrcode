// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	jsonv1 "encoding/json"
	"regexp"
	"strconv"
	"strings"
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

// The words of a refusal, as the Java build writes them (ClosedRequestFields).
const (
	fieldsAddress = "line1, line2, city, state, postalCode and country"
	fieldsParty   = "name, phone, email and address"
	fieldsBill    = "paymentTiming, description, order, invoice, tip and amountDue"
	fieldsMethod  = "currency, validUntil, amount, editable and networks"
	fieldsBank    = "routingNumber, accountNumber and protectionType"
)

// level is one closed object of a body: where it is (a JSON pointer), what a refusal calls it, and
// the fields it lists ("" for an object with one field, which the refusal names as its only one).
type level struct{ pointer, name, fields string }

var createLevels = []level{
	{"", "the payment request", "validUntil, creditor, bill, locationId, unstructured, additionalInformation, paymentNotification and paymentMethods"},
	{"/creditor", "creditor", "name, phone, email, address, ultimateCreditor and MCC"},
	{"/creditor/address", "address", fieldsAddress},
	{"/creditor/ultimateCreditor", "ultimateCreditor", "account, name, phone, email and address"},
	{"/creditor/ultimateCreditor/account", "account", "id and schemaName"},
	{"/creditor/ultimateCreditor/address", "address", fieldsAddress},
	{"/bill", "bill", fieldsBill},
	{"/bill/order", "order", "number and date"},
	{"/bill/invoice", "invoice", "number, date, dueDate and invoicee"},
	{"/bill/invoice/invoicee", "invoicee", fieldsParty},
	{"/bill/invoice/invoicee/address", "address", fieldsAddress},
	{"/bill/tip", "tip", "allowed, range and presets"},
	{"/bill/tip/range", "range", "min and max"},
	{"/bill/amountDue", "amountDue", "amount, currency and adjustments"},
	{"/bill/amountDue/adjustments", "adjustments", "formula and parameters"},
	{"/bill/amountDue/adjustments/parameters", "parameters", "discounts and lateFees"},
	{"/bill/amountDue/adjustments/parameters/discounts/0", "a discount", "daysBefore, discount and explanation"},
	{"/bill/amountDue/adjustments/parameters/lateFees", "lateFees", "fixed, perDay and explanation"},
	{"/additionalInformation/0", "an additionalInformation entry", "key and value"},
	{"/paymentNotification", "paymentNotification", "kind and endpoint"},
	{"/paymentMethods/0", "a payment method", fieldsMethod},
	{"/paymentMethods/0/editable", "editable", ""},
	{"/paymentMethods/0/editable/range", "range", "min and max"},
	{"/paymentMethods/0/networks/fednow", "fednow", fieldsBank},
	{"/paymentMethods/1/networks/solana", "solana", "recipient and memo"},
}

var patchLevels = []level{
	{"", "the payment request", "locationId, validUntil, bill, unstructured, additionalInformation and paymentMethods"},
	{"/bill", "bill", fieldsBill},
	{"/bill/order", "order", "number and date"},
	{"/bill/invoice", "invoice", "number, date, dueDate and invoicee"},
	{"/bill/invoice/invoicee", "invoicee", fieldsParty},
	{"/bill/invoice/invoicee/address", "address", fieldsAddress},
	{"/bill/tip", "tip", "allowed, range and presets"},
	{"/bill/tip/range", "range", "min and max"},
	{"/bill/amountDue", "amountDue", "amount and adjustments"},
	{"/bill/amountDue/adjustments", "adjustments", "formula and parameters"},
	{"/bill/amountDue/adjustments/parameters", "parameters", "discounts and lateFees"},
	{"/bill/amountDue/adjustments/parameters/discounts/0", "a discount", "daysBefore, discount and explanation"},
	{"/bill/amountDue/adjustments/parameters/lateFees", "lateFees", "fixed, perDay and explanation"},
	{"/additionalInformation/0", "an additionalInformation entry", "key and value"},
	{"/paymentMethods/0", "a payment method", fieldsMethod},
	{"/paymentMethods/0/editable", "editable", ""},
	{"/paymentMethods/0/editable/range", "range", "min and max"},
	{"/paymentMethods/0/networks/ach", "ach", fieldsBank},
	{"/paymentMethods/1/networks/solana", "solana", "recipient and memo"},
}

var outboundLevels = []level{
	{"", "the outbound notification", "endpoint, correlationId and notification"},
	{"/notification", "notification", "payment, payer, expectedDate and blockchain"},
	{"/notification/payment", "payment", "qrcodeId, amount, tipAmount, currency, network and transactionId"},
	{"/notification/payer", "payer", ""},
	{"/notification/blockchain", "blockchain", "action, from and to"},
}

var arrayIndex = regexp.MustCompile(`/(\d+)`)

// want is the violation an unknown field "zzUnknown" at l gets.
func (l level) want() string {
	path := "zzUnknown"
	if l.pointer != "" {
		path = strings.ReplaceAll(arrayIndex.ReplaceAllString(l.pointer[1:], "[$1]"), "/", ".") + ".zzUnknown"
	}
	if l.fields == "" {
		only := map[string]string{"editable": "range", "payer": "info"}[l.name]
		return path + ": is not a field of " + l.name + ", whose only field is " + only
	}
	return path + ": is not a field of " + l.name + ", whose fields are " + l.fields
}

// withUnknown is sample with "zzUnknown": true added to the object at pointer.
func withUnknown(t *testing.T, sample, pointer string) []byte {
	t.Helper()
	dec := jsonv1.NewDecoder(strings.NewReader(sample))
	dec.UseNumber()
	var body any
	if err := dec.Decode(&body); err != nil {
		t.Fatal(err)
	}
	node := body
	for _, step := range strings.Split(pointer, "/")[1:] {
		if i, err := strconv.Atoi(step); err == nil {
			node = node.([]any)[i]
		} else {
			node = node.(map[string]any)[step]
		}
	}
	node.(map[string]any)["zzUnknown"] = true
	raw, err := jsonv1.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

// INTERPRETATION I-16 (owner, 2026-11-15): what this service generates is closed at every level.
// An unknown field anywhere in a create, a PATCH or an outbound payment notification is refused,
// naming its path and the fields the object has, in the Java build's words (X9-RAIL-058, -059,
// -065); networks alone stays open, to refuse an unknown network by name.
func TestAnUnknownFieldIsRefusedAtEveryLevel(t *testing.T) {
	for _, op := range []struct {
		name, sample string
		levels       []level
		target       func() validator
	}{
		{"create", sampleCreate, createLevels, func() validator { return &CreateRequest{} }},
		{"patch", samplePatch, patchLevels, func() validator { return &PatchRequest{} }},
		{"outbound", sampleOutbound, outboundLevels, func() validator { return &OutboundNotification{} }},
	} {
		for _, l := range op.levels {
			t.Run(op.name+":"+l.name+":"+l.pointer, func(t *testing.T) {
				target := op.target()
				if err := httpapi.Unmarshal(withUnknown(t, op.sample, l.pointer), target); err != nil {
					t.Fatal(err)
				}
				if diff := cmp.Diff([]string{l.want()}, target.Validate()); diff != "" {
					t.Errorf("violations (-want +got):\n%s", diff)
				}
			})
		}
	}
}

// Reading is tolerant (I-16): a payment notification read on its own, as a payee reads another
// bank's, keeps an unknown field aside and never refuses it; only the outbound request, which this
// service signs and sends, is closed.
func TestANotificationReadOnItsOwnIsNotClosed(t *testing.T) {
	outbound := withUnknown(t, sampleOutbound, "/notification/payment")
	var r OutboundNotification
	if err := httpapi.Unmarshal(outbound, &r); err != nil {
		t.Fatal(err)
	}
	read := r.Notification.Value
	if names := read.Payment.Value.Unknown.Names(); !cmp.Equal(names, []string{"zzUnknown"}) {
		t.Fatalf("the unknown member is not kept aside: %v", names)
	}
	if v := read.Validate(); v != nil {
		t.Errorf("a notification read on its own refused an unknown field: %q", v)
	}
}

// A null where a closed object goes reads as before: the object's members are each null.
func TestANullItemOfAClosedListReadsAsAnEmptyObject(t *testing.T) {
	var r CreateRequest
	body := replace(t, sampleCreate, `"additionalInformation": [{"key": "Note", "value": "contract"}]`,
		`"additionalInformation": [null]`)
	if err := httpapi.Unmarshal([]byte(body), &r); err != nil {
		t.Fatal(err)
	}
	want := []string{"additionalInformation[0].key: must not be null", "additionalInformation[0].value: must not be null"}
	if diff := cmp.Diff(want, r.Validate()); diff != "" {
		t.Errorf("violations (-want +got):\n%s", diff)
	}
}
