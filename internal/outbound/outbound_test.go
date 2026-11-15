// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package outbound

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

const (
	cid       = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
	qrID      = "01A1DC2A6B08D68B0BF382C053CAE802"
	payerFrom = "5xoBq7f7CDgZwqHrDBdRWM84ExRetg4gZq93dyJtoSwp"
	payeeTo   = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
	txHash    = "3n8kLq2Vb7yTfXpR9sWzM4cJhG6dEuA1"
)

type fixedClock struct{ at time.Time }

func (c fixedClock) Now() time.Time { return c.at }

// received is one notification as the payee got it.
type received struct {
	contentType string
	body        string
}

// payee is a test payee: it records what it receives and answers with its handler.
type payee struct {
	mu       sync.Mutex
	got      []received
	answer   http.HandlerFunc
	endpoint string
}

func (p *payee) received() []received {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]received(nil), p.got...)
}

type fixture struct {
	t        *testing.T
	url      string
	payee    *payee
	signer   *jws.Signer
	verifier *jws.Verifier
	store    *keys.Store
}

// serve runs the two routes, signing with the bundled demo key and trusting it, and a payee whose
// answers the test sets. answerTimeout stands in for the 15 s.
func serve(t *testing.T, answerTimeout time.Duration) *fixture {
	t.Helper()
	return serveWith(t, Config{AnswerTimeout: answerTimeout})
}

// serveWith is serve with the delivery's whole configuration.
func serveWith(t *testing.T, cfg Config) *fixture {
	t.Helper()
	logger := slog.New(slog.DiscardHandler)
	store, err := keys.Load(config.Certificate{}, logger)
	if err != nil {
		t.Fatal(err)
	}
	clk := fixedClock{at: time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC)}
	signer, err := jws.New(jws.Config{Publication: jws.PublishChain}, clk)
	if err != nil {
		t.Fatal(err)
	}
	verifier, err := jws.NewVerifier(jws.VerifierConfig{Truststore: store, IssuerName: "X9", Logger: logger}, clk)
	if err != nil {
		t.Fatal(err)
	}
	f := &fixture{t: t, signer: signer, verifier: verifier, store: store}

	p := &payee{}
	p.answer = func(w http.ResponseWriter, r *http.Request) { f.signedAnswer(w, r, http.StatusOK) }
	payeeSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		p.mu.Lock()
		p.got = append(p.got, received{contentType: r.Header.Get("Content-Type"), body: string(b)})
		answer := p.answer
		p.mu.Unlock()
		r.Body = io.NopCloser(strings.NewReader(string(b)))
		answer(w, r)
	}))
	p.endpoint = payeeSrv.URL + "/pub/api/v1/payment-notification"
	f.payee = p

	api := httpapi.New(logger, clk.Now)
	svc := New(store, signer, verifier, logger, cfg)
	svc.Register(api)
	srv := httptest.NewServer(api)
	f.url = srv.URL
	t.Cleanup(func() {
		srv.Close()
		svc.CloseIdleConnections()
		payeeSrv.Close()
	})
	return f
}

// signedAnswer answers as ANSI X9.150 A.4.5 has a payee answer: {"statusCode": n}, signed, under
// the notification's own correlationId.
func (f *fixture) signedAnswer(w http.ResponseWriter, r *http.Request, status int) {
	b, _ := io.ReadAll(r.Body)
	v, err := f.verifier.Verify(r.Context(), string(b), jws.Expect{})
	if err != nil {
		http.Error(w, "the test payee could not read the notification", http.StatusInternalServerError)
		return
	}
	f.answerWith(w, v.Header.CorrelationID, status, status)
}

func (f *fixture) answerWith(w http.ResponseWriter, correlationID uuid.UUID, signed, httpStatus int) {
	token, err := f.signer.SignAnswer(f.store.Current().Identity, correlationID, signed)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", ContentTypeJOSE)
	w.WriteHeader(httpStatus)
	_, _ = io.WriteString(w, token)
}

type reply struct {
	status int
	body   map[string]any
	raw    string
}

func (f *fixture) post(path string, body any) reply {
	f.t.Helper()
	raw, err := json.Marshal(body)
	if err != nil {
		f.t.Fatal(err)
	}
	req, err := http.NewRequestWithContext(f.t.Context(), http.MethodPost, f.url+path, strings.NewReader(string(raw)))
	if err != nil {
		f.t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		f.t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		f.t.Fatal(err)
	}
	out := reply{status: resp.StatusCode, raw: string(b)}
	_ = json.Unmarshal(b, &out.body)
	return out
}

// request is the body of a pre-payment (no transactionId) or a post-payment (with one, SENT).
func (f *fixture) request(post bool) map[string]any {
	payment := map[string]any{"qrcodeId": qrID, "amount": 1000, "currency": "USDC", "network": "solana"}
	action := "PAYMENT_INITIATED"
	if post {
		payment["transactionId"] = txHash
		action = "SENT"
	}
	return map[string]any{
		"endpoint":      f.payee.endpoint,
		"correlationId": cid,
		"notification": map[string]any{
			"payment":    payment,
			"payer":      map[string]any{"info": "outbound@example.com"},
			"blockchain": map[string]any{"action": action, "from": payerFrom, "to": payeeTo},
		},
	}
}

func path(post bool) string {
	if post {
		return PostPaymentPath
	}
	return PrePaymentPath
}

// S10.6-AC1, S10.6-AC3: the notification is composed in the wire shape, signed with this
// deployment's key under the caller's correlationId, posted as application/jose; the payee's signed
// yes is reported VERIFIED, accepted, its body verbatim.
func TestTheNotificationIsSignedAndPostedAsJOSE(t *testing.T) {
	for _, post := range []bool{false, true} {
		t.Run(path(post), func(t *testing.T) {
			f := serve(t, 5*time.Second)

			got := f.post(path(post), f.request(post))

			if got.status != http.StatusOK {
				t.Fatalf("status %d: %s", got.status, got.raw)
			}
			deliveries := f.payee.received()
			if len(deliveries) != 1 {
				t.Fatalf("%d deliveries, want 1", len(deliveries))
			}
			if deliveries[0].contentType != ContentTypeJOSE {
				t.Errorf("Content-Type %q, want %q", deliveries[0].contentType, ContentTypeJOSE)
			}
			v, err := f.verifier.Verify(t.Context(), deliveries[0].body, jws.Expect{CorrelationID: uuid.MustParse(cid)})
			if err != nil {
				t.Fatalf("the notification sent does not verify: %v", err)
			}
			tx := ""
			action := "PAYMENT_INITIATED"
			if post {
				tx, action = `,"transactionId":"`+txHash+`"`, "SENT"
			}
			want := `{"payment":{"qrcodeId":"` + qrID + `","amount":1000,"currency":"USDC","network":"solana"` + tx +
				`},"payer":{"info":"outbound@example.com"},"blockchain":{"action":"` + action + `","from":"` + payerFrom +
				`","to":"` + payeeTo + `"}}`
			if string(v.Payload) != want {
				t.Errorf("signed payload\n got %s\nwant %s", v.Payload, want)
			}
			wantReport := map[string]any{"statusCode": 200.0, "accepted": true, "signature": "VERIFIED"}
			body, _ := got.body["body"].(string)
			delete(got.body, "body")
			if diff := cmp.Diff(wantReport, got.body); diff != "" {
				t.Errorf("report (-want +got):\n%s", diff)
			}
			if !strings.HasPrefix(body, "ey") {
				t.Errorf("body %q is not the payee's JWS, verbatim", body)
			}
		})
	}
}

// S10.6-AC1: the notification's optional members are written when sent, and only then; a blank
// transactionId is no reference and is not written.
func TestTheWireNotificationCarriesWhatWasSent(t *testing.T) {
	f := serve(t, 5*time.Second)
	req := f.request(false)
	note := req["notification"].(map[string]any)
	note["payment"].(map[string]any)["tipAmount"] = 200
	note["payment"].(map[string]any)["transactionId"] = ""
	note["expectedDate"] = "2026-11-16T09:30:00.500Z"
	note["payer"] = map[string]any{}
	delete(req, "correlationId")

	got := f.post(PrePaymentPath, req)

	if got.status != http.StatusOK {
		t.Fatalf("status %d: %s", got.status, got.raw)
	}
	v, err := f.verifier.Verify(t.Context(), f.payee.received()[0].body, jws.Expect{})
	if err != nil {
		t.Fatal(err)
	}
	if v.Header.CorrelationID == uuid.Nil {
		t.Error("no correlationId was generated for a request without one")
	}
	want := `{"payment":{"qrcodeId":"` + qrID + `","amount":1000,"tipAmount":200,"currency":"USDC","network":"solana"},` +
		`"payer":{},"expectedDate":"2026-11-16T09:30:00.500Z","blockchain":{"action":"PAYMENT_INITIATED","from":"` +
		payerFrom + `","to":"` + payeeTo + `"}}`
	if string(v.Payload) != want {
		t.Errorf("signed payload\n got %s\nwant %s", v.Payload, want)
	}
	if got.body["signature"] != "VERIFIED" {
		t.Errorf("the answer to a generated correlationId is %v, want VERIFIED", got.body)
	}
}

// S10.6-AC1: a pre-payment carrying a transactionId, a post-payment reporting a payment without
// one, and an endpoint that is not an absolute URL with a host are refused, 400, before anything
// is sent. NOT_SENT without a transactionId gives the QR Code back, and is delivered.
func TestAContradictionIsRefusedBeforeSending(t *testing.T) {
	cases := []struct {
		name      string
		post      bool
		edit      func(req map[string]any)
		violation string // "" when delivered
	}{
		{"pre-payment with a transactionId", false, func(req map[string]any) {
			payment(req)["transactionId"] = txHash
		}, "payment.transactionId: " + violationPreWithTransaction},
		{"post-payment SENT without a transactionId", true, func(req map[string]any) {
			delete(payment(req), "transactionId")
		}, "payment.transactionId: " + violationPostWithoutTransaction},
		{"post-payment PAYMENT_INITIATED without a transactionId", true, func(req map[string]any) {
			delete(payment(req), "transactionId")
			blockchain(req)["action"] = "PAYMENT_INITIATED"
		}, "payment.transactionId: " + violationPostWithoutTransaction},
		{"post-payment on a bank rail without a transactionId", true, func(req map[string]any) {
			delete(payment(req), "transactionId")
			delete(req["notification"].(map[string]any), "blockchain")
		}, "payment.transactionId: " + violationPostWithoutTransaction},
		{"post-payment with a blank transactionId", true, func(req map[string]any) {
			payment(req)["transactionId"] = "   "
		}, "payment.transactionId: " + violationPostWithoutTransaction},
		{"relative endpoint", false, func(req map[string]any) {
			req["endpoint"] = "/pub/api/v1/payment-notification"
		}, "endpoint: " + violationEndpoint + "/pub/api/v1/payment-notification"},
		{"endpoint without a scheme", false, func(req map[string]any) {
			req["endpoint"] = "payee.example.com/pub/api/v1/payment-notification"
		}, "endpoint: " + violationEndpoint + "payee.example.com/pub/api/v1/payment-notification"},
		{"endpoint without a host", false, func(req map[string]any) {
			req["endpoint"] = "mailto:payee@example.com"
		}, "endpoint: " + violationEndpoint + "mailto:payee@example.com"},
		{"nil correlationId", false, func(req map[string]any) {
			req["correlationId"] = uuid.Nil.String()
		}, "correlationId: " + violationNilCorrelationID},
		{"post-payment NOT_SENT without a transactionId", true, func(req map[string]any) {
			delete(payment(req), "transactionId")
			blockchain(req)["action"] = "NOT_SENT"
		}, ""},
		// No rail-specific refusal: pre-commit exists on every rail (ADR-0036).
		{"pre-payment on fednow", false, func(req map[string]any) {
			payment(req)["network"] = "fednow"
			payment(req)["currency"] = "USD"
			delete(req["notification"].(map[string]any), "blockchain")
		}, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := serve(t, 5*time.Second)
			req := f.request(tc.post)
			tc.edit(req)

			got := f.post(path(tc.post), req)

			if tc.violation == "" {
				if got.status != http.StatusOK || len(f.payee.received()) != 1 {
					t.Fatalf("status %d, %d deliveries, want 200 and one: %s", got.status, len(f.payee.received()), got.raw)
				}
				return
			}
			if got.status != http.StatusBadRequest || got.body["type"] != httpapi.TypeBusinessRule {
				t.Fatalf("status %d, want a 400 business-rule-violation: %s", got.status, got.raw)
			}
			if diff := cmp.Diff([]any{tc.violation}, got.body["violations"]); diff != "" {
				t.Errorf("violations (-want +got):\n%s", diff)
			}
			if n := len(f.payee.received()); n != 0 {
				t.Errorf("%d deliveries, want none: refused before sending", n)
			}
		})
	}
}

// S10.6-AC1: what the contract refuses is refused before anything is sent, naming the field.
func TestTheContractIsCheckedBeforeSending(t *testing.T) {
	f := serve(t, 5*time.Second)
	req := f.request(false)
	delete(req, "endpoint")
	delete(payment(req), "qrcodeId")

	got := f.post(PrePaymentPath, req)

	if got.status != http.StatusBadRequest || got.body["type"] != httpapi.TypeValidation {
		t.Fatalf("status %d, want a 400 validation-error: %s", got.status, got.raw)
	}
	if diff := cmp.Diff([]any{"endpoint: must not be null", "notification.payment.qrcodeId: must not be null"},
		got.body["violations"]); diff != "" {
		t.Errorf("violations (-want +got):\n%s", diff)
	}
	if n := len(f.payee.received()); n != 0 {
		t.Errorf("%d deliveries, want none", n)
	}
}

// S10.6-AC1, INTERPRETATION I-16: what this service signs is closed at every level, so an unknown
// member is refused by name before anything is sent, never dropped and sent without it.
func TestAnUnknownMemberIsRefusedBeforeSending(t *testing.T) {
	for _, at := range []string{"", "notification.", "notification.payment.", "notification.payer.", "notification.blockchain."} {
		t.Run(at+"x9extra", func(t *testing.T) {
			f := serve(t, 5*time.Second)
			req := f.request(false)
			target := req
			for name := range strings.SplitSeq(strings.TrimSuffix(at, "."), ".") {
				if name != "" {
					target = target[name].(map[string]any)
				}
			}
			target["x9extra"] = true

			got := f.post(PrePaymentPath, req)

			if got.status != http.StatusBadRequest || got.body["type"] != httpapi.TypeValidation {
				t.Fatalf("status %d, want a 400 validation-error: %s", got.status, got.raw)
			}
			violations, _ := got.body["violations"].([]any)
			if len(violations) != 1 || !strings.HasPrefix(violations[0].(string), at+"x9extra: ") {
				t.Errorf("violations %v, want one naming %sx9extra", violations, at)
			}
			if n := len(f.payee.received()); n != 0 {
				t.Errorf("%d deliveries, want none", n)
			}
		})
	}
}

// S10.6-AC2: a redirect is not followed; it is the payee's answer, reported as such.
func TestARedirectIsReportedNotFollowed(t *testing.T) {
	for _, status := range []int{http.StatusFound, http.StatusTemporaryRedirect} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			f := serve(t, 5*time.Second)
			f.payee.answer = func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/redirected" {
					f.signedAnswer(w, r, http.StatusOK)
					return
				}
				http.Redirect(w, r, "/redirected", status)
			}

			got := f.post(PrePaymentPath, f.request(false))

			if got.status != http.StatusOK {
				t.Fatalf("status %d: %s", got.status, got.raw)
			}
			if n := len(f.payee.received()); n != 1 {
				t.Fatalf("%d deliveries: the redirect was followed", n)
			}
			if got.body["statusCode"] != float64(status) || got.body["accepted"] != false {
				t.Errorf("report %v, want statusCode %d, not accepted", got.body, status)
			}
		})
	}
}

// S10.6-AC2: no answer, a closed connection, or a whole answer (headers and body) not received in
// time is a 502 notification-undeliverable.
func TestNoAnswerIsUndeliverable(t *testing.T) {
	cases := map[string]func(w http.ResponseWriter, r *http.Request){
		"the payee hangs up": func(w http.ResponseWriter, _ *http.Request) {
			conn, _, err := w.(http.Hijacker).Hijack()
			if err == nil {
				_ = conn.Close()
			}
		},
		"no headers in time": func(_ http.ResponseWriter, r *http.Request) {
			<-r.Context().Done()
		},
		"headers in time, not the body": func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", ContentTypeJOSE)
			w.Header().Set("Content-Length", "100")
			w.WriteHeader(http.StatusOK)
			_, _ = io.WriteString(w, "eyJ")
			w.(http.Flusher).Flush()
			<-r.Context().Done()
		},
	}
	for name, answer := range cases {
		t.Run(name, func(t *testing.T) {
			f := serve(t, 300*time.Millisecond)
			f.payee.answer = answer

			got := f.post(PrePaymentPath, f.request(false))

			assertUndeliverable(t, got)
			if n := len(f.payee.received()); n != 1 {
				t.Errorf("%d deliveries, want 1: the payee may have received it", n)
			}
		})
	}
	t.Run("nobody listens", func(t *testing.T) {
		f := serve(t, 5*time.Second)
		l, err := (&net.ListenConfig{}).Listen(t.Context(), "tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		addr := l.Addr().String()
		_ = l.Close()
		req := f.request(true)
		req["endpoint"] = "http://" + addr + "/pub/api/v1/payment-notification"

		assertUndeliverable(t, f.post(PostPaymentPath, req))
	})
}

// S10.6-AC2, as the Java build times it (#120): the connection gets its own timeout, and the
// answer timeout starts once the request has been sent. Scaled down from 5 s and 15 s: a slow
// connect followed by an answer inside its own window is a 200 (Java's 4 s connect + 12 s answer),
// though the two together exceed the answer timeout; a connect past its timeout is a 502, and
// nothing reaches the payee.
func TestTheAnswerTimeoutStartsOnceTheRequestIsSent(t *testing.T) {
	slowDial := func(delay time.Duration) func(ctx context.Context, network, addr string) (net.Conn, error) {
		return func(ctx context.Context, network, addr string) (net.Conn, error) {
			time.Sleep(delay)
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			return (&net.Dialer{}).DialContext(ctx, network, addr)
		}
	}
	t.Run("a slow connect, then an answer in time", func(t *testing.T) {
		f := serveWith(t, Config{ConnectTimeout: 2 * time.Second, AnswerTimeout: 400 * time.Millisecond,
			DialContext: slowDial(300 * time.Millisecond)})
		f.payee.answer = func(w http.ResponseWriter, r *http.Request) {
			time.Sleep(250 * time.Millisecond)
			f.signedAnswer(w, r, http.StatusOK)
		}

		got := f.post(PrePaymentPath, f.request(false))

		if got.status != http.StatusOK || got.body["signature"] != "VERIFIED" {
			t.Fatalf("status %d, want 200 VERIFIED: %s", got.status, got.raw)
		}
	})
	t.Run("a connect past its timeout", func(t *testing.T) {
		f := serveWith(t, Config{ConnectTimeout: 150 * time.Millisecond, AnswerTimeout: 5 * time.Second,
			DialContext: slowDial(300 * time.Millisecond)})

		assertUndeliverable(t, f.post(PrePaymentPath, f.request(false)))
		if n := len(f.payee.received()); n != 0 {
			t.Errorf("%d deliveries, want none", n)
		}
	})
}

func assertUndeliverable(t *testing.T, got reply) {
	t.Helper()
	if got.status != http.StatusBadGateway || got.body["type"] != httpapi.TypeNotificationUndeliverable {
		t.Fatalf("status %d, want a 502 notification-undeliverable: %s", got.status, got.raw)
	}
	if _, ok := got.body["accepted"]; ok {
		t.Errorf("a 502 carries no verdict: %s", got.raw)
	}
}

// S10.6-AC3: the payee's answer is reported, never refused: a signed refusal is VERIFIED and not
// accepted; an unsigned yes is still accepted; the correlationId is checked against the one sent,
// the caller's or the one generated when the caller gave none (ADR-0027, amendment of 2026-11-15).
func TestTheAnswerIsReported(t *testing.T) {
	cases := []struct {
		name   string
		answer func(f *fixture) http.HandlerFunc
		want   map[string]any
		body   bool
		// generated: the caller gives no correlationId, so the one sent is this service's.
		generated bool
	}{
		{"a signed refusal", func(f *fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, r *http.Request) { f.signedAnswer(w, r, http.StatusConflict) }
		}, map[string]any{"statusCode": 409.0, "accepted": false, "signature": "VERIFIED"}, true, false},
		{"an unsigned yes", func(*fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				_, _ = io.WriteString(w, `{"statusCode":200}`)
			}
		}, map[string]any{"statusCode": 200.0, "accepted": true, "signature": "ABSENT", "signatureProblem": "NOT_JWS"}, true, false},
		{"an empty yes", func(*fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) }
		}, map[string]any{"statusCode": 200.0, "accepted": true, "signature": "ABSENT", "signatureProblem": "ABSENT_BODY"}, false, false},
		{"a yes to another notification", func(f *fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, _ *http.Request) { f.answerWith(w, uuid.New(), 200, 200) }
		}, map[string]any{"statusCode": 200.0, "accepted": true, "signature": "INVALID", "signatureProblem": "CORRELATION_ID_MISMATCH"}, true, false},
		{"a yes under another correlationId than the one generated", func(f *fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, _ *http.Request) { f.answerWith(w, uuid.MustParse(cid), 200, 200) }
		}, map[string]any{"statusCode": 200.0, "accepted": true, "signature": "INVALID", "signatureProblem": "CORRELATION_ID_MISMATCH"}, true, true},
		{"a yes to the one generated", func(f *fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, r *http.Request) { f.signedAnswer(w, r, http.StatusOK) }
		}, map[string]any{"statusCode": 200.0, "accepted": true, "signature": "VERIFIED"}, true, true},
		{"a signed yes sent with a 400", func(f *fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, _ *http.Request) { f.answerWith(w, uuid.MustParse(cid), 200, 400) }
		}, map[string]any{"statusCode": 400.0, "accepted": false, "signature": "INVALID", "signatureProblem": "STATUS_MISMATCH"}, true, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := serve(t, 5*time.Second)
			f.payee.answer = tc.answer(f)
			req := f.request(false)
			if tc.generated {
				delete(req, "correlationId")
			}

			got := f.post(PrePaymentPath, req)

			if got.status != http.StatusOK {
				t.Fatalf("status %d: %s", got.status, got.raw)
			}
			_, hasBody := got.body["body"]
			detail, _ := got.body["signatureDetail"].(string)
			delete(got.body, "body")
			delete(got.body, "signatureDetail")
			if diff := cmp.Diff(tc.want, got.body); diff != "" {
				t.Errorf("report (-want +got):\n%s", diff)
			}
			if hasBody != tc.body {
				t.Errorf("body present %v, want %v", hasBody, tc.body)
			}
			if verified := tc.want["signature"] == "VERIFIED"; verified == (detail != "") {
				t.Errorf("signatureDetail %q: the words travel with the code, and only with it", detail)
			}
		})
	}
}

func payment(req map[string]any) map[string]any {
	return req["notification"].(map[string]any)["payment"].(map[string]any)
}

func blockchain(req map[string]any) map[string]any {
	return req["notification"].(map[string]any)["blockchain"].(map[string]any)
}
