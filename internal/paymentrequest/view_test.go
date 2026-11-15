// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

// S9.6-AC5: GET shows the stored request as Java reads it back, whatever the body left out or sent
// as null, and the lifecycle at now: a lapsed reservation reads ACTIVE, and the payment details
// appear once given. lockVersion never appears.
func TestViewIsJavasReadOfTheRow(t *testing.T) {
	stored := `{"validUntil":"2030-12-31T23:59:59Z","creditor":{"name":"Co","phone":null,"MCC":"4900",` +
		`"address":{"line1":"1 A St","line2":null,"city":"LA","country":"US"}},` +
		`"bill":{"paymentTiming":"deferred","description":"d","invoice":{"number":"I","date":"2030-01-10","dueDate":"2030-12-30T00:00:00Z"},` +
		`"tip":{"range":{"min":0,"max":20},"presets":null},` +
		`"amountDue":{"amount":1000,"currency":"USD","adjustments":{"formula":"FixedDiscountLateFeeLinearInterest",` +
		`"parameters":{"discounts":[],"lateFees":{"fixed":1,"perDay":2,"explanation":"x"}}}}},` +
		`"unstructured":null,"additionalInformation":[],"paymentNotification":{"kind":"EXTERNAL","endpoint":"https://n.example"},` +
		`"paymentMethods":[{"currency":"USD","validUntil":"2030-12-31T23:59:59Z","amount":1000,"editable":null,` +
		`"networks":{"fednow":{"routingNumber":"021000021","accountNumber":"1234","protectionType":"plaintext"}}}]}`
	id := uuid.MustParse("01a1dc2a-6a37-d73d-6313-076e9bfe04f5")
	created := time.Date(2026, 11, 15, 12, 0, 0, 500_000_000, time.UTC)
	q := store.QRCode{
		ID: id, LocationID: id, ValidUntil: time.Date(2030, 12, 31, 23, 59, 59, 0, time.UTC),
		Status: domain.StatusPaymentInitiated, Revision: 2, LockVersion: 9,
		ReservedUntil: created.Add(time.Minute), EndToEndID: "E2E-1",
		RequestJSON: json.RawMessage(stored), CreatedAt: created, RevisedAt: created,
		EMVContent: "000201",
	}
	s := &Service{host: "pay.example"}
	got, err := s.view(q, nil, created.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	const want = `{"id":"01A1DC2A6A37D73D6313076E9BFE04F5","qrCode":"000201","qrCodeB64":"MDAwMjAx",` +
		`"location":{"id":"01A1DC2A6A37D73D6313076E9BFE04F5","endpoint":"https://pay.example/pub/api/v1/loc/01A1DC2A6A37D73D6313076E9BFE04F5"},` +
		`"validUntil":"2030-12-31T23:59:59Z","creditor":{"name":"Co","address":{"line1":"1 A St","city":"LA","country":"US"},"MCC":"4900"},` +
		`"bill":{"paymentTiming":"deferred","description":"d","invoice":{"number":"I","date":"2030-01-10","dueDate":"2030-12-30T00:00:00Z"},` +
		`"tip":{"allowed":true,"range":{"min":0,"max":20}},` +
		`"amountDue":{"amount":1000,"currency":"USD","adjustments":{"formula":"FixedDiscountLateFeeLinearInterest",` +
		`"parameters":{"lateFees":{"fixed":1,"perDay":2,"explanation":"x"}}}}},` +
		`"paymentNotification":{"kind":"EXTERNAL","endpoint":"https://n.example"},` +
		`"paymentMethods":[{"currency":"USD","validUntil":"2030-12-31T23:59:59Z","amount":1000,` +
		`"networks":{"fednow":{"routingNumber":"021000021","accountNumber":"1234","protectionType":"plaintext"}}}],` +
		`"revision":2,"createdAt":"2026-11-15T12:00:00.500Z","revisedAt":"2026-11-15T12:00:00.500Z",` +
		`"sentAt":"2026-11-15T13:00:00.500Z","status":"ACTIVE","paymentDetails":{"endToEndId":"E2E-1"}}`
	if diff := cmp.Diff(want, string(got)); diff != "" {
		t.Fatalf("view (-want +got):\n%s", diff)
	}
}

// X9.150 §6, INTERPRETATION I-17 (owner, 2026-11-15): sentAt is when the JSON is generated, on
// the business clock, but never earlier than revisedAt; the GET answer carries it.
func TestSentAtIsNowButNeverBeforeRevisedAt(t *testing.T) {
	revised := time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC)
	for _, tc := range []struct {
		name string
		now  time.Time
		want time.Time
	}{
		{"later than revisedAt: now", revised.Add(time.Minute), revised.Add(time.Minute)},
		{"at revisedAt", revised, revised},
		{"a clock behind revisedAt: revisedAt", revised.Add(-time.Hour), revised},
	} {
		if got := SentAt(revised, tc.now); !got.Equal(tc.want) {
			t.Errorf("%s: SentAt = %v, want %v", tc.name, got, tc.want)
		}
	}

	stored := `{"validUntil":"2030-12-31T23:59:59Z","creditor":{"name":"Co","MCC":"4900"},` +
		`"bill":{"paymentTiming":"immediate","description":"d","amountDue":{"amount":1000,"currency":"USD"}},` +
		`"paymentNotification":{"kind":"DEFAULT"},"paymentMethods":[{"currency":"USD","validUntil":"2030-12-31T23:59:59Z",` +
		`"amount":1000,"networks":{"fednow":{"routingNumber":"021000021","accountNumber":"1234","protectionType":"plaintext"}}}]}`
	id := uuid.MustParse("01a1dc2a-6a37-d73d-6313-076e9bfe04f5")
	q := store.QRCode{ID: id, LocationID: id, ValidUntil: time.Date(2030, 12, 31, 23, 59, 59, 0, time.UTC),
		Status: domain.StatusActive, ReservedUntil: domain.NoLapse, RequestJSON: json.RawMessage(stored),
		CreatedAt: revised, RevisedAt: revised, EMVContent: "000201"}
	got, err := (&Service{host: "pay.example"}).view(q, nil, revised.Add(-time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	var answer map[string]any
	if err := json.Unmarshal(got, &answer); err != nil {
		t.Fatal(err)
	}
	if answer["sentAt"] != "2026-11-15T12:00:00Z" {
		t.Errorf("GET at a clock behind revisedAt: sentAt %v, want revisedAt", answer["sentAt"])
	}
}

// X9-HOLD-401 (S10.5-AC5): GET shows the latest accepted notification as paymentNotification.data,
// after kind and endpoint, echoing the payer's spelling, without payment.qrcodeId (Java's mapper
// does not copy it); no data member before any notification.
func TestViewShowsTheLatestNotification(t *testing.T) {
	id := uuid.MustParse("01a1dc2a-6a37-d73d-6313-076e9bfe04f5")
	q := store.QRCode{
		ID: id, LocationID: id, ValidUntil: time.Date(2030, 12, 31, 23, 59, 59, 0, time.UTC),
		Status: domain.StatusPaymentInitiated, ReservedUntil: domain.NoLapse,
		RequestJSON: json.RawMessage(`{"paymentNotification":{"kind":"DEFAULT"},` +
			`"paymentMethods":[{"currency":"USDC","amount":1000,"networks":{"solana":{"recipient":"R"}}}]}`),
	}
	notification := []byte(`{"payment":{"qrcodeId":"01A1DC2A6A37D73D6313076E9BFE04F5","amount":1000,` +
		`"currency":"usdc","network":"SOLANA","transactionId":"5ig"},"payer":{"info":"p@example.com"},` +
		`"blockchain":{"action":"SENT","from":"F","to":"R"}}`)
	s := &Service{host: "pay.example"}
	got, err := s.view(q, notification, q.ValidUntil.Add(-time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	var body struct {
		PaymentNotification json.RawMessage `json:"paymentNotification"`
	}
	if err := json.Unmarshal(got, &body); err != nil {
		t.Fatal(err)
	}
	const want = `{"kind":"DEFAULT","data":{"payment":{"amount":1000,"currency":"usdc","network":"SOLANA",` +
		`"transactionId":"5ig"},"payer":{"info":"p@example.com"},"blockchain":{"action":"SENT","from":"F","to":"R"}}}`
	if diff := cmp.Diff(want, string(body.PaymentNotification)); diff != "" {
		t.Fatalf("paymentNotification (-want +got):\n%s", diff)
	}

	if got, err = s.view(q, nil, q.ValidUntil.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(got, &body); err != nil || string(body.PaymentNotification) != `{"kind":"DEFAULT"}` {
		t.Fatalf("before any notification: %s, %v", body.PaymentNotification, err)
	}
}

// A body is read for the business rules as Java's value objects read it.
func TestBillingRequestReadsTheBody(t *testing.T) {
	var req contract.CreateRequest
	raw := `{"validUntil":"2030-12-31T23:59:59Z","bill":{"amountDue":{"amount":1000,"currency":"USD"},"tip":{"presets":[]}},` +
		`"paymentMethods":[{"currency":"USD","validUntil":"2030-12-31T23:59:59Z","amount":10,"editable":{"range":{"min":1,"max":10}},` +
		`"networks":{"ach":{"routingNumber":"021000021","accountNumber":"1234"},"solana":{"recipient":"r","memo":"m"},"FedNow":{}}}]}`
	if err := httpapi.Unmarshal([]byte(raw), &req); err != nil {
		t.Fatal(err)
	}
	got := billingRequest(&req)
	memo := "m"
	at := time.Date(2030, 12, 31, 23, 59, 59, 0, time.UTC)
	want := billing.Request{
		ValidUntil: at,
		Bill: billing.Bill{
			Tip:       &billing.Tip{Allowed: true}, // allowed defaults to true; an empty presets list is none
			AmountDue: billing.AmountDue{Amount: 1000, Currency: "USD"},
		},
		PaymentMethods: []billing.PaymentMethod{{
			Currency: "USD", ValidUntil: at, Amount: 10,
			Editable: &billing.Editable{Range: &billing.AmountRange{Min: 1, Max: 10}},
			Networks: billing.Networks{
				ACH:    &billing.BankAddress{RoutingNumber: "021000021", AccountNumber: "1234"},
				Solana: &billing.SolanaAddress{Recipient: "r", Memo: &memo},
				Other:  []string{"FedNow"},
			},
		}},
	}
	if diff := cmp.Diff(want, got); diff != "" {
		t.Fatalf("billing request (-want +got):\n%s", diff)
	}
}

// Every failure is answered as Java answers it: a Violation and a Refusal by their kind, a
// statement timeout as a 503, a unique violation on the location as a refused rebind.
func TestAnswers(t *testing.T) {
	for _, tc := range []struct {
		name   string
		err    error
		status int
		typ    string
		says   string
	}{
		{"invalid", &billing.Violation{Kind: billing.Invalid, Message: "m"}, 400, httpapi.TypeValidation, "m"},
		{"business rule", fmt.Errorf("x: %w", &billing.Violation{Kind: billing.BusinessRule, Field: "f", Message: "m"}), 400,
			httpapi.TypeBusinessRule, "f: m"},
		{"refusal", &domain.Refusal{Kind: domain.KindBusinessRule, Message: "r"}, 400, httpapi.TypeBusinessRule, "r"},
		{"conflict", &domain.Refusal{Kind: domain.KindConflict, Message: "busy", CurrentStatus: domain.StatusActive}, 409,
			httpapi.TypeStatusConflict, "busy"},
		{"precondition", &domain.Refusal{Kind: domain.KindPreconditionFailed, Message: "p"}, 412, httpapi.TypePreconditionFailed, "p"},
		{"duplicate", &store.DuplicateError{Constraint: "ux_qr_code_location_id"}, 400, httpapi.TypeBusinessRule, msgLocationTaken},
		{"timeout", fmt.Errorf("%w: x", store.ErrUnavailable), 503, httpapi.TypeInternalError, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var p *httpapi.Problem
			if !errors.As(answer(tc.err), &p) || p.Status != tc.status || p.Type != tc.typ {
				t.Fatalf("answer = %#v, want %d %s", answer(tc.err), tc.status, tc.typ)
			}
			if tc.says != "" && (len(p.Violations) != 1 || p.Violations[0] != tc.says) {
				t.Fatalf("violations %v, want [%s]", p.Violations, tc.says)
			}
		})
	}
	other := errors.New("boom")
	if !errors.Is(answer(other), other) {
		t.Fatal("anything else is passed on, to be answered 500")
	}
	if p := notFound(uuid.Nil); p.Status != http.StatusNotFound {
		t.Fatal(p)
	}
}
