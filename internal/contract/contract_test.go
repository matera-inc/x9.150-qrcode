// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"encoding/json/v2"
	"strings"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

// replace returns the create sample with old replaced by new, once.
func replace(t *testing.T, sample, old, with string) string {
	t.Helper()
	if strings.Count(sample, old) < 1 {
		t.Fatalf("the sample has no %q", old)
	}
	return strings.Replace(sample, old, with, 1)
}

const pmAmount = `"amount": 1000,
     "editable"`

// S9.2-AC2: a member of a request is read as Jackson reads the Java field. Each expected outcome
// was taken from the Java build's answer to the same body.
func TestScalarsAreReadAsJacksonReadsThem(t *testing.T) {
	for _, tc := range []struct {
		name, old, new string
		malformed      bool
		check          func(*CreateRequest) bool
	}{
		{name: "an integer amount", old: pmAmount, new: `"amount": 1000, "editable"`,
			check: func(r *CreateRequest) bool { return r.PaymentMethods.Items[0].Amount.Value == 1000 }},
		{name: "an amount as a string", old: pmAmount, new: `"amount": "1000", "editable"`,
			check: func(r *CreateRequest) bool { return r.PaymentMethods.Items[0].Amount.Value == 1000 }},
		{name: "an amount as a padded string", old: pmAmount, new: `"amount": " 1000", "editable"`,
			check: func(r *CreateRequest) bool { return r.PaymentMethods.Items[0].Amount.Value == 1000 }},
		{name: "a fractional amount is truncated", old: pmAmount, new: `"amount": 1000.7, "editable"`,
			check: func(r *CreateRequest) bool { return r.PaymentMethods.Items[0].Amount.Value == 1000 }},
		{name: "an amount with an exponent", old: pmAmount, new: `"amount": 1e3, "editable"`,
			check: func(r *CreateRequest) bool { return r.PaymentMethods.Items[0].Amount.Value == 1000 }},
		{name: "an empty string amount is null", old: pmAmount, new: `"amount": "", "editable"`,
			check: func(r *CreateRequest) bool { return r.PaymentMethods.Items[0].Amount.IsNull() }},
		{name: "2^63-1 is read", old: pmAmount, new: `"amount": 9223372036854775807, "editable"`,
			check: func(r *CreateRequest) bool { return r.PaymentMethods.Items[0].Amount.Value == 1<<63-1 }},
		// X9-AMT-422: past 2^63−1 the body is unreadable.
		{name: "2^63 is malformed (X9-AMT-422)", old: pmAmount, new: `"amount": 9223372036854775808, "editable"`, malformed: true},
		{name: "19 nines are malformed (X9-AMT-422)", old: pmAmount, new: `"amount": 9999999999999999999, "editable"`, malformed: true},
		{name: "a boolean amount is malformed", old: pmAmount, new: `"amount": true, "editable"`, malformed: true},
		{name: "an array amount is malformed", old: pmAmount, new: `"amount": [1000], "editable"`, malformed: true},
		{name: "an int32 past its range is malformed", old: `"min": 0, "max": 25`, new: `"min": 2147483648, "max": 25`, malformed: true},
		{name: "an int32 fraction is truncated", old: `"min": 0, "max": 25`, new: `"min": 1.5, "max": 25`,
			check: func(r *CreateRequest) bool { return r.Bill.Value.Tip.Value.Range.Value.Min.Value == 1 }},
		{name: "a number where a string is expected is its text", old: `"MCC": "4900"`, new: `"MCC": 4900`,
			check: func(r *CreateRequest) bool { return r.Creditor.Value.MCC.Value == "4900" }},
		{name: "a boolean where a string is expected is its name", old: `"unstructured": "blackbox"`, new: `"unstructured": true`,
			check: func(r *CreateRequest) bool { return r.Unstructured.Value == "true" }},
		{name: "an object where a string is expected is malformed", old: `"MCC": "4900"`, new: `"MCC": {}`, malformed: true},
		{name: "allowed 1 is true", old: `"allowed": true`, new: `"allowed": 1`,
			check: func(r *CreateRequest) bool { return r.Bill.Value.Tip.Value.Allowed.Value }},
		{name: `allowed "true" is true`, old: `"allowed": true`, new: `"allowed": "true"`,
			check: func(r *CreateRequest) bool { return r.Bill.Value.Tip.Value.Allowed.Value }},
		{name: `allowed "yes" is malformed`, old: `"allowed": true`, new: `"allowed": "yes"`, malformed: true},
		{name: "a timestamp with an offset is malformed", old: `"validUntil": "2030-12-31T23:59:59Z",
  "creditor"`, new: `"validUntil": "2030-12-31T23:59:59+00:00", "creditor"`, malformed: true},
		{name: "a timestamp with four fractional digits is malformed", old: `"validUntil": "2030-12-31T23:59:59Z",
  "creditor"`, new: `"validUntil": "2030-12-31T23:59:59.1234Z", "creditor"`, malformed: true},
		{name: "a timestamp with one fractional digit", old: `"validUntil": "2030-12-31T23:59:59Z",
  "creditor"`, new: `"validUntil": "2030-12-31T23:59:59.5Z", "creditor"`,
			check: func(r *CreateRequest) bool {
				return r.ValidUntil.Value.Equal(time.Date(2030, 12, 31, 23, 59, 59, 5e8, time.UTC))
			}},
		{name: "a date-only timestamp is malformed", old: `"validUntil": "2030-12-31T23:59:59Z",
  "creditor"`, new: `"validUntil": "2030-12-31", "creditor"`, malformed: true},
		{name: "a bad date is malformed", old: `"date": "2030-01-10"}`, new: `"date": "2030-1-10"}`, malformed: true},
		{name: "a date-time is read as its date", old: `"date": "2030-01-10"}`, new: `"date": "2030-01-10T00:00:00Z"}`,
			check: func(r *CreateRequest) bool { return r.Bill.Value.Order.Value.Date.Value.Day() == 10 }},
		{name: "a URI with a space is malformed", old: `"https://payee.example.com/notify"`, new: `"http://a b"`, malformed: true},
		{name: "a relative URI is read", old: `"https://payee.example.com/notify"`, new: `"abc"`,
			check: func(r *CreateRequest) bool { return r.PaymentNotification.Value.Endpoint.Value == "abc" }},
		{name: "an enum value in another case is malformed", old: `"kind": "DEFAULT"`, new: `"kind": "default"`, malformed: true},
		{name: "paymentTiming is read in any case", old: `"paymentTiming": "immediate"`, new: `"paymentTiming": "DEFERRED"`,
			check: func(r *CreateRequest) bool { return r.Bill.Value.PaymentTiming.Value == "deferred" }},
		{name: "paymentTiming outside its values is malformed", old: `"paymentTiming": "immediate"`, new: `"paymentTiming": "later"`, malformed: true},
		{name: "paymentMethods as a bare object is malformed (I-5)", old: `"paymentMethods": [`, new: `"paymentMethods": {"x": [`, malformed: true},
		// Keys match case-sensitively: a key in another case is an unknown key, ignored.
		{name: "a key in another case is not the member", old: `"MCC": "4900"`, new: `"mcc": "4900"`,
			check: func(r *CreateRequest) bool { return r.Creditor.Value.MCC.IsNull() }},
		{name: "an unknown key is ignored", old: `"MCC": "4900"`, new: `"MCC": "4900", "zzz": {"a": [1]}`,
			check: func(r *CreateRequest) bool { return r.Creditor.Value.MCC.Value == "4900" }},
		{name: "a repeated key keeps its last value", old: `"MCC": "4900"`, new: `"MCC": "1111", "MCC": "4900"`,
			check: func(r *CreateRequest) bool { return r.Creditor.Value.MCC.Value == "4900" }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var r CreateRequest
			err := httpapi.Unmarshal([]byte(replace(t, sampleCreate, tc.old, tc.new)), &r)
			if tc.malformed {
				if !httpapi.IsMalformed(err) {
					t.Fatalf("got %v, want malformed-json", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if !tc.check(&r) {
				t.Errorf("read wrongly: %+v", r)
			}
		})
	}
}

// S9.2-AC5: the violations carry Java's field paths and Bean Validation's default messages, word for
// word as the Java build answered the same bodies.
func TestViolationsAreJavas(t *testing.T) {
	for _, tc := range []struct {
		name, old, new string
		want           []string
	}{
		{"required", `"MCC": "4900"`, `"x": 1`, []string{"creditor.MCC: must not be null"}},
		{"pattern and size", `"name": "Blackbox Co"`, `"name": "` + strings.Repeat("B", 51) + `"`,
			[]string{`creditor.name: must match "^[\x20-\x7E]{1,50}$"`, "creditor.name: size must be between 0 and 50"}},
		{"phone", `"phone": "+15552223333", "email": "party@example.com",
    "address"`, `"phone": "x", "address"`,
			[]string{`creditor.phone: must match "^(\+1[2-9]\d{2}[2-9]\d{6}|\+\d{1,3}\d{4,14})$"`}},
		{"email", `"email": "party@example.com",
    "address"`, `"email": "a b@c.d", "address"`, []string{"creditor.email: must be a well-formed email address"}},
		{"email that @Email lets through", `"email": "party@example.com",
    "address"`, `"email": "a@b", "address"`, nil},
		{"country", `"country": "US"},
    "MCC"`, `"country": "USA"}, "MCC"`,
			[]string{`creditor.address.country: must match "^[A-Z]{2}$"`, "creditor.address.country: size must be between 2 and 2"}},
		{"amount", pmAmount, `"amount": 1000000000000000000, "editable"`,
			[]string{"paymentMethods[0].amount: must be less than or equal to 999999999999999999"}},
		{"tip range", `"min": 0, "max": 25`, `"min": -1, "max": 1000`,
			[]string{"bill.tip.range.min: must be greater than or equal to 0", "bill.tip.range.max: must be less than or equal to 999"}},
		{"presets", `"presets": [10, 15]`, `"presets": [0,1,2,3,4,5,6,7,8,9,1000]`,
			[]string{"bill.tip.presets: size must be between 0 and 10", "bill.tip.presets[10]: must be less than or equal to 999"}},
		{"explicit null on a defaulted member", `"allowed": true`, `"allowed": null`, []string{"bill.tip.allowed: must not be null"}},
		{"absent defaulted member", `"paymentTiming": "immediate",`, ``, nil},
		{"locationId dashed", `"locationId": "0123456789ABCDEF0123456789ABCDEF"`,
			`"locationId": "01234567-89ab-cdef-0123-456789abcdef"`, []string{"locationId: size must be between 32 and 32"}},
		{"paymentMethods empty", `"paymentMethods": [`, `"paymentMethods": [], "x": [`,
			[]string{"paymentMethods: size must be between 1 and 2147483647"}},
		{"paymentMethods null", `"paymentMethods": [`, `"paymentMethods": null, "x": [`,
			[]string{"paymentMethods: must not be null"}},
		{"recipient", `"recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"`, `"walletAddress": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"`,
			[]string{"paymentMethods[1].networks.solana.recipient: must not be null",
				"paymentMethods[1].networks.solana.walletAddress: is not a field of solana, whose fields are recipient and memo"}},
		// R-I9-03: an interpreted rail stays closed.
		{"an unknown field in solana (R-I9-03)", `"memo": "blackbox"`, `"memo": "blackbox", "x": 1`,
			[]string{"paymentMethods[1].networks.solana.x: is not a field of solana, whose fields are recipient and memo"}},
		{"an unknown network is kept for the business rules", `"networks": {"solana"`, `"networks": {"Solana": {}, "tron": 1, "solana"`, nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var r CreateRequest
			if err := httpapi.Unmarshal([]byte(replace(t, sampleCreate, tc.old, tc.new)), &r); err != nil {
				t.Fatal(err)
			}
			if diff := cmp.Diff(tc.want, r.Validate()); diff != "" {
				t.Errorf("violations (-want +got):\n%s", diff)
			}
		})
	}
	var r CreateRequest
	if err := httpapi.Unmarshal([]byte(replace(t, sampleCreate, `"networks": {"solana"`, `"networks": {"Solana": {}, "tron": 1, "solana"`)), &r); err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff([]string{"Solana", "tron"}, r.PaymentMethods.Items[1].Networks.Value.OtherNames()); diff != "" {
		t.Errorf("unknown networks, in order (-want +got):\n%s", diff)
	}
	var empty CreateRequest
	if err := httpapi.Unmarshal([]byte(`{}`), &empty); err != nil {
		t.Fatal(err)
	}
	want := []string{"validUntil: must not be null", "creditor: must not be null", "bill: must not be null",
		"paymentMethods: size must be between 1 and 2147483647"}
	if diff := cmp.Diff(want, empty.Validate()); diff != "" {
		t.Errorf("an empty create (-want +got):\n%s", diff)
	}
}

// S9.2-AC2: a PATCH knows which members it carried and which it cleared (ADR-0024).
func TestAPatchTracksWhatItCarried(t *testing.T) {
	var p PatchRequest
	body := `{"unstructured": null, "bill": {"paymentTiming": "immediate", "description": "d",
		"amountDue": {"amount": 5}, "tip": null}, "paymentMethods": []}`
	if err := httpapi.Unmarshal([]byte(body), &p); err != nil {
		t.Fatal(err)
	}
	got := map[string][2]bool{
		"locationId":   {p.LocationID.Present, p.LocationID.Null},
		"validUntil":   {p.ValidUntil.Present, p.ValidUntil.Null},
		"unstructured": {p.Unstructured.Present, p.Unstructured.Null},
		"bill":         {p.Bill.Present, p.Bill.Null},
		"bill.tip":     {p.Bill.Value.Tip.Present, p.Bill.Value.Tip.Null},
		"bill.order":   {p.Bill.Value.Order.Present, p.Bill.Value.Order.Null},
	}
	want := map[string][2]bool{
		"locationId": {false, false}, "validUntil": {false, false}, "unstructured": {true, true},
		"bill": {true, false}, "bill.tip": {true, true}, "bill.order": {false, false},
	}
	if diff := cmp.Diff(want, got); diff != "" {
		t.Errorf("present/null (-want +got):\n%s", diff)
	}
	// PatchBill has no default for paymentTiming; PatchTip's presets hold at least one value.
	var q PatchRequest
	if err := httpapi.Unmarshal([]byte(`{"bill": {"description": "d", "amountDue": {"amount": 5},
		"tip": {"presets": []}}, "paymentMethods": []}`), &q); err != nil {
		t.Fatal(err)
	}
	wantQ := []string{"bill.paymentTiming: must not be null", "bill.tip.presets: size must be between 1 and 10",
		"paymentMethods: size must be between 1 and 2147483647"}
	if diff := cmp.Diff(wantQ, q.Validate()); diff != "" {
		t.Errorf("(-want +got):\n%s", diff)
	}
}

// S9.2-AC2: the type decoded is the type stored. Written back, a body has exactly the keys it was
// sent with, nulls kept, each value in its canonical form (a coerced "1000" is the number 1000).
func TestARequestIsWrittenBackAsItWasRead(t *testing.T) {
	in := `{"validUntil":"2030-12-31T23:59:59.500Z","unstructured":null,"bill":{"description":"d",` +
		`"amountDue":{"amount":"1000","currency":"USD"},"tip":{"presets":[]}},"paymentMethods":[` +
		`{"currency":"USD","validUntil":"2030-12-31T23:59:59Z","amount":0,"networks":{"tron":{"a":1},` +
		`"solana":{"recipient":"9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM","x":true}}}]}`
	var r CreateRequest
	if err := httpapi.Unmarshal([]byte(in), &r); err != nil {
		t.Fatal(err)
	}
	out, err := json.Marshal(&r)
	if err != nil {
		t.Fatal(err)
	}
	want := `{"validUntil":"2030-12-31T23:59:59.500Z","bill":{"description":"d","tip":{"presets":[]},` +
		`"amountDue":{"amount":1000,"currency":"USD"}},"unstructured":null,"paymentMethods":[` +
		`{"currency":"USD","validUntil":"2030-12-31T23:59:59Z","amount":0,"networks":{"solana":` +
		`{"recipient":"9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM","x":true},"tron":{"a":1}}}]}`
	if string(out) != want {
		t.Errorf("written back as\n%s\nwant\n%s", out, want)
	}
}

func TestTimestampsFollowJavasResolver(t *testing.T) {
	for in, want := range map[string]string{
		"2030-02-30T10:00:00Z":     "2030-02-28T10:00:00Z", // SMART: a day past the month's end is its last
		"2030-12-31T24:00:00Z":     "2031-01-01T00:00:00Z",
		"2030-12-31T23:59:59.1Z":   "2030-12-31T23:59:59.100Z",
		"2030-12-31T23:59:59.000Z": "2030-12-31T23:59:59Z",
		"2030-12-32T10:00:00Z":     "",
		"2030-13-01T10:00:00Z":     "",
		"2030-12-31T23:60:00Z":     "",
		"2030-12-31T23:59Z":        "",
		"2030-12-31T23:59:59z":     "",
	} {
		v, ok := ParseTimestamp(in)
		got := ""
		if ok {
			got = FormatInstant(v)
		}
		if got != want {
			t.Errorf("%s: got %q, want %q", in, got, want)
		}
	}
}

// FuzzRequestReader: no body makes the reader or the checks panic.
func FuzzRequestReader(f *testing.F) {
	for _, op := range operations {
		f.Add([]byte(op.sample))
	}
	f.Add([]byte(`{"paymentMethods":[{"amount":1e400,"networks":{"solana":{"x":[{}]}}}]}`))
	f.Fuzz(func(t *testing.T, body []byte) {
		for _, op := range operations {
			target := op.target()
			if httpapi.Unmarshal(body, target) == nil {
				_ = target.Validate()
				if _, err := json.Marshal(target); err != nil {
					t.Fatalf("%s: a body read cannot be written back: %v", op.name, err)
				}
			}
		}
	})
}
