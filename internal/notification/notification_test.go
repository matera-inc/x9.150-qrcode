// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package notification

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"reflect"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

const (
	recipient = "So11111111111111111111111111111111111111112"
	wallet    = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
	bill      = 1000
)

var (
	t0         = time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC)
	validUntil = time.Date(2030, 1, 1, 0, 0, 0, 0, time.UTC)
)

type businessClock struct {
	mu sync.Mutex
	at time.Time
}

func (c *businessClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.at
}

func (c *businessClock) set(at time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.at = at
}

// fakeStore runs a transition on rows held in memory, as store.Transition does: the decision on
// the row as read, then the row, the notification (which needs its evidence) and the events.
type fakeStore struct {
	mu       sync.Mutex
	clock    *businessClock
	rows     map[uuid.UUID]domain.Row
	stored   []store.Evidence
	events   []domain.Event
	err      error
	panicked bool
}

func (f *fakeStore) Transition(_ context.Context, id uuid.UUID, ev *store.Evidence,
	decide func(domain.Row, *domain.NotificationRecord, time.Time) (domain.Decision, error),
) (store.Result, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.panicked {
		panic("an internal detail that must not leak")
	}
	if f.err != nil {
		return store.Result{}, f.err
	}
	row, ok := f.rows[id]
	if !ok {
		return store.Result{}, store.ErrNotFound
	}
	d, err := decide(row, nil, f.clock.Now())
	if err != nil {
		return store.Result{}, err
	}
	if d.Notification != nil {
		if ev == nil || len(ev.JSON) == 0 || ev.JWSCompact == "" {
			return store.Result{}, errors.New("an accepted notification is stored with its evidence")
		}
		f.stored = append(f.stored, *ev)
	}
	if d.Write {
		f.rows[id] = d.Row
	}
	f.events = append(f.events, d.Events...)
	return store.Result{Decision: d}, nil
}

func (f *fakeStore) row(id uuid.UUID) domain.Row {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.rows[id]
}

type fixture struct {
	t        *testing.T
	url      string
	clock    *businessClock
	store    *fakeStore
	keys     *keys.Store
	payer    *jws.Signer
	verifier *jws.Verifier
	logs     *syncBuffer
}

type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// serve runs the endpoint over HTTP. The payee and the payer both sign with the bundled demo key,
// the payer with its chain in x5c; two payers differ by payer.info.
func serve(t *testing.T, skew time.Duration) *fixture {
	t.Helper()
	clk := &businessClock{at: t0}
	logs := &syncBuffer{}
	logger := slog.New(slog.NewJSONHandler(logs, nil))
	ks, err := keys.Load(config.Certificate{}, slog.New(slog.DiscardHandler))
	if err != nil {
		t.Fatal(err)
	}
	payee, err := jws.New(jws.Config{Publication: jws.PublishChain}, clk)
	if err != nil {
		t.Fatal(err)
	}
	payer, err := jws.New(jws.Config{Publication: jws.PublishChain}, clk)
	if err != nil {
		t.Fatal(err)
	}
	verifier, err := jws.NewVerifier(jws.VerifierConfig{Truststore: ks, IssuerName: "X9", ClockSkew: skew}, clk)
	if err != nil {
		t.Fatal(err)
	}
	st := &fakeStore{clock: clk, rows: map[uuid.UUID]domain.Row{}}
	api := httpapi.New(logger, clk.Now)
	New(st, ks, payee, verifier, domain.Settings{ReservationTTL: 90 * time.Second}, logger).Register(api)
	srv := httptest.NewServer(api)
	t.Cleanup(srv.Close)
	return &fixture{t: t, url: srv.URL, clock: clk, store: st, keys: ks, payer: payer, verifier: verifier, logs: logs}
}

// qr stores a QR Code built from a create body, ACTIVE.
func (f *fixture) qr(request string) uuid.UUID {
	f.t.Helper()
	id := uuid.New()
	f.store.mu.Lock()
	defer f.store.mu.Unlock()
	f.store.rows[id] = domain.Row{
		ID: id, LocationID: uuid.New(), ValidUntil: validUntil, Status: domain.StatusActive,
		RequestJSON: json.RawMessage(request), CreatedAt: t0, RevisedAt: t0,
	}
	return id
}

// solanaQR is a USDC QR Code on Solana, with the given extra members of the bill and the method.
func solanaQR(billExtra, methodExtra, top string) string {
	return `{"validUntil":"2030-01-01T00:00:00Z","bill":{"amountDue":{"amount":1000,"currency":"USDC"}` + billExtra +
		`},"paymentMethods":[{"currency":"USDC","validUntil":"2030-01-01T00:00:00Z","amount":1000` + methodExtra +
		`,"networks":{"solana":{"recipient":"` + recipient + `"}}}]` + top + `}`
}

const notifyDefault = `,"paymentNotification":{"kind":"DEFAULT"}`

func bankQR(rails string) string {
	return `{"validUntil":"2030-01-01T00:00:00Z","bill":{"amountDue":{"amount":1000,"currency":"USD"}},` +
		`"paymentNotification":{"kind":"DEFAULT"},"paymentMethods":[{"currency":"USD",` +
		`"validUntil":"2030-01-01T00:00:00Z","amount":1000,"networks":{` + rails + `}}]}`
}

const bankAddress = `{"routingNumber":"021000021","accountNumber":"123456789"}`

// note is a notification body.
func note(id uuid.UUID, edits ...func(map[string]any)) map[string]any {
	n := map[string]any{
		"payment":    map[string]any{"qrcodeId": httpapi.FormatID(id), "amount": bill, "currency": "USDC", "network": "Solana"},
		"payer":      map[string]any{"info": "payer@example.com"},
		"blockchain": map[string]any{"action": "PAYMENT_INITIATED", "from": wallet, "to": recipient},
	}
	for _, e := range edits {
		e(n)
	}
	return n
}

func payment(key string, value any) func(map[string]any) {
	return func(n map[string]any) {
		p, _ := n["payment"].(map[string]any)
		if value == nil {
			delete(p, key)
			return
		}
		p[key] = value
	}
}

func top(key string, value any) func(map[string]any) {
	return func(n map[string]any) {
		if value == nil {
			delete(n, key)
			return
		}
		n[key] = value
	}
}

func (f *fixture) sign(body any, correlationID uuid.UUID) string {
	f.t.Helper()
	payload, ok := body.([]byte)
	if !ok {
		var err error
		if payload, err = json.Marshal(body); err != nil {
			f.t.Fatal(err)
		}
	}
	token, err := f.payer.Sign(f.t.Context(), f.keys.Current().Identity, jws.Message{Payload: payload, CorrelationID: correlationID})
	if err != nil {
		f.t.Fatal(err)
	}
	return token
}

type reply struct {
	status int
	header http.Header
	token  string
}

func (f *fixture) post(method, contentType, body string) reply {
	f.t.Helper()
	req, err := http.NewRequestWithContext(f.t.Context(), method, f.url+Path, strings.NewReader(body))
	if err != nil {
		f.t.Fatal(err)
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		f.t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		f.t.Fatal(err)
	}
	return reply{status: resp.StatusCode, header: resp.Header, token: string(b)}
}

func (f *fixture) notify(body any) (reply, uuid.UUID) {
	f.t.Helper()
	cid := uuid.New()
	return f.post(http.MethodPost, ContentTypeJOSE, f.sign(body, cid)), cid
}

// signed checks that an answer is a JWS the payee signed (S10.4-AC1): application/jose, typ JOSE,
// statusCode in the header, in crit and in the body equal to the HTTP status, under correlationID
// (or a fresh one when correlationID is uuid.Nil), iat the business clock's. It returns the body.
func (f *fixture) signed(r reply, status int, correlationID uuid.UUID) map[string]any {
	f.t.Helper()
	if r.status != status {
		f.t.Fatalf("status %d, want %d: %s", r.status, status, payloadOf(f.t, r.token))
	}
	if ct := r.header.Get("Content-Type"); ct != ContentTypeJOSE {
		f.t.Fatalf("Content-Type %q, want %q", ct, ContentTypeJOSE)
	}
	v, err := f.verifier.Verify(f.t.Context(), r.token, jws.Expect{CorrelationID: correlationID})
	if err != nil {
		f.t.Fatalf("the answer does not verify: %v", err)
	}
	if v.Header.Type != jws.TypeJOSE {
		f.t.Errorf("typ %q, want JOSE", v.Header.Type)
	}
	if v.Header.StatusCode != status {
		f.t.Errorf("header statusCode %d, want %d", v.Header.StatusCode, status)
	}
	if !strings.Contains(strings.Join(v.Header.Critical, ","), "statusCode") {
		f.t.Errorf("crit %v does not name statusCode", v.Header.Critical)
	}
	if !v.Header.IssuedAt().Equal(f.clock.Now()) {
		f.t.Errorf("iat %v, want the business clock's %v", v.Header.IssuedAt(), f.clock.Now())
	}
	if correlationID == uuid.Nil && v.Header.CorrelationID == uuid.Nil {
		f.t.Error("no correlationId")
	}
	var body map[string]any
	if err := json.Unmarshal(v.Payload, &body); err != nil {
		f.t.Fatal(err)
	}
	if body["statusCode"] != float64(status) {
		f.t.Errorf("body statusCode %v, want %d: %v", body["statusCode"], status, body)
	}
	if status != http.StatusOK && body["status"] != float64(status) {
		f.t.Errorf("body status %v, want %d: %v", body["status"], status, body)
	}
	if _, ok := body["instance"]; ok {
		f.t.Errorf("a signed refusal carries no instance: %v", body)
	}
	return body
}

func payloadOf(t *testing.T, token string) string {
	t.Helper()
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return token
	}
	b, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return token
	}
	return string(b)
}

func violations(body map[string]any) string {
	b, _ := json.Marshal(body["violations"])
	return string(b)
}

// S10.4-AC1, S10.4-AC2: a yes is {"statusCode":200}, signed, echoing the question, iat the payee's
// clock at the decision; the pre-commit reserves the QR Code and its notification is stored with the
// JWS and the payload as read (notification_json, blockchain.action included).
func TestAYesIsSignedAndEchoesTheQuestion(t *testing.T) {
	f := serve(t, 0)
	f.clock.set(t0.Add(24 * time.Hour)) // the iat must be the business clock's, not the wall clock's
	id := f.qr(solanaQR("", "", notifyDefault))

	r, cid := f.notify(note(id))

	body := f.signed(r, http.StatusOK, cid)
	if len(body) != 1 {
		t.Errorf("the yes is exactly {\"statusCode\":200}: %v", body)
	}
	if got := f.store.row(id).Status; got != domain.StatusPaymentInitiated {
		t.Errorf("status %s, want PAYMENT_INITIATED", got)
	}
	if len(f.store.stored) != 1 {
		t.Fatalf("stored %d notifications, want 1", len(f.store.stored))
	}
	var stored map[string]any
	if err := json.Unmarshal(f.store.stored[0].JSON, &stored); err != nil {
		t.Fatal(err)
	}
	want := `{"blockchain":{"action":"PAYMENT_INITIATED","from":"` + wallet + `","to":"` + recipient +
		`"},"payer":{"info":"payer@example.com"},"payment":{"amount":1000,"currency":"USDC","network":"Solana","qrcodeId":"` +
		httpapi.FormatID(id) + `"}}`
	if got, _ := json.Marshal(stored); string(got) != want {
		t.Errorf("notification_json\n got %s\nwant %s", got, want)
	}
	if !strings.HasPrefix(f.store.stored[0].JWSCompact, "ey") || strings.Count(f.store.stored[0].JWSCompact, ".") != 2 {
		t.Errorf("the compact JWS is stored: %q", f.store.stored[0].JWSCompact)
	}
}

// S10.4-AC1: refused before the handler reads anything, and still signed, under a fresh
// correlationId: an empty body (400), a wrong media type (415), a wrong method (405).
func TestRefusalsBeforeReadingAreSigned(t *testing.T) {
	f := serve(t, 0)
	cases := []struct {
		name, method, contentType, body string
		status                          int
		title                           string
	}{
		{"empty body", http.MethodPost, ContentTypeJOSE, "", http.StatusBadRequest, "Malformed JSON"},
		{"json", http.MethodPost, "application/json", "{}", http.StatusUnsupportedMediaType, "Unsupported Media Type"},
		{"no content type", http.MethodPost, "", "x.y.z", http.StatusUnsupportedMediaType, "Unsupported Media Type"},
		{"get", http.MethodGet, "", "", http.StatusMethodNotAllowed, "Method Not Allowed"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := f.post(c.method, c.contentType, c.body)
			body := f.signed(r, c.status, uuid.Nil)
			if body["title"] != c.title {
				t.Errorf("title %v, want %q", body["title"], c.title)
			}
		})
	}
	if r := f.post(http.MethodPost, "application/jose; charset=UTF-8", ""); r.status != http.StatusBadRequest {
		t.Errorf("application/jose with a charset is the endpoint's media type: %d", r.status)
	}
}

// S10.4-AC2: a notification that does not verify is a signed 401 with the fixed signatureProblem,
// no words, under a fresh correlationId; nothing is read or written.
func TestAnUnverifiableNotificationIsASigned401(t *testing.T) {
	f := serve(t, 0)
	id := f.qr(solanaQR("", "", notifyDefault))
	good := f.sign(note(id), uuid.New())
	parts := strings.Split(good, ".")
	tampered := parts[0] + "." + base64.RawURLEncoding.EncodeToString([]byte(`{"payment":{}}`)) + "." + parts[2]

	cases := []struct{ name, token, problem string }{
		{"not a JWS", "not-a-jws", "NOT_JWS"},
		{"payload edited after signing", tampered, "BAD_SIGNATURE"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := f.post(http.MethodPost, ContentTypeJOSE, c.token)
			body := f.signed(r, http.StatusUnauthorized, uuid.Nil)
			if body["signatureProblem"] != c.problem {
				t.Errorf("signatureProblem %v, want %s", body["signatureProblem"], c.problem)
			}
			if _, ok := body["signatureDetail"]; ok {
				t.Errorf("only the code goes back: %v", body)
			}
			if !strings.HasSuffix(body["type"].(string), "/invalid-signature") {
				t.Errorf("type %v", body["type"])
			}
		})
	}
	if got := f.store.row(id).Status; got != domain.StatusActive {
		t.Errorf("status %s, want ACTIVE", got)
	}
}

// S10.4-AC2: X9_JWS_CLOCK_SKEW_SECONDS bounds how far ahead an iat may be (FUTURE_IAT).
func TestAnIATAheadOfTheSkewIsFutureIAT(t *testing.T) {
	for _, c := range []struct {
		skew   time.Duration
		status int
	}{{0, http.StatusUnauthorized}, {3 * time.Minute, http.StatusOK}} {
		f := serve(t, c.skew)
		id := f.qr(solanaQR("", "", notifyDefault))
		f.clock.set(t0.Add(2 * time.Minute)) // the payer signs two minutes ahead of the payee
		cid := uuid.New()
		token := f.sign(note(id), cid)
		f.clock.set(t0)

		r := f.post(http.MethodPost, ContentTypeJOSE, token)
		if c.status == http.StatusOK {
			f.signed(r, http.StatusOK, cid)
			continue
		}
		if body := f.signed(r, c.status, uuid.Nil); body["signatureProblem"] != "FUTURE_IAT" {
			t.Errorf("skew %v: signatureProblem %v, want FUTURE_IAT", c.skew, body["signatureProblem"])
		}
	}
}

// S10.4-AC3: refused by name before any transition, each with the Java build's status and field, the
// answer signed under the notification's correlationId; the QR Code stays ACTIVE and nothing is
// stored.
func TestRefusedByName(t *testing.T) {
	ach := bankQR(`"ach":` + bankAddress)
	cases := []struct {
		name    string
		request string
		edits   []func(map[string]any)
		raw     []byte
		status  int
		naming  string
	}{
		{name: "no paymentNotification", request: solanaQR("", "", ""), status: 400, naming: "paymentNotification: is not configured"},
		{name: "kind EXTERNAL", request: solanaQR("", "", `,"paymentNotification":{"kind":"EXTERNAL","endpoint":"https://payee.example.com/n"}`),
			status: 400, naming: "paymentNotification.kind"},
		{name: "network not offered, echoed as sent", request: solanaQR("", "", notifyDefault),
			edits: []func(map[string]any){payment("network", "FedNow"), top("blockchain", nil)}, status: 400,
			naming: "paymentNotification.data.payment.network: This QR Code does not offer the FedNow rail."},
		{name: "rtp on a FedNow-only QR Code", request: bankQR(`"fednow":` + bankAddress),
			edits:  []func(map[string]any){payment("network", "RTP"), payment("currency", "USD"), top("blockchain", nil)},
			status: 400, naming: "paymentNotification.data.payment.network: This QR Code does not offer the RTP rail."},
		{name: "a chain the QR Code does not offer", request: bankQR(`"fednow":` + bankAddress),
			edits: []func(map[string]any){payment("currency", "USD")}, status: 400,
			naming: "paymentNotification.data.payment.network: This QR Code does not offer the Solana rail."},
		{name: "currency not offered", request: solanaQR("", "", notifyDefault),
			edits: []func(map[string]any){payment("currency", "USD")}, status: 400, naming: "paymentNotification.data.payment.currency: This QR Code does not offer the USD currency."},
		{name: "neither the rail nor the currency offered", request: bankQR(`"fednow":` + bankAddress),
			status: 400, naming: "paymentNotification.data.payment: This QR Code does not offer the Solana rail nor the USDC currency."},
		{name: "a NOT_SENT on a rail not offered: refused, nothing moved", request: bankQR(`"fednow":` + bankAddress),
			edits:  []func(map[string]any){payment("currency", "USD"), top("blockchain", map[string]any{"action": "NOT_SENT", "from": wallet, "to": recipient})},
			status: 400, naming: "This QR Code does not offer the Solana rail."},
		{name: "blockchain on a bank rail", request: bankQR(`"fednow":` + bankAddress),
			edits: []func(map[string]any){payment("network", "fednow"), payment("currency", "USD")}, status: 400,
			naming: "paymentNotification.data.blockchain"},
		{name: "a chain without blockchain", request: solanaQR("", "", notifyDefault),
			edits: []func(map[string]any){top("blockchain", nil)}, status: 400, naming: "Blockchain data is required"},
		{name: "ACH without payer.info", request: ach,
			edits: []func(map[string]any){payment("network", "ACH"), payment("currency", "USD"), top("blockchain", nil),
				top("payer", map[string]any{}), top("expectedDate", "2030-01-01T00:00:00Z")},
			status: 400, naming: "paymentNotification.data.payer.info"},
		{name: "ACH without expectedDate", request: ach,
			edits:  []func(map[string]any){payment("network", "ach"), payment("currency", "USD"), top("blockchain", nil)},
			status: 400, naming: "ExpectedDate must not be null when network is ACH"},
		{name: "a destination the QR Code never published", request: solanaQR("", "", notifyDefault),
			edits: []func(map[string]any){top("blockchain", map[string]any{"action": "PAYMENT_INITIATED", "from": wallet,
				"to": "11111111111111111111111111111111"})}, status: 400, naming: "destination address"},
		{name: "amount past 18 digits", request: solanaQR("", "", notifyDefault),
			raw: []byte(`{"payment":{"qrcodeId":"%ID%","amount":1000000000000000000,"currency":"USDC","network":"Solana"},` +
				`"payer":{"info":"p"},"blockchain":{"action":"PAYMENT_INITIATED","from":"` + wallet + `","to":"` + recipient + `"}}`),
			status: 400, naming: "should not exceed 18 digits"},
		{name: "amount past a long", request: solanaQR("", "", notifyDefault),
			raw:    []byte(`{"payment":{"qrcodeId":"%ID%","amount":99999999999999999999,"currency":"USDC","network":"Solana"}}`),
			status: 400, naming: msgUnreadable},
		{name: "a negative amount", request: solanaQR("", "", notifyDefault),
			edits: []func(map[string]any){payment("amount", -1)}, status: 400, naming: "should not be negative"},
		{name: "a negative tip", request: solanaQR("", "", notifyDefault),
			edits: []func(map[string]any){payment("tipAmount", -1)}, status: 400, naming: "should not be negative"},
		{name: "an expectedDate already past", request: solanaQR("", "", notifyDefault),
			edits: []func(map[string]any){top("expectedDate", "2026-01-01T00:00:00Z")}, status: 400,
			naming: "paymentNotification.data.expectedDate: ExpectedDate must not be less than the current date."},
		{name: "payer.info of 255", request: solanaQR("", "", notifyDefault),
			edits: []func(map[string]any){top("payer", map[string]any{"info": strings.Repeat("x", 255)})}, status: 400,
			naming: "payer.info"},
		{name: "a wrong amount", request: solanaQR("", "", notifyDefault),
			edits: []func(map[string]any){payment("amount", bill+1)}, status: 400, naming: "paymentNotification.data.payment.amount"},
		{name: "an unknown QR Code", request: "", status: 404, naming: "Could not find QR code"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := serve(t, 0)
			id := uuid.New()
			if c.request != "" {
				id = f.qr(c.request)
			}
			var body any = note(id, c.edits...)
			if c.raw != nil {
				body = bytes.ReplaceAll(c.raw, []byte("%ID%"), []byte(httpapi.FormatID(id)))
			}
			r, cid := f.notify(body)
			answer := f.signed(r, c.status, cid)
			if v := violations(answer); !strings.Contains(v, c.naming) {
				t.Errorf("violations %s do not name %q", v, c.naming)
			}
			if c.request != "" && f.store.row(id).Status != domain.StatusActive {
				t.Errorf("status %s, want ACTIVE", f.store.row(id).Status)
			}
			if len(f.store.stored) != 0 || len(f.store.events) != 0 {
				t.Errorf("a refusal stores nothing: %d notifications, %d events", len(f.store.stored), len(f.store.events))
			}
		})
	}
}

// ADR-0036 (amended 2026-11-15): a post-commit on a rail or in a currency the QR Code does not offer
// is an error that is still recorded. It is stored with its evidence, its hash is published as one
// payment.sent on every rail (a bank rail included) flagged NOT_OFFERED_RAIL and/or
// NOT_OFFERED_CURRENCY beside the other codes, the row is not touched at all (no reservation taken,
// none refreshed or stopped), and the answer is a signed 400 naming what is not offered.
func TestAPostCommitNotOfferedIsRecordedAndRefused(t *testing.T) {
	post := func(edits ...func(map[string]any)) []func(map[string]any) {
		return append([]func(map[string]any){payment("transactionId", "5VfYtx9Hash")}, edits...)
	}
	cases := []struct {
		name       string
		request    string
		held       bool // a pre-commit from the same payer reserved it first
		edits      []func(map[string]any)
		naming     string
		deviations []domain.Deviation
		status     domain.Status
	}{
		{name: "a chain on a bank-only QR Code, ACTIVE", request: bankQR(`"fednow":` + bankAddress),
			edits:      post(payment("currency", "USD"), top("blockchain", map[string]any{"action": "SENT", "from": wallet, "to": recipient})),
			naming:     "paymentNotification.data.payment.network: This QR Code does not offer the Solana rail.",
			deviations: []domain.Deviation{domain.DeviationReceivedInWrongStatus, domain.DeviationNotOfferedRail}, status: domain.StatusActive},
		{name: "RTP on a FedNow-only QR Code: each rail on its own, payment.sent on a bank rail too", request: bankQR(`"fednow":` + bankAddress),
			edits:      post(payment("network", "RTP"), payment("currency", "USD"), top("blockchain", nil)),
			naming:     "paymentNotification.data.payment.network: This QR Code does not offer the RTP rail.",
			deviations: []domain.Deviation{domain.DeviationReceivedInWrongStatus, domain.DeviationNotOfferedRail}, status: domain.StatusActive},
		{name: "a currency not offered, from the holder", request: solanaQR("", "", notifyDefault), held: true,
			edits:      post(payment("currency", "EURC"), top("blockchain", map[string]any{"action": "SENT", "from": wallet, "to": recipient})),
			naming:     "paymentNotification.data.payment.currency: This QR Code does not offer the EURC currency.",
			deviations: []domain.Deviation{domain.DeviationNotOfferedCurrency}, status: domain.StatusPaymentInitiated},
		{name: "both, contradicting its action", request: bankQR(`"fednow":` + bankAddress),
			edits:  post(),
			naming: "paymentNotification.data.payment: This QR Code does not offer the Solana rail nor the USDC currency.",
			deviations: []domain.Deviation{domain.DeviationReceivedInWrongStatus, domain.DeviationActionContradictsHash,
				domain.DeviationNotOfferedRail, domain.DeviationNotOfferedCurrency}, status: domain.StatusActive},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := serve(t, 0)
			id := f.qr(c.request)
			if c.held {
				r, cid := f.notify(note(id))
				f.signed(r, http.StatusOK, cid)
			}
			before := f.store.row(id)
			stored, events := len(f.store.stored), len(f.store.events)

			r, cid := f.notify(note(id, c.edits...))
			answer := f.signed(r, http.StatusBadRequest, cid)
			if v := violations(answer); !strings.Contains(v, c.naming) {
				t.Errorf("violations %s do not name %q", v, c.naming)
			}
			if after := f.store.row(id); !reflect.DeepEqual(after, before) {
				t.Errorf("the row changed:\n before %+v\n after  %+v", before, after)
			}
			if got := len(f.store.stored) - stored; got != 1 {
				t.Errorf("%d notifications stored, want the call recorded once", got)
			}
			if got := f.store.events[events:]; len(got) != 1 || got[0].Type != domain.EventSent {
				t.Fatalf("events %+v, want one payment.sent", got)
			}
			e := f.store.events[events]
			if !slices.Equal(e.Deviations, c.deviations) {
				t.Errorf("deviations %v, want %v", e.Deviations, c.deviations)
			}
			if e.ReceivedInStatus == nil || *e.ReceivedInStatus != c.status {
				t.Errorf("receivedInStatus %v, want %s", e.ReceivedInStatus, c.status)
			}
			if e.TransactionID == nil || *e.TransactionID != "5VfYtx9Hash" {
				t.Errorf("transactionId %v, want the hash", e.TransactionID)
			}
			if !strings.Contains(f.logs.String(), "NOT_OFFERED_") {
				t.Errorf("the warning does not name the deviation: %s", f.logs.String())
			}
		})
	}
}

// ADR-0036, amended 2026-11-15: a pre-commit's rail or currency not offered is the acceptance
// gate's, as in the Java build, so it comes after the status: another payer's hold is a 409 first,
// and a holder's retry naming one is a refused retry that releases the QR Code. Nothing is stored.
func TestAPreCommitNotOfferedIsTheGatesAfterTheStatus(t *testing.T) {
	f := serve(t, 0)
	id := f.qr(solanaQR("", "", notifyDefault))
	r, cid := f.notify(note(id))
	f.signed(r, http.StatusOK, cid)
	stored := len(f.store.stored)

	r, cid = f.notify(note(id, payment("currency", "EUR"), top("payer", map[string]any{"info": "someone-else@example.com"})))
	if body := f.signed(r, http.StatusConflict, cid); body["currentStatus"] != "PAYMENT_INITIATED" {
		t.Errorf("another payer naming a currency not offered: %v, want the 409 first", body)
	}

	r, cid = f.notify(note(id, payment("currency", "EUR")))
	answer := f.signed(r, http.StatusBadRequest, cid)
	if v := violations(answer); !strings.Contains(v, "paymentNotification.data.payment.currency: This QR Code does not offer the EUR currency.") {
		t.Errorf("violations %s", v)
	}
	if got := f.store.row(id).Status; got != domain.StatusActive {
		t.Errorf("the holder's refused retry leaves %s, want ACTIVE (released)", got)
	}
	if len(f.store.stored) != stored {
		t.Errorf("a refused pre-commit stored a notification")
	}
}

// The owner's ruling of 2026-11-15: a rail and a currency each offered, but not on one payment
// method (USD only on FedNow, USDC on Solana; the payer reports USD on Solana), are not offered. A
// pre-commit is a signed 400 with nothing stored; a post-commit is a signed 400 recorded on one
// payment.sent flagged NOT_OFFERED_CURRENCY, the QR Code untouched.
func TestACurrencyNotOfferedOnThatRail(t *testing.T) {
	twoMethods := `{"validUntil":"2030-01-01T00:00:00Z","bill":{"amountDue":{"amount":1000,"currency":"USDC"}},` +
		`"paymentNotification":{"kind":"DEFAULT"},"paymentMethods":[` +
		`{"currency":"USDC","validUntil":"2030-01-01T00:00:00Z","amount":1000,"networks":{"solana":{"recipient":"` + recipient + `"}}},` +
		`{"currency":"USD","validUntil":"2030-01-01T00:00:00Z","amount":1000,"networks":{"fednow":` + bankAddress + `}}]}`
	const want = "paymentNotification.data.payment.currency: This QR Code does not offer the USD currency on the Solana rail."

	f := serve(t, 0)
	id := f.qr(twoMethods)
	r, cid := f.notify(note(id, payment("currency", "USD")))
	if v := violations(f.signed(r, http.StatusBadRequest, cid)); !strings.Contains(v, want) {
		t.Errorf("pre-commit: %s", v)
	}
	if len(f.store.stored) != 0 || len(f.store.events) != 0 {
		t.Fatalf("a refused pre-commit stored %d notifications, %d events", len(f.store.stored), len(f.store.events))
	}

	before := f.store.row(id)
	r, cid = f.notify(note(id, payment("currency", "USD"), payment("transactionId", "5VfYtx9Hash"),
		top("blockchain", map[string]any{"action": "SENT", "from": wallet, "to": recipient})))
	if v := violations(f.signed(r, http.StatusBadRequest, cid)); !strings.Contains(v, want) {
		t.Errorf("post-commit: %s", v)
	}
	if !reflect.DeepEqual(f.store.row(id), before) {
		t.Error("the row changed")
	}
	if len(f.store.stored) != 1 || len(f.store.events) != 1 || f.store.events[0].Type != domain.EventSent ||
		!slices.Equal(f.store.events[0].Deviations, []domain.Deviation{domain.DeviationReceivedInWrongStatus, domain.DeviationNotOfferedCurrency}) {
		t.Errorf("stored %d, events %+v; want the call recorded on one flagged payment.sent", len(f.store.stored), f.store.events)
	}
}

// S10.4-AC3: the network and the currency are matched whatever their case.
func TestNetworkAndCurrencyAnyCase(t *testing.T) {
	f := serve(t, 0)
	id := f.qr(solanaQR("", "", notifyDefault))
	r, cid := f.notify(note(id, payment("network", "SOLANA"), payment("currency", "usdc")))
	f.signed(r, http.StatusOK, cid)

	g := serve(t, 0)
	bank := g.qr(bankQR(`"fednow":` + bankAddress))
	r, cid = g.notify(note(bank, payment("network", "FedNow"), payment("currency", "usd"), top("blockchain", nil)))
	g.signed(r, http.StatusOK, cid)
}

// S10.4-AC4: a refusal is logged with the payer's certificate subject, its payer.info and the reason.
func TestARefusalIsLoggedWithThePayer(t *testing.T) {
	f := serve(t, 0)
	id := f.qr(solanaQR("", "", ""))
	r, cid := f.notify(note(id))
	f.signed(r, http.StatusBadRequest, cid)

	var entry map[string]any
	for line := range strings.SplitSeq(f.logs.String(), "\n") {
		if strings.Contains(line, "payment notification refused") {
			if err := json.Unmarshal([]byte(line), &entry); err != nil {
				t.Fatal(err)
			}
		}
	}
	if entry == nil {
		t.Fatalf("no refusal logged: %s", f.logs.String())
	}
	subject := f.keys.Current().Identity.Certificate().Subject.String()
	if s, _ := entry["signerSubject"].(string); s == "" || !strings.Contains(s, strings.Split(subject, ",")[0]) {
		t.Errorf("signerSubject %v, want the certificate's %q", entry["signerSubject"], subject)
	}
	if entry["payerInfo"] != "payer@example.com" {
		t.Errorf("payerInfo %v", entry["payerInfo"])
	}
	if reason, _ := entry["reason"].(string); !strings.Contains(reason, "paymentNotification") {
		t.Errorf("reason %v", entry["reason"])
	}
}

// S10.4-AC1: a failure is a signed 500 that says nothing of its cause, under the notification's
// correlationId once it verified; a statement that ran out of time is a signed 503.
func TestFailuresAreSigned(t *testing.T) {
	for _, c := range []struct {
		name   string
		set    func(*fakeStore)
		status int
	}{
		{"an error", func(s *fakeStore) { s.err = errors.New("an internal detail that must not leak") }, 500},
		{"a panic", func(s *fakeStore) { s.panicked = true }, 500},
		{"a statement timeout", func(s *fakeStore) { s.err = store.ErrUnavailable }, 503},
	} {
		t.Run(c.name, func(t *testing.T) {
			f := serve(t, 0)
			id := f.qr(solanaQR("", "", notifyDefault))
			c.set(f.store)
			r, cid := f.notify(note(id))
			body := f.signed(r, c.status, cid)
			if b, _ := json.Marshal(body); strings.Contains(string(b), "must not leak") {
				t.Errorf("the cause leaked: %s", b)
			}
		})
	}
}

// S10.4-AC1: a status the domain decides keeps its HTTP status and its fields, signed: another
// payer's pre-commit is a 409 with currentStatus, a pre-commit on a CANCELLED QR Code a 400.
func TestTheTransitionMapsRefusalsAreSigned(t *testing.T) {
	f := serve(t, 0)
	id := f.qr(solanaQR("", "", notifyDefault))
	r, cid := f.notify(note(id))
	f.signed(r, http.StatusOK, cid)

	r, cid = f.notify(note(id, top("payer", map[string]any{"info": "someone-else@example.com"})))
	body := f.signed(r, http.StatusConflict, cid)
	if body["currentStatus"] != "PAYMENT_INITIATED" {
		t.Errorf("currentStatus %v", body["currentStatus"])
	}

	cancelled := f.qr(solanaQR("", "", notifyDefault))
	f.store.mu.Lock()
	row := f.store.rows[cancelled]
	row.Status = domain.StatusCancelled
	f.store.rows[cancelled] = row
	f.store.mu.Unlock()
	r, cid = f.notify(note(cancelled))
	f.signed(r, http.StatusBadRequest, cid)
}

// OPTIONS is Spring's own answer, with nothing to sign.
func TestOptions(t *testing.T) {
	f := serve(t, 0)
	r := f.post(http.MethodOptions, "", "")
	if r.status != http.StatusOK || r.header.Get("Allow") != "POST,OPTIONS" {
		t.Errorf("OPTIONS: %d %v", r.status, r.header)
	}
}
