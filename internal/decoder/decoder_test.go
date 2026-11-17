// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package decoder

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"io"
	"log/slog"
	"maps"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/emv"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
	"github.com/matera-inc/x9.150-qrcode/internal/outbound"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

const (
	cid        = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
	locationID = "01A1DC2A6A37D73D6313076E9BFE04F5"
	// ownHost is this deployment's public host, as configured: a port, and a case the EMV need not
	// share.
	ownHost = "X9.Example.Test:8443"
)

type fixedClock struct{ at time.Time }

func (c fixedClock) Now() time.Time { return c.at }

// question is one payload request as a host received it.
type question struct {
	path, contentType, dateForPayment, authorization string
	body                                             string
}

// host is a payload host: it records what it receives and answers with its handler.
type host struct {
	mu     sync.Mutex
	got    []question
	answer http.HandlerFunc
}

func (h *host) received() []question {
	h.mu.Lock()
	defer h.mu.Unlock()
	return append([]question(nil), h.got...)
}

func (h *host) answerWith(a http.HandlerFunc) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.answer = a
}

func (h *host) serve(w http.ResponseWriter, r *http.Request) {
	b, _ := io.ReadAll(r.Body)
	h.mu.Lock()
	h.got = append(h.got, question{
		path: r.URL.RequestURI(), contentType: r.Header.Get("Content-Type"),
		dateForPayment: r.Header.Get("dateForPayment"), authorization: r.Header.Get("Authorization"),
		body: string(b),
	})
	answer := h.answer
	h.mu.Unlock()
	answer(w, r)
}

type fixture struct {
	t        *testing.T
	url      string
	signer   *jws.Signer
	verifier *jws.Verifier
	store    *keys.Store
	// bank is another bank's payload host, over https; own is this deployment's, read internally.
	bank, own         *host
	bankHost, ownBase string
}

// serve runs the decoder, signing with the bundled demo key and trusting it, beside another bank's
// host (TLS, trusted through the client's roots) and this deployment's own payload route.
func serve(t *testing.T) *fixture {
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
	f := &fixture{t: t, signer: signer, verifier: verifier, store: store, bank: &host{}, own: &host{}}
	f.bank.answer = f.payloadAnswer(foreignPayload)
	f.own.answer = f.payloadAnswer(foreignPayload)
	bankSrv := httptest.NewTLSServer(http.HandlerFunc(f.bank.serve))
	ownSrv := httptest.NewServer(http.HandlerFunc(f.own.serve))
	f.bankHost, f.ownBase = bankSrv.Listener.Addr().String(), ownSrv.URL

	client := outbound.NewClient(outbound.Config{
		AnswerTimeout: 2 * time.Second,
		TLSConfig:     bankSrv.Client().Transport.(*http.Transport).TLSClientConfig,
	})
	svc, err := New(store, signer, verifier, client, Config{OwnHost: ownHost, OwnBaseURL: ownSrv.URL}, logger)
	if err != nil {
		t.Fatal(err)
	}
	api := httpapi.New(logger, clk.Now)
	svc.Register(api)
	srv := httptest.NewServer(api)
	f.url = srv.URL
	t.Cleanup(func() {
		srv.Close()
		client.CloseIdleConnections()
		bankSrv.Close()
		ownSrv.Close()
	})
	return f
}

// payloadAnswer answers a payload request with payload, signed under the request's correlationId.
func (f *fixture) payloadAnswer(payload string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		got := f.received(r)
		f.sign(r.Context(), w, payload, got.Header.CorrelationID)
	}
}

// received verifies the payload request the host just recorded.
func (f *fixture) received(r *http.Request) *jws.Verified {
	var last question
	for _, h := range []*host{f.bank, f.own} {
		if got := h.received(); len(got) > 0 && got[len(got)-1].path == r.URL.RequestURI() {
			last = got[len(got)-1]
		}
	}
	v, err := f.verifier.Verify(r.Context(), last.body, jws.Expect{})
	if err != nil {
		f.t.Errorf("the payload request did not verify: %v", err)
		return &jws.Verified{}
	}
	return v
}

func (f *fixture) sign(ctx context.Context, w http.ResponseWriter, payload string, correlationID uuid.UUID) {
	token, err := f.signer.Sign(ctx, f.store.Current().Identity, jws.Message{
		Payload: []byte(payload), CorrelationID: correlationID, StatusCode: http.StatusOK,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/jose;charset=UTF-8")
	_, _ = io.WriteString(w, token)
}

// signWithoutCorrelationID answers with payload signed by the demo key under a header that names
// no correlationId, in crit or out of it: a signature that verifies, on an answer to nobody.
func (f *fixture) signWithoutCorrelationID(ctx context.Context, w http.ResponseWriter, payload string) {
	token, err := f.signer.Sign(ctx, f.store.Current().Identity, jws.Message{
		Payload: []byte(payload), CorrelationID: uuid.New(), StatusCode: http.StatusOK,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	var header map[string]any
	raw, err := base64.RawURLEncoding.DecodeString(strings.Split(token, ".")[0])
	if err == nil {
		err = json.Unmarshal(raw, &header)
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	delete(header, jws.HeaderCorrelationID)
	header["crit"] = []string{jws.HeaderIssuedAt, jws.HeaderTTL, jws.HeaderStatusCode}
	h, err := json.Marshal(header)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	input := base64.RawURLEncoding.EncodeToString(h) + "." + base64.RawURLEncoding.EncodeToString([]byte(payload))
	alg, _ := header["alg"].(string)
	hashes := map[string]crypto.Hash{"256": crypto.SHA256, "384": crypto.SHA384, "512": crypto.SHA512}
	hash, ok := hashes[strings.TrimLeft(alg, "RPS")]
	if !ok || !strings.HasPrefix(alg, "RS") && !strings.HasPrefix(alg, "PS") {
		f.t.Errorf("the demo key signs with %q; this helper re-signs RS and PS only", alg)
		return
	}
	digest := hash.New()
	digest.Write([]byte(input))
	var opts crypto.SignerOpts = hash
	if strings.HasPrefix(alg, "PS") {
		opts = &rsa.PSSOptions{SaltLength: rsa.PSSSaltLengthEqualsHash, Hash: hash}
	}
	sig, err := f.store.Current().Identity.Sign(rand.Reader, digest.Sum(nil), opts)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/jose;charset=UTF-8")
	_, _ = io.WriteString(w, input+"."+base64.RawURLEncoding.EncodeToString(sig))
}

type reply struct {
	status int
	body   map[string]any
	raw    string
}

func (f *fixture) decode(body any) reply {
	f.t.Helper()
	raw, err := json.Marshal(body)
	if err != nil {
		f.t.Fatal(err)
	}
	req, err := http.NewRequestWithContext(f.t.Context(), http.MethodPost, f.url+Path, strings.NewReader(string(raw)))
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

// qrCode is an EMV string whose org.x9 template carries url.
func qrCode(t *testing.T, url string) string {
	t.Helper()
	s, err := emv.Encode(url, emv.Merchant{CategoryCode: "5812", Country: "US", Name: "Test", City: "Austin"})
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func (f *fixture) bankCode() string { return qrCode(f.t, emv.LocationURL(f.bankHost, locationID)) }

func violations(r reply) []string {
	list, _ := r.body["violations"].([]any)
	out := make([]string, 0, len(list))
	for _, v := range list {
		s, _ := v.(string)
		out = append(out, s)
	}
	return out
}

func assertProblem(t *testing.T, got reply, status int, typ string, want ...string) {
	t.Helper()
	if got.status != status || got.body["type"] != typ {
		t.Fatalf("status %d type %v, want %d %s: %s", got.status, got.body["type"], status, typ, got.raw)
	}
	if want != nil {
		if diff := cmp.Diff(want, violations(got)); diff != "" {
			t.Errorf("violations (-want +got):\n%s", diff)
		}
	}
}

// withCRC appends tag 63 with the right CRC, so a case fails for its own defect only.
func withCRC(body string) string {
	body += "6304"
	return body + hex4(emv.CRC16([]byte(body)))
}

func hex4(v uint16) string {
	const digits = "0123456789ABCDEF"
	return string([]byte{digits[v>>12], digits[v>>8&0xF], digits[v>>4&0xF], digits[v&0xF]})
}

// S10.7-AC1: malformed EMV is a 400 business-rule-violation naming qrCode, in the Java build's
// words, before anything is fetched; a well-formed string with no org.x9 URL is refused saying so.
// The cases are internal/emv's TestDecodeRefusesMalformedInput, which the black-box X9-DEC-016
// repeats by name.
func TestMalformedEMVIsRefusedBeforeAnyFetch(t *testing.T) {
	f := serve(t)
	good := f.bankCode()
	x9 := "26800006org.x90166x9-150.example.com/pub/api/v1/loc/01A1DC2A6A37D73D6313076E9BFE04F5"
	const noURL = "EMV QR Code does not contain a valid payload URL."
	malformed := func(reason string) string { return "qrCode: The QR Code is malformed: " + reason + "." }
	for _, tc := range []struct {
		name, in, want string
	}{
		{"empty", "", malformed("it is empty")},
		{"bad CRC", good[:len(good)-4] + "0000", malformed("the CRC (tag 63) does not match its content")},
		{"CRC not hex", good[:len(good)-4] + "ZZZZ", malformed("the CRC (tag 63) is not four hex digits")},
		{"no CRC", good[:len(good)-8], malformed("there is no CRC (tag 63) at the end")},
		{"CRC not last", withCRC("6304AAAA" + "000201"), malformed("the CRC (tag 63) is not the last field")},
		{"CRC too short", withCRC("000201")[:len(withCRC("000201"))-8] + "6303ABC",
			malformed("the CRC (tag 63) has 3 characters, not 4")},
		{"truncated", good[:len(good)-30], malformed("the field at offset 113 is truncated")},
		{"truncated header", "000", malformed("the field at offset 0 is truncated")},
		{"length beyond the end", "000501", malformed("tag 00 declares 05 characters, and fewer are left")},
		{"length not digits", withCRC("00AB01"), malformed("tag 00 has a length that is not 01 to 99")},
		{"length zero", withCRC("0000" + "000201"), malformed("tag 00 has a length that is not 01 to 99")},
		{"tag not digits", withCRC("A00201"), malformed("the tag at offset 0 is not two digits")},
		{"duplicate tag", withCRC("000201000201" + x9), malformed("tag 00 appears twice")},
		{"no template", withCRC("000201010212"), noURL},
		{"wrong GUI", withCRC("000201" + "26220006org.x80108x9.a/loc"), noURL},
		{"org.x9 without URL", withCRC("000201" + "26100006org.x9"), noURL},
		{"template not TLV", withCRC("000201" + "2603abc"), malformed("in tag 26, the field at offset 0 is truncated")},
		{"template truncated", withCRC("000201" + "26140006org.x90199"),
			malformed("in tag 26, tag 01 declares 99 characters, and fewer are left")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := f.decode(map[string]any{"qrCode": tc.in})
			assertProblem(t, got, http.StatusBadRequest, httpapi.TypeBusinessRule, tc.want)
		})
	}
	if n := len(f.bank.received()) + len(f.own.received()); n != 0 {
		t.Fatalf("%d payloads fetched for strings that were refused", n)
	}
}

// X9-DEC-515 (ADR-0028, a known difference between the builds): the nil UUID as correlationID is a
// 400, nothing fetched (TOLERATED-DIFFERENCES.md, entry 20).
//
// The request's own contract: qrCode is required; a body that is not JSON is malformed-json.
func TestTheRequestContractIsChecked(t *testing.T) {
	f := serve(t)
	assertProblem(t, f.decode(map[string]any{}), http.StatusBadRequest, httpapi.TypeValidation,
		"qrCode: must not be null")
	assertProblem(t, f.decode(map[string]any{"qrCode": f.bankCode(), "correlationID": uuid.Nil.String()}),
		http.StatusBadRequest, httpapi.TypeBusinessRule,
		"correlationID: The correlationID must not be the nil UUID: the payee's payload is matched to it.")
	if n := len(f.bank.received()); n != 0 {
		t.Fatalf("%d payloads fetched for requests that were refused", n)
	}
}

// S10.7-AC2: the payload request is signed with this deployment's key, carries the scanned string
// as qrCodeContent, the caller's correlationID and dateForPayment, and is posted as
// application/jose. A URL on this deployment's own host, port included and ignoring case, is read
// internally.
func TestTheOwnHostIsReadInternally(t *testing.T) {
	f := serve(t)
	code := qrCode(t, emv.LocationURL(strings.ToLower(ownHost), locationID))
	got := f.decode(map[string]any{"qrCode": code, "correlationID": cid, "dateForPayment": "2030-12-21"})
	if got.status != http.StatusOK {
		t.Fatalf("status %d: %s", got.status, got.raw)
	}
	asked := f.own.received()
	if len(asked) != 1 || len(f.bank.received()) != 0 {
		t.Fatalf("own %d, bank %d payload requests, want 1 and 0", len(asked), len(f.bank.received()))
	}
	q := asked[0]
	if q.path != "/pub/api/v1/loc/"+locationID || q.contentType != "application/jose" || q.dateForPayment != "2030-12-21" {
		t.Errorf("request %+v", q)
	}
	v, err := f.verifier.Verify(t.Context(), q.body, jws.Expect{CorrelationID: uuid.MustParse(cid)})
	if err != nil {
		t.Fatalf("the payload request does not verify under the caller's correlationID: %v", err)
	}
	var payload map[string]string
	if err := json.Unmarshal(v.Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if want := base64.StdEncoding.EncodeToString([]byte(code)); payload["qrCodeContent"] != want || len(payload) != 1 {
		t.Errorf("payload request %s, want qrCodeContent %s", v.Payload, want)
	}
}

// S10.7-AC2: any other host is fetched over https, whatever scheme the URL was written with,
// without its user information; a URL that only mentions the own host is another host's.
func TestAnotherHostIsFetchedOverHTTPS(t *testing.T) {
	for _, tc := range []struct{ name, url string }{
		{"no scheme", "%s/pub/api/v1/loc/" + locationID},
		{"http", "http://%s/pub/api/v1/loc/" + locationID},
		{"user information", "http://user:secret@%s/pub/api/v1/loc/" + locationID},
		{"the own host in the path", "%s/" + ownHost + "/loc/" + locationID},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := serve(t)
			got := f.decode(map[string]any{"qrCode": qrCode(t, strings.Replace(tc.url, "%s", f.bankHost, 1))})
			if got.status != http.StatusOK {
				t.Fatalf("status %d: %s", got.status, got.raw)
			}
			asked := f.bank.received()
			if len(asked) != 1 || len(f.own.received()) != 0 {
				t.Fatalf("bank %d, own %d payload requests, want 1 and 0", len(asked), len(f.own.received()))
			}
			if asked[0].authorization != "" {
				t.Errorf("a credential was sent: %q", asked[0].authorization)
			}
		})
	}
}

func TestTargets(t *testing.T) {
	ts, err := newTargets(" x9.example.test:8443 ", "http://localhost:8080")
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct{ in, want, refused string }{
		{"x9.example.test:8443/pub/api/v1/loc/A?x=1", "http://localhost:8080/pub/api/v1/loc/A?x=1", ""},
		{"HTTPS://X9.EXAMPLE.TEST:8443/pub/api/v1/loc/A", "http://localhost:8080/pub/api/v1/loc/A", ""},
		{"x9.example.test/pub/api/v1/loc/A", "https://x9.example.test/pub/api/v1/loc/A", ""},
		{"x9.example.test.evil.example:8443/loc/A", "https://x9.example.test.evil.example:8443/loc/A", ""},
		{"x9.example.test:8443.evil.example/loc/A", "", "Invalid URI syntax: x9.example.test:8443.evil.example/loc/A"},
		{"evil.example/x9.example.test:8443/loc/A", "https://evil.example/x9.example.test:8443/loc/A", ""},
		{"http://x9.example.test:8443@evil.example/loc/A#f", "https://evil.example/loc/A", ""},
		{"http://payee.example:8080/loc/A", "https://payee.example:8080/loc/A", ""},
		{"ftp://payee.example/loc/A", "", "The scheme ftp is not http or https: ftp://payee.example/loc/A"},
		{"https:///loc/A", "", "The URL has no host: https:///loc/A"},
		{"payee example/loc", "", "Invalid URI syntax: payee example/loc"},
	} {
		got, refused := ts.resolve(tc.in)
		if refused != tc.refused {
			t.Errorf("%s: refused %q, want %q", tc.in, refused, tc.refused)
			continue
		}
		if got != nil && got.String() != tc.want {
			t.Errorf("%s: %s, want %s", tc.in, got, tc.want)
		}
	}
}

// S10.7-AC3: a payee's refusal is relayed with its own status, as payload-refused, its reasons
// reduced to plain strings.
func TestARefusalIsRelayed(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   []string
	}{
		{"paid", 400, `{"type":"https://x9.matera.com/api/business-rule-violation","violations":["payment payload with ID: X has already been paid."]}`,
			[]string{"payment payload with ID: X has already been paid."}},
		{"another 4xx", 404, `{"violations":"gone"}`, []string{"gone"}},
		{"detail only", 409, `{"detail":"taken"}`, []string{"taken"}},
		{"not a problem", 400, `<html>no</html>`, []string{refusedWithoutReason}},
		{"empty", 403, ``, []string{refusedWithoutReason}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := serve(t)
			f.bank.answerWith(func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "application/problem+json")
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, tc.body)
			})
			got := f.decode(map[string]any{"qrCode": f.bankCode()})
			assertProblem(t, got, tc.status, httpapi.TypePayloadRefused, tc.want...)
		})
	}
}

func TestRelayedReasonsAreCappedAndPlain(t *testing.T) {
	long := strings.Repeat("é", 301)
	many := make([]string, 12)
	for i := range many {
		many[i] = `"r"`
	}
	for _, tc := range []struct {
		name, body string
		want       []string
	}{
		{"a link", `{"violations":["Pay at https://evil.example/pay now, or www.evil.example, or evil.com/x"]}`,
			[]string{"Pay at [link removed] now, or [link removed] or [link removed]"}},
		{"control characters", `{"violations":["a\u001b[2Jb\n\n  c "]}`, []string{"a [2Jb c"}},
		{"only spaces", `{"violations":["  \t "]}`, []string{refusedWithoutReason}},
		{"long", `{"violations":["` + long + `"]}`, []string{strings.Repeat("é", 300) + "…"}},
		{"ten at most", `{"violations":[` + strings.Join(many, ",") + `]}`, strings.Split(strings.Repeat("r ", 10), " ")[:10]},
		{"strings only", `{"violations":[1,{"a":"b"},"kept",null]}`, []string{"kept"}},
		{"no strings", `{"violations":[1],"detail":"the detail"}`, []string{"the detail"}},
		{"not an object", `["a"]`, []string{refusedWithoutReason}},
	} {
		if diff := cmp.Diff(tc.want, relayed(tc.body)); diff != "" {
			t.Errorf("%s (-want +got):\n%s", tc.name, diff)
		}
	}
}

// X9-DEC-512 (ADR-0028, a known difference between the builds): a payee that does not answer within
// the bounds is a 502 payload-unreachable (TOLERATED-DIFFERENCES.md, entry 17).
//
// S10.7-AC3: no answer, or a 5xx, is a 502 payload-unreachable that carries no address.
func TestNoAnswerIsUnreachable(t *testing.T) {
	for _, tc := range []struct {
		name   string
		answer http.HandlerFunc
	}{
		{"5xx", func(w http.ResponseWriter, _ *http.Request) {
			http.Error(w, "down at 10.0.0.1", http.StatusServiceUnavailable)
		}},
		{"closed", func(w http.ResponseWriter, _ *http.Request) {
			conn, _, err := http.NewResponseController(w).Hijack()
			if err == nil {
				_ = conn.Close()
			}
		}},
		{"too slow", func(w http.ResponseWriter, r *http.Request) {
			select {
			case <-r.Context().Done():
			case <-time.After(5 * time.Second): //nolint:forbidigo // a real delay past the answer timeout
			}
			w.WriteHeader(http.StatusOK)
		}},
		{"too large", func(w http.ResponseWriter, _ *http.Request) {
			_, _ = io.WriteString(w, strings.Repeat("a", outbound.DefaultMaxAnswerBytes+1))
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := serve(t)
			f.bank.answerWith(tc.answer)
			got := f.decode(map[string]any{"qrCode": f.bankCode()})
			assertProblem(t, got, http.StatusBadGateway, httpapi.TypePayloadUnreachable)
			if strings.Contains(got.raw, "127.0.0.1") || strings.Contains(got.raw, "10.0.0.1") || got.body["violations"] != nil {
				t.Errorf("the answer carries an address or a reason: %s", got.raw)
			}
		})
	}
}

// S10.7-AC2 and AC5: tolerance stops at integrity. A payload whose signature does not verify, one
// that is not signed, and one signed under no correlationId or under another than the request was
// sent under (the caller's, or the one generated when the caller gave none: ADR-0027, amendment of
// 2026-11-15) are refused, and nothing of them is presented. The last two are refused in their own
// words, "QR Code is invalid (bad correlationId)" (owner, 2026-11-15): trying again may succeed, and
// nothing in the caller's input can fix it. The verifier's verdict comes first: an altered payload
// under another correlationId is a bad signature.
func TestAPayloadThatDoesNotVerifyIsRefused(t *testing.T) {
	const (
		invalid = "Payload JWS signature is invalid."
		badID   = "QR Code is invalid (bad correlationId)"
	)
	altered := func(f *fixture, correlationID func(r *http.Request) uuid.UUID) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			rec := httptest.NewRecorder()
			f.sign(r.Context(), rec, foreignPayload, correlationID(r))
			parts := strings.Split(rec.Body.String(), ".")
			parts[1] = base64.RawURLEncoding.EncodeToString([]byte(strings.Replace(foreignPayload, "1250", "1", 1)))
			_, _ = io.WriteString(w, strings.Join(parts, "."))
		}
	}
	for _, tc := range []struct {
		name   string
		answer func(f *fixture) http.HandlerFunc
		body   map[string]any
		want   string
	}{
		{"altered", func(f *fixture) http.HandlerFunc {
			return altered(f, func(r *http.Request) uuid.UUID { return f.received(r).Header.CorrelationID })
		}, nil, invalid},
		{"altered, under another correlationID", func(f *fixture) http.HandlerFunc {
			return altered(f, func(*http.Request) uuid.UUID { return uuid.New() })
		}, nil, invalid},
		{"unsigned", func(*fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, foreignPayload) }
		}, nil, invalid},
		{"another correlationID than the caller's", func(f *fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, r *http.Request) { f.sign(r.Context(), w, foreignPayload, uuid.New()) }
		}, map[string]any{"correlationID": cid}, badID},
		{"another correlationID than the one generated", func(f *fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, r *http.Request) { f.sign(r.Context(), w, foreignPayload, uuid.New()) }
		}, nil, badID},
		{"no correlationID, none given", func(f *fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, r *http.Request) {
				f.signWithoutCorrelationID(r.Context(), w, foreignPayload)
			}
		}, nil, badID},
		{"no correlationID, the caller's given", func(f *fixture) http.HandlerFunc {
			return func(w http.ResponseWriter, r *http.Request) {
				f.signWithoutCorrelationID(r.Context(), w, foreignPayload)
			}
		}, map[string]any{"correlationID": cid}, badID},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := serve(t)
			f.bank.answerWith(tc.answer(f))
			body := map[string]any{"qrCode": f.bankCode()}
			maps.Copy(body, tc.body)
			got := f.decode(body)
			assertProblem(t, got, http.StatusBadRequest, httpapi.TypeBusinessRule, tc.want)
			if strings.Contains(got.raw, "0196cb82afab46f1bf8dfff2f9c8a540") {
				t.Errorf("the payload leaked: %s", got.raw)
			}
		})
	}
}

// ADR-0028, known difference 22 (the black-box X9-DEC-510): a 3xx from the payee is not followed,
// though where it points would serve a good payload. Its own body is checked as the payload's JWS,
// and refused as an invalid signature.
func TestARedirectIsNotFollowed(t *testing.T) {
	for _, status := range []int{http.StatusFound, http.StatusTemporaryRedirect} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			f := serve(t)
			good := f.payloadAnswer(foreignPayload)
			f.bank.answerWith(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/redirected" {
					good(w, r)
					return
				}
				http.Redirect(w, r, "/redirected", status)
			})

			got := f.decode(map[string]any{"qrCode": f.bankCode()})

			assertProblem(t, got, http.StatusBadRequest, httpapi.TypeBusinessRule, "Payload JWS signature is invalid.")
			if n := len(f.bank.received()); n != 1 {
				t.Errorf("%d payload requests: the redirect was followed", n)
			}
		})
	}
}

// The answer signWithoutCorrelationID gives fails for its one defect: no correlationId, refused
// whatever is expected, and not a broken signature.
func TestAnAnswerWithoutACorrelationIDIsABadHeader(t *testing.T) {
	f := serve(t)
	rec := httptest.NewRecorder()
	f.signWithoutCorrelationID(t.Context(), rec, foreignPayload)
	_, err := f.verifier.Verify(t.Context(), rec.Body.String(), jws.Expect{})
	if got := jws.ProblemOf(err); got != jws.BadHeader {
		t.Fatalf("problem %q, want %q: %v", got, jws.BadHeader, err)
	}
	if !strings.Contains(err.Error(), "correlationId") {
		t.Errorf("the refusal does not name correlationId: %v", err)
	}
}

// Without a correlationID from the caller, the request goes out under a fresh one, and a payload
// that echoes it decodes (ADR-0027, amendment of 2026-11-15).
func TestWithoutACorrelationIDThePayloadEchoesTheGeneratedOne(t *testing.T) {
	f := serve(t)
	if got := f.decode(map[string]any{"qrCode": f.bankCode()}); got.status != http.StatusOK {
		t.Fatalf("status %d: %s", got.status, got.raw)
	}
	asked := f.bank.received()
	if len(asked) != 1 {
		t.Fatalf("%d payload requests, want 1", len(asked))
	}
	v, err := f.verifier.Verify(t.Context(), asked[0].body, jws.Expect{})
	if err != nil {
		t.Fatal(err)
	}
	if v.Header.CorrelationID == uuid.Nil {
		t.Error("the payload request went out under no correlationId")
	}
}

// S10.7-AC3, S10.7-AC5: another bank's payload is a 200 PaymentPayloadResponse: an unknown rail
// and an unknown currency presented as written, an unknown field dropped at every level.
func TestAForeignPayloadIsPresented(t *testing.T) {
	f := serve(t)
	got := f.decode(map[string]any{"qrCode": f.bankCode()})
	if got.status != http.StatusOK {
		t.Fatalf("status %d: %s", got.status, got.raw)
	}
	if diff := cmp.Diff(compact(t, foreignPresented), got.raw); diff != "" {
		t.Errorf("answer (-want +got):\n%s", diff)
	}
}

// X9-DEC-513 (ADR-0028, a known difference between the builds): a verified payload that is not a
// JSON object is a 400 business-rule, not a 500 (TOLERATED-DIFFERENCES.md, entry 18).
//
// A payload that verified and is not a JSON object has nothing to present.
func TestAPayloadThatIsNotAnObjectIsRefused(t *testing.T) {
	f := serve(t)
	f.bank.answerWith(f.payloadAnswer(`["not", "a", "payload"]`))
	got := f.decode(map[string]any{"qrCode": f.bankCode()})
	assertProblem(t, got, http.StatusBadRequest, httpapi.TypeBusinessRule, msgUnreadable)
}

// compact strips the whitespace of s outside its strings, keeping its member order.
func compact(t *testing.T, s string) string {
	t.Helper()
	var b strings.Builder
	inString, escaped := false, false
	for _, r := range s {
		switch {
		case inString:
			b.WriteRune(r)
			if escaped {
				escaped = false
			} else if r == '\\' {
				escaped = true
			} else if r == '"' {
				inString = false
			}
		case r == '"':
			inString = true
			b.WriteRune(r)
		case r == ' ' || r == '\n' || r == '\t' || r == '\r':
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}
