// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package decoder

import (
	"strings"
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

// foreignPayload is another bank's payload: every member the contract declares, in another order,
// beside fields, a rail and currencies this deployment does not know (the black-box suite's
// test_tolerant_reading.py, and the Java build's ForeignPayloadReadWithToleranceApiTest and
// OpaqueNetworkOnDecodeApiTest, in one).
const foreignPayload = `{
  "bankExtension": {"channel": "branch", "flags": [1, 2]},
  "status": "ACTIVE",
  "id": "0196cb82afab46f1bf8dfff2f9c8a540",
  "revision": 2,
  "createdAt": "2030-01-01T00:00:00.000Z",
  "revisedAt": "2030-01-01T00:00:00.5Z",
  "sentAt": "2030-01-01T00:00:00+00:00",
  "validUntil": "2030-12-31T23:59:59Z",
  "creditor": {"name": "Banco Exemplo", "loyaltyId": "L-1", "MCC": "5999",
    "address": {"line1": "Rua A", "county": "X", "city": "Sao Paulo", "country": "BR"}},
  "bill": {
    "description": "foreign",
    "amountDue": {"amount": 1250, "currency": "USD", "adjustment": [], "rate": 1},
    "paymentTiming": "someday",
    "tip": {"allowed": true, "presets": [10, "x", 25.5, 50], "range": {"min": 0, "max": 20, "step": 5}},
    "order": {"number": "PO-1", "date": "2030-01-02"},
    "invoice": {"number": "INV-1", "date": "2030-01-02", "dueDate": "2030-02-01T00:00:00Z",
      "invoicee": {"name": "Payer", "taxId": "1"}}
  },
  "additionalInformation": [],
  "unstructured": 42,
  "paymentNotification": "https://other-bank.example/notify",
  "paymentMethods": [
    {"currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1250, "priority": 1,
     "editable": {"range": {"min": 1000, "max": 2000}},
     "networks": {
       "zelle": {"token": "loja@example.com"},
       "ach": {"routingNumber": "021000021", "accountNumber": "1234567890",
               "protectionType": "plaintext", "accountType": "checking"},
       "someFutureChain": {"addr": "zzz", "tag": 99, "nested": {"a": [1, 2.50, 3e2]}}}},
    {"currency": "BRL", "validUntil": "2030-12-31T23:59:59Z", "amount": 6500,
     "networks": {"pix": {"pixKey": "loja@example.com", "txid": "ABC123"}}},
    {"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 12500000,
     "networks": {"solana": {"recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "referenceKeys": ["abc"]},
                  "fednow": "not an object", "tron": null}},
    "not a method",
    {"currency": "EUR", "amount": "12", "validUntil": null, "networks": []}
  ]
}`

// foreignPresented is foreignPayload as the decoder answers it.
const foreignPresented = `{
  "id": "0196cb82afab46f1bf8dfff2f9c8a540",
  "revision": 2,
  "createdAt": "2030-01-01T00:00:00Z",
  "revisedAt": "2030-01-01T00:00:00.500Z",
  "sentAt": "2030-01-01T00:00:00+00:00",
  "validUntil": "2030-12-31T23:59:59Z",
  "status": "ACTIVE",
  "creditor": {"name": "Banco Exemplo", "address": {"line1": "Rua A", "city": "Sao Paulo", "country": "BR"}, "MCC": "5999"},
  "bill": {
    "amountDue": {"amount": 1250, "currency": "USD"},
    "paymentTiming": "someday",
    "description": "foreign",
    "order": {"number": "PO-1", "date": "2030-01-02"},
    "invoice": {"number": "INV-1", "date": "2030-01-02", "dueDate": "2030-02-01T00:00:00Z", "invoicee": {"name": "Payer"}},
    "tip": {"allowed": true, "range": {"min": 0, "max": 20}, "presets": [10, 50]}
  },
  "paymentNotification": "https://other-bank.example/notify",
  "paymentMethods": [
    {"currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1250,
     "editable": {"range": {"min": 1000, "max": 2000}},
     "networks": {
       "ach": {"routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "plaintext"},
       "zelle": {"token": "loja@example.com"},
       "someFutureChain": {"addr": "zzz", "tag": 99, "nested": {"a": [1, 2.50, 3e2]}}}},
    {"currency": "BRL", "validUntil": "2030-12-31T23:59:59Z", "amount": 6500,
     "networks": {"pix": {"pixKey": "loja@example.com", "txid": "ABC123"}}},
    {"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 12500000,
     "networks": {"solana": {"recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"}, "tron": null}},
    {"currency": "EUR"}
  ]
}`

// S10.7-AC5: read with tolerance, member by member (see foreign.go).
func TestReadForeign(t *testing.T) {
	for _, tc := range []struct{ name, in, want string }{
		{"the whole payload", foreignPayload, foreignPresented},
		{"the black-box probe", `{"id": "probe", "paymentMethods": []}`, `{"id":"probe"}`},
		{"nothing known", `{"x": 1}`, `{}`},
		{"a repeated key keeps its last value", `{"id": "a", "id": "b"}`, `{"id":"b"}`},
		{"a repeated network keeps its first place", `{"paymentMethods": [{"networks": {"pix": 1, "zelle": 2, "pix": 3}}]}`,
			`{"paymentMethods":[{"networks":{"pix":3,"zelle":2}}]}`},
		{"an amount past int64 is not presented", `{"revision": 9223372036854775808}`, `{}`},
		{"an object where a string is declared", `{"id": {"a": 1}, "status": ["ACTIVE"]}`, `{}`},
		{"a string where an object is declared", `{"creditor": "x", "bill": 1}`, `{}`},
		{"a known rail spelled in another case is somebody else's", `{"paymentMethods": [{"networks": {"ACH": {"x": 1}}}]}`,
			`{"paymentMethods":[{"networks":{"ACH":{"x":1}}}]}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := readForeign([]byte(tc.in))
			if err != nil {
				t.Fatal(err)
			}
			if diff := cmp.Diff(compact(t, tc.want), string(got)); diff != "" {
				t.Errorf("(-want +got):\n%s", diff)
			}
		})
	}
	for _, in := range []string{`[]`, `"x"`, `null`, `1`} {
		if _, err := readForeign([]byte(in)); err == nil {
			t.Errorf("%s: read as a payload", in)
		}
	}
	if _, err := readForeign([]byte(`{"id": `)); err == nil {
		t.Error("a truncated payload was read")
	}
}

// S10.7-AC5: what this deployment generates stays strict. The members the decoder drops from
// another bank's payload are refused, by name, by the create's own contract (INTERPRETATION I-16):
// tolerance is the reader's, never the create's or the PATCH's.
func TestTheCreateContractStaysStrict(t *testing.T) {
	const create = `{
	  "validUntil": "2030-12-31T23:59:59Z",
	  "creditor": {"name": "Strict", "loyaltyId": "L-1", "MCC": "5999",
	    "address": {"line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US"}},
	  "bill": {"description": "strict", "amountDue": {"amount": 1250, "currency": "USD"}},
	  "paymentMethods": [{"currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1250, "priority": 1,
	    "networks": {"ach": {"routingNumber": "021000021", "accountNumber": "1234567890",
	      "protectionType": "plaintext", "accountType": "checking"}}}]
	}`
	var req contract.CreateRequest
	if err := httpapi.Unmarshal([]byte(create), &req); err != nil {
		t.Fatal(err)
	}
	got := strings.Join(req.Validate(), "\n")
	for _, refused := range []string{"creditor.loyaltyId: ", "paymentMethods[0].priority: ", "paymentMethods[0].networks.ach.accountType: "} {
		if !strings.Contains(got, refused) {
			t.Errorf("the create does not refuse %s; its violations:\n%s", refused, got)
		}
	}
	presented, err := readForeign([]byte(create))
	if err != nil {
		t.Fatal(err)
	}
	for _, dropped := range []string{"loyaltyId", "priority", "accountType"} {
		if strings.Contains(string(presented), dropped) {
			t.Errorf("the reader presented %s: %s", dropped, presented)
		}
	}
}
