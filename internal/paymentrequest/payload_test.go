// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/emv"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/pki"
)

// payer signs payload requests and reads the answers, with the bundled demo key, which the
// deployment under test also trusts and signs with.
type payer struct {
	keys     *keys.Store
	signer   *jws.Signer
	verifier *jws.Verifier
}

func newPayer(t *testing.T, e *env) *payer {
	t.Helper()
	ks, err := keys.Load(config.Certificate{}, slog.New(slog.DiscardHandler))
	if err != nil {
		t.Fatal(err)
	}
	signer, err := jws.New(jws.Config{Algorithm: "PS512", Publication: jws.PublishChain, Host: host}, e.clock)
	if err != nil {
		t.Fatal(err)
	}
	fetcher, err := pki.NewFetcher(pki.FetcherConfig{Clock: clock.New(), OwnHost: host, OwnBaseURL: "http://127.0.0.1:1"})
	if err != nil {
		t.Fatal(err)
	}
	verifier, err := jws.NewVerifier(jws.VerifierConfig{Truststore: ks, IssuerName: "X9", Fetcher: fetcher,
		Logger: slog.New(slog.DiscardHandler)}, e.clock)
	if err != nil {
		t.Fatal(err)
	}
	NewPayloads(e.store, ks, signer, verifier, host, e.clock.Now, slog.New(slog.DiscardHandler)).Register(e.api)
	return &payer{keys: ks, signer: signer, verifier: verifier}
}

// request is a payer's signed payload request carrying content.
func (p *payer) request(t *testing.T, content string, correlationID uuid.UUID) string {
	t.Helper()
	body, err := json.Marshal(map[string]string{"qrCodeContent": base64.StdEncoding.EncodeToString([]byte(content))})
	if err != nil {
		t.Fatal(err)
	}
	token, err := p.signer.Sign(p.keys.Current().Identity, jws.Message{Payload: body, CorrelationID: correlationID})
	if err != nil {
		t.Fatal(err)
	}
	return token
}

type fetched struct {
	status int
	header http.Header
	text   string
	body   map[string]any
}

// post sends token to the location's payload route with the headers given.
func (e *env) post(t *testing.T, location, token string, header map[string]string) fetched {
	t.Helper()
	r := httptest.NewRequestWithContext(context.Background(), http.MethodPost, "/pub/api/v1/loc/"+location,
		strings.NewReader(token))
	r.Header.Set("Content-Type", "application/jose")
	for k, v := range header {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	e.api.ServeHTTP(w, r)
	f := fetched{status: w.Code, header: w.Header(), text: w.Body.String()}
	_ = json.Unmarshal(w.Body.Bytes(), &f.body)
	return f
}

// issued creates a QR Code and returns its id, its location and its EMV string.
func (e *env) issued(t *testing.T, override map[string]any) (id, location, content string) {
	t.Helper()
	a := e.do(t, http.MethodPost, "/api/v1/payment-request", body(t, override))
	if a.status != http.StatusCreated {
		t.Fatalf("create: %d %s", a.status, a.text)
	}
	return a.body["id"].(string), a.body["location"].(map[string]any)["id"].(string), a.body["qrCode"].(string)
}

// served fetches the payload of an issued QR Code and returns it verified, with the HTTP answer.
func (e *env) served(t *testing.T, p *payer, location, content string, header map[string]string) (*jws.Verified, fetched) {
	t.Helper()
	cid := uuid.New()
	f := e.post(t, location, p.request(t, content, cid), header)
	if f.status != http.StatusOK {
		t.Fatalf("payload: %d %s", f.status, f.text)
	}
	v, err := p.verifier.Verify(t.Context(), f.text, jws.Expect{CorrelationID: cid})
	if err != nil {
		t.Fatalf("the payload does not verify: %v", err)
	}
	return v, f
}

func claims(t *testing.T, v *jws.Verified) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(v.Payload, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

// refused asserts a 400 business-rule-violation whose one violation is want, with no signatureProblem
// and nothing of the payload in it.
func refused(t *testing.T, f fetched, want string) {
	t.Helper()
	v, _ := f.body["violations"].([]any)
	if f.status != http.StatusBadRequest || len(v) != 1 || v[0] != want {
		t.Fatalf("want a 400 saying %q: %d %s", want, f.status, f.text)
	}
	if _, ok := f.body["signatureProblem"]; ok || strings.Contains(f.text, recipient) {
		t.Fatalf("a refusal carries no signatureProblem and no payload: %s", f.text)
	}
}

func TestPayloadAgainstPostgres(t *testing.T) {
	e := newEnv(t)
	NewStatusUpdates(e.store, ttl).Register(e.api)
	p := newPayer(t, e)
	for _, tc := range []struct {
		name string
		test func(t *testing.T, e *env, p *payer)
	}{
		{"ServedIsJavasPayloadSigned", testServedIsJavasPayloadSigned},
		{"SentAtIsNowNeverBeforeRevisedAt", testSentAtIsNowNeverBeforeRevisedAt},
		{"ARequestThatDoesNotVerifyIs400", testARequestThatDoesNotVerifyIs400},
		{"AnUnknownLocationIs400", testAnUnknownLocationIs400},
		{"ContentMustBeTheIssuedEMV", testContentMustBeTheIssuedEMV},
		{"ClosedAndExpiredAreRefusedNamingWhy", testClosedAndExpiredAreRefusedNamingWhy},
		{"ALapsedReservationIsServedActive", testALapsedReservationIsServedActive},
		{"PricedAtTheLaterOfDateForPaymentAndNow", testPricedAtTheLaterOfDateForPaymentAndNow},
		{"TheExternalEndpointIsTheCreditors", testTheExternalEndpointIsTheCreditors},
		{"BindingRefusals", testBindingRefusals},
		{"AStatementCancelledIs503", testAStatementCancelledIs503},
	} {
		e.clock.set(start)
		t.Run(tc.name, func(t *testing.T) { tc.test(t, e, p) })
	}
}

// S10.3-AC4, S10.3-AC5: the body is Java's PaymentPayloadResponse member for member and in its order
// (the golden ec-payload-response's shape), signed with statusCode 200 in the header and in crit,
// under the request's correlationId; X-Payload-Based-Date is sentAt's date; nothing is written.
func testServedIsJavasPayloadSigned(t *testing.T, e *env, p *payer) {
	id, location, content := e.issued(t, nil)
	audits, events := e.count(t, "SELECT count(*) FROM qr_code_audit_log"), e.count(t, "SELECT count(*) FROM payment_event")
	before := e.stored(t, id)

	v, f := e.served(t, p, location, content, nil)

	want := `{"id":"` + id + `","revision":0,"createdAt":"2026-11-15T12:00:00Z","revisedAt":"2026-11-15T12:00:00Z",` +
		`"sentAt":"2026-11-15T12:00:00Z","validUntil":"2030-12-31T23:59:59Z","status":"ACTIVE",` +
		`"creditor":{"name":"Blackbox Co","address":{"line1":"1 A St","city":"Los Angeles","country":"US"},"MCC":"4900"},` +
		`"bill":{"amountDue":{"amount":1000,"currency":"USDC"},"paymentTiming":"immediate","description":"blackbox",` +
		`"tip":{"allowed":false}},"paymentNotification":"https://` + host + `/pub/api/v1/payment-notification",` +
		`"paymentMethods":[{"currency":"USDC","validUntil":"2030-12-31T23:59:59Z","amount":1000,` +
		`"networks":{"solana":{"recipient":"` + recipient + `"}}}]}`
	if got := string(v.Payload); got != want {
		t.Fatalf("payload:\n got %s\nwant %s", got, want)
	}
	if v.Header.StatusCode != http.StatusOK || !slices.Contains(v.Header.Critical, "statusCode") ||
		v.Header.Type != "JOSE" {
		t.Fatalf("header: %+v", v.Header)
	}
	if ct := f.header.Get("Content-Type"); ct != "application/jose;charset=UTF-8" {
		t.Fatalf("Content-Type %q", ct)
	}
	if d := f.header.Get("X-Payload-Based-Date"); d != "2026-11-15" {
		t.Fatalf("X-Payload-Based-Date %q", d)
	}
	after := e.stored(t, id)
	if diff := cmp.Diff(before.Row, after.Row); diff != "" ||
		e.count(t, "SELECT count(*) FROM qr_code_audit_log") != audits || e.count(t, "SELECT count(*) FROM payment_event") != events {
		t.Fatalf("a payload wrote something (-before +after):\n%s", diff)
	}
}

// Owner, 2026-11-15 (X9.150 §6): sentAt is the business clock's now when the payload is composed,
// never earlier than revisedAt, and never later than validUntil (an expired QR Code is not served).
func testSentAtIsNowNeverBeforeRevisedAt(t *testing.T, e *env, p *payer) {
	_, location, content := e.issued(t, nil)

	e.clock.set(start.Add(90*time.Minute + 123*time.Millisecond))
	v, _ := e.served(t, p, location, content, nil)
	if got := claims(t, v)["sentAt"]; got != "2026-11-15T13:30:00.123Z" {
		t.Fatalf("sentAt %v, want the business clock's now", got)
	}

	e.clock.set(start.Add(-time.Hour)) // a test clock moved back past revisedAt
	v, f := e.served(t, p, location, content, nil)
	c := claims(t, v)
	if c["sentAt"] != c["revisedAt"] || c["sentAt"] != "2026-11-15T12:00:00Z" {
		t.Fatalf("sentAt %v must not precede revisedAt %v", c["sentAt"], c["revisedAt"])
	}
	if d := f.header.Get("X-Payload-Based-Date"); d != "2026-11-15" {
		t.Fatalf("X-Payload-Based-Date %q, want sentAt's date", d)
	}
}

// S10.3-AC1: a request that does not verify is a 400 with Java's one generic reason, never a 401 and
// never a signatureProblem code; malformed EMV and content naming another location are refused by the
// verifier (CONTENT_MISMATCH) with the same answer.
func testARequestThatDoesNotVerifyIs400(t *testing.T, e *env, p *payer) {
	_, location, content := e.issued(t, nil)
	other := strings.Repeat("F", 32)
	for name, token := range map[string]string{
		"not a JWS":         "not-a-jws",
		"blank":             "   ",
		"malformed EMV":     p.request(t, content[:len(content)-4]+"0000", uuid.New()),
		"trailing space":    p.request(t, content+" ", uuid.New()),
		"another location":  p.request(t, withCRC(t, strings.Replace(content[:len(content)-4], location, other, 1)), uuid.New()),
		"no qrCodeContent":  signedJSON(t, p, `{"content":"x"}`),
		"content not text":  signedJSON(t, p, `{"qrCodeContent":"%%%"}`),
		"expired iat + ttl": expiredRequest(t, e, p, content),
	} {
		t.Run(name, func(t *testing.T) {
			refused(t, e.post(t, location, token, nil), msgSignatureRefused)
		})
	}
}

// S10.3-AC1: a location nobody holds, named by a well-formed, correctly signed request, is a 400 with
// the generic reason, as Java answers it (not a 404), and names nothing.
func testAnUnknownLocationIs400(t *testing.T, e *env, p *payer) {
	_, location, content := e.issued(t, nil)
	unknown := strings.Repeat("0", 32)
	moved := withCRC(t, strings.Replace(content[:len(content)-4], location, unknown, 1))

	refused(t, e.post(t, unknown, p.request(t, moved, uuid.New()), nil), msgUnreadable)
}

// X9-LOC-514 (S11.4-AC1; ADR-0028, known difference 10; ADR-0025): when the database cancels the
// payload's read (SQLSTATE 57014, what a statement past statement_timeout raises), the payer is
// answered 503 internal-error, "try again", where the Java build answers its generic 400. The read
// is made to wait on a lock and is then cancelled by the server, as statement_timeout would cancel
// it after 30 s.
func testAStatementCancelledIs503(t *testing.T, e *env, p *payer) {
	_, location, content := e.issued(t, nil)
	token := p.request(t, content, uuid.New())
	ctx := t.Context()
	lock, err := e.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = lock.Rollback(context.Background()) }()
	if _, err := lock.Exec(ctx, "LOCK TABLE qr_code IN ACCESS EXCLUSIVE MODE"); err != nil {
		t.Fatal(err)
	}

	done := make(chan fetched, 1)
	go func() { done <- e.post(t, location, token, nil) }()
	cancelled := false
	for range 200 {
		err := e.pool.QueryRow(ctx, `SELECT COALESCE(bool_or(pg_cancel_backend(pid)), false) FROM pg_stat_activity
 WHERE wait_event_type = 'Lock' AND state = 'active' AND query LIKE '%qr_code%' AND pid <> pg_backend_pid()`).Scan(&cancelled)
		if err != nil {
			t.Fatal(err)
		}
		if cancelled {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	if !cancelled {
		t.Fatal("the payload's read never waited on the lock")
	}
	f := <-done
	if err := lock.Rollback(ctx); err != nil {
		t.Fatal(err)
	}

	if f.status != http.StatusServiceUnavailable || f.body["type"] != httpapi.TypeInternalError {
		t.Fatalf("want a 503 internal-error: %d %s", f.status, f.text)
	}
	if strings.Contains(f.text, "57014") || strings.Contains(f.text, "qr_code") || strings.Contains(f.text, recipient) {
		t.Fatalf("the 503 carries an internal detail or the payload: %s", f.text)
	}
}

// S10.3-AC2: a well-formed EMV string for this location that is not byte-equal to the one issued
// (another merchant name, a correct CRC) is refused naming qrCodeContent.
func testContentMustBeTheIssuedEMV(t *testing.T, e *env, p *payer) {
	_, location, content := e.issued(t, nil)
	altered := withCRC(t, strings.Replace(content[:len(content)-4], "Blackbox Co", "Blackbox Cp", 1))

	refused(t, e.post(t, location, p.request(t, altered, uuid.New()), nil), "qrCodeContent: "+msgContentNotIssued)
}

// S10.3-AC3: PAID and CANCELLED are refused naming which; past validUntil the expiry reason, the row
// kept (ADR-0029); validUntil itself is still served.
func testClosedAndExpiredAreRefusedNamingWhy(t *testing.T, e *env, p *payer) {
	for status, want := range map[string]string{putPaid: msgPaid, putCancelled: msgCancelled} {
		id, location, content := e.issued(t, nil)
		e.mustPut(t, id, status, "", http.StatusOK)
		refused(t, e.post(t, location, p.request(t, content, uuid.New()), nil), strings.Replace(want, "%s", location, 1))
	}

	_, location, content := e.issued(t, map[string]any{"validUntil": "2026-11-15T13:00:00Z", "paymentMethods": []any{map[string]any{
		"currency": "USDC", "validUntil": "2026-11-15T13:00:00Z", "amount": 1000,
		"networks": map[string]any{"solana": map[string]any{"recipient": recipient}},
	}}})
	e.clock.set(time.Date(2026, 11, 15, 13, 0, 0, 0, time.UTC))
	e.served(t, p, location, content, nil)
	e.clock.set(time.Date(2026, 11, 15, 14, 0, 0, 0, time.UTC))
	refused(t, e.post(t, location, p.request(t, content, uuid.New()), nil), strings.Replace(msgExpired, "%s", location, 1))
}

// S10.3-AC3, ADR-0019: a lapsed reservation reads ACTIVE and is served as ACTIVE; a live one is
// served as PAYMENT_INITIATED.
func testALapsedReservationIsServedActive(t *testing.T, e *env, p *payer) {
	id, location, content := e.issued(t, nil)
	e.mustPut(t, id, putInitiated, "", http.StatusOK)

	v, _ := e.served(t, p, location, content, nil)
	if got := claims(t, v)["status"]; got != "PAYMENT_INITIATED" {
		t.Fatalf("a live reservation: status %v", got)
	}
	e.clock.set(start.Add(ttl + time.Second))
	v, _ = e.served(t, p, location, content, nil)
	if got := claims(t, v)["status"]; got != "ACTIVE" {
		t.Fatalf("a lapsed reservation: status %v", got)
	}
}

// adjusted is a bill with a discount up to five days before its due date and a late fee after it,
// payable in USDC until 2030 and in USD over FedNow until nine days after the due date.
func adjusted() map[string]any {
	return map[string]any{
		"bill": map[string]any{
			"description": "blackbox",
			"invoice":     map[string]any{"number": "INV-1", "date": "2026-11-01", "dueDate": "2026-12-01T00:00:00Z"},
			"amountDue": map[string]any{"amount": 1000, "currency": "USDC", "adjustments": map[string]any{
				"formula": "FixedDiscountLateFeeLinearInterest",
				"parameters": map[string]any{
					"discounts": []any{map[string]any{"daysBefore": 5, "discount": 100, "explanation": "early"}},
					"lateFees":  map[string]any{"fixed": 50, "perDay": 10, "explanation": "late"},
				},
			}},
		},
		"paymentMethods": []any{
			map[string]any{"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
				"networks": map[string]any{"solana": map[string]any{"recipient": recipient}}},
			map[string]any{"currency": "USD", "validUntil": "2026-12-10T00:00:00Z", "amount": 1000,
				"networks": map[string]any{"fednow": map[string]any{
					"routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "plaintext"}}},
		},
	}
}

// S10.3-AC4, INTERPRETATION I-14: priced by S9.4-AC3 at the later of dateForPayment's UTC start and
// now, the payment methods chosen at that same instant; the bill's own amount is shown with the
// adjustment, each method's amount carried by ratio.
func testPricedAtTheLaterOfDateForPaymentAndNow(t *testing.T, e *env, p *payer) {
	_, location, content := e.issued(t, adjusted())
	type method struct {
		currency string
		amount   float64
	}
	for _, tc := range []struct {
		name, date, based string
		adjustment        map[string]any
		methods           []method
	}{
		{"now, before the discount's edge", "", "2026-11-15",
			map[string]any{"explanation": "early", "amount": -100.0, "validUntil": "2026-11-26T00:00:00Z"},
			[]method{{"USDC", 900}, {"USD", 900}}},
		{"a date already past is priced at now", "2026-11-01", "2026-11-01",
			map[string]any{"explanation": "early", "amount": -100.0, "validUntil": "2026-11-26T00:00:00Z"},
			[]method{{"USDC", 900}, {"USD", 900}}},
		{"ten days late, the USD method lapsed by then", "2026-12-11", "2026-12-11",
			map[string]any{"explanation": "late", "amount": 150.0, "validUntil": "2026-12-11T00:00:00Z"},
			[]method{{"USDC", 1150}}},
		{"on time and no discount left: no adjustment", "2026-11-30", "2026-11-30",
			nil, []method{{"USDC", 1000}, {"USD", 1000}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			header := map[string]string{}
			if tc.date != "" {
				header["dateForPayment"] = tc.date
			}
			v, f := e.served(t, p, location, content, header)
			c := claims(t, v)
			due := c["bill"].(map[string]any)["amountDue"].(map[string]any)
			if due["amount"] != 1000.0 {
				t.Fatalf("amountDue.amount is the bill's own: %v", due)
			}
			var adj map[string]any
			if l, ok := due["adjustment"].([]any); ok && len(l) == 1 {
				adj = l[0].(map[string]any)
			}
			if diff := cmp.Diff(tc.adjustment, adj); diff != "" {
				t.Fatalf("adjustment (-want +got):\n%s", diff)
			}
			served := c["paymentMethods"].([]any)
			got := make([]method, 0, len(served))
			for _, m := range served {
				m := m.(map[string]any)
				got = append(got, method{m["currency"].(string), m["amount"].(float64)})
			}
			if diff := cmp.Diff(tc.methods, got, cmp.AllowUnexported(method{})); diff != "" {
				t.Fatalf("methods (-want +got):\n%s", diff)
			}
			if d := f.header.Get("X-Payload-Based-Date"); d != tc.based {
				t.Fatalf("X-Payload-Based-Date %q, want %q", d, tc.based)
			}
		})
	}
}

// S10.3-AC4, ADR-0006: kind EXTERNAL gives the creditor's own endpoint; no paymentNotification, none.
func testTheExternalEndpointIsTheCreditors(t *testing.T, e *env, p *payer) {
	const own = "https://payee.example/notify"
	_, location, content := e.issued(t, map[string]any{"paymentNotification": map[string]any{"kind": "EXTERNAL", "endpoint": own}})
	v, _ := e.served(t, p, location, content, nil)
	if got := claims(t, v)["paymentNotification"]; got != own {
		t.Fatalf("paymentNotification %v, want %s", got, own)
	}

	_, location, content = e.issued(t, map[string]any{"paymentNotification": nil})
	v, _ = e.served(t, p, location, content, nil)
	if _, ok := claims(t, v)["paymentNotification"]; ok {
		t.Fatalf("no paymentNotification was created, none is served: %s", v.Payload)
	}
}

// What Spring refuses before the controller runs: a body that is not application/jose (415), no body,
// a dateForPayment that is not a date, an id that is not 32 characters.
func testBindingRefusals(t *testing.T, e *env, p *payer) {
	_, location, content := e.issued(t, nil)
	token := p.request(t, content, uuid.New())

	r := httptest.NewRequestWithContext(context.Background(), http.MethodPost, "/pub/api/v1/loc/"+location, strings.NewReader(token))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	e.api.ServeHTTP(w, r)
	if w.Code != http.StatusUnsupportedMediaType || w.Header().Get("Accept") != "application/jose" {
		t.Fatalf("a JSON body: %d %v %s", w.Code, w.Header(), w.Body)
	}
	if f := e.post(t, location, "", nil); f.status != http.StatusBadRequest || f.body["title"] != "Malformed JSON" {
		t.Fatalf("no body: %d %s", f.status, f.text)
	}
	for _, bad := range []string{"2026-13-01", "tomorrow", "2026-11-15T00:00:00Z"} {
		f := e.post(t, location, token, map[string]string{"dateForPayment": bad})
		if v, _ := f.body["violations"].([]any); f.status != http.StatusBadRequest || len(v) != 1 || v[0] != "dateForPayment has invalid value." {
			t.Fatalf("dateForPayment %q: %d %s", bad, f.status, f.text)
		}
	}
	f := e.post(t, location[:31], token, nil)
	if v, _ := f.body["violations"].([]any); f.status != http.StatusBadRequest || len(v) != 1 ||
		v[0] != "retrievePaymentPayloadByLocation.id: size must be between 32 and 32" {
		t.Fatalf("a short id: %d %s", f.status, f.text)
	}
}

// withCRC completes an EMV string that lacks its CRC value.
func withCRC(t *testing.T, s string) string {
	t.Helper()
	if !strings.HasSuffix(s, "6304") {
		t.Fatalf("%q does not end with the CRC tag", s)
	}
	return s + fmt.Sprintf("%04X", emv.CRC16([]byte(s)))
}

func signedJSON(t *testing.T, p *payer, payload string) string {
	t.Helper()
	token, err := p.signer.Sign(p.keys.Current().Identity, jws.Message{Payload: []byte(payload), CorrelationID: uuid.New()})
	if err != nil {
		t.Fatal(err)
	}
	return token
}

// expiredRequest is a request signed six minutes before now, with the default five-minute ttl.
func expiredRequest(t *testing.T, e *env, p *payer, content string) string {
	t.Helper()
	now := e.clock.Now()
	e.clock.set(now.Add(-6 * time.Minute))
	defer e.clock.set(now)
	return p.request(t, content, uuid.New())
}
