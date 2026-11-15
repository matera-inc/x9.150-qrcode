// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package httpapi

import (
	"bufio"
	"context"
	"encoding/json/v2"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

var fixedNow = time.Date(2026, 11, 15, 13, 58, 0, 403e6, time.UTC)

func testAPI(t *testing.T) (*API, *strings.Builder) {
	t.Helper()
	var logs strings.Builder
	a := New(slog.New(slog.NewJSONHandler(&logs, nil)), func() time.Time { return fixedNow })
	a.Handle("POST /api/v1/payment-request", func(w http.ResponseWriter, r *http.Request) error {
		var body struct {
			Amount int64 `json:"amount"`
		}
		if err := DecodeJSON(r, &body); err != nil {
			return err
		}
		return WriteJSON(w, http.StatusCreated, body)
	})
	a.Handle("GET /api/v1/payment-request/{id}", func(_ http.ResponseWriter, r *http.Request) error {
		id, err := PathID(r, "getPaymentRequest", "id")
		if err != nil {
			return err
		}
		if _, ok := ParseID(id); !ok {
			return InvalidQRCodeID()
		}
		switch id {
		case strings.Repeat("e", 32):
			return errors.New("pq: relation \"qr_code\" does not exist") // must never reach the caller
		case strings.Repeat("f", 32):
			panic("boom at /internal/secret.go:12")
		}
		return NotFound("Could not find QR code with id: " + strings.ToUpper(id))
	})
	a.Handle("PUT /api/v1/payment-request/{id}/status-update", func(http.ResponseWriter, *http.Request) error {
		return nil
	})
	return a, &logs
}

func serve(a http.Handler, method, target, contentType, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequestWithContext(context.Background(), method, target, strings.NewReader(body))
	if contentType != "" {
		r.Header.Set("Content-Type", contentType)
	}
	w := httptest.NewRecorder()
	a.ServeHTTP(w, r)
	return w
}

// S9.2-AC4: a refusal is application/problem+json with the Java build's members, word for word as
// the Java build wrote them.
func TestProblemsAreJavas(t *testing.T) {
	a, logs := testAPI(t)
	for _, tc := range []struct {
		name, method, target, body string
		status                     int
		want                       string
	}{
		{"not found", http.MethodGet, "/api/v1/payment-request/0123456789abcdef0123456789abcdef", "", 404,
			`{"type":"https://x9.matera.com/api/resource-not-found","title":"Resource not found","status":404,` +
				`"detail":"A resource was not found.","instance":"/api/v1/payment-request/0123456789abcdef0123456789abcdef",` +
				`"violations":["Could not find QR code with id: 0123456789ABCDEF0123456789ABCDEF"]}`},
		{"a short path id", http.MethodGet, "/api/v1/payment-request/abc", "", 400,
			`{"type":"https://x9.matera.com/api/validation-error","title":"Validation Failed","status":400,` +
				`"detail":"One or more fields failed validation.","instance":"/api/v1/payment-request/abc",` +
				`"violations":["getPaymentRequest.id: size must be between 32 and 32"]}`},
		{"a dashed path id (S9.6-AC3)", http.MethodGet, "/api/v1/payment-request/01234567-89ab-cdef-0123-456789abcdef", "", 400,
			`{"type":"https://x9.matera.com/api/validation-error","title":"Validation Failed","status":400,` +
				`"detail":"One or more fields failed validation.","instance":"/api/v1/payment-request/01234567-89ab-cdef-0123-456789abcdef",` +
				`"violations":["getPaymentRequest.id: size must be between 32 and 32"]}`},
		{"a non-hexadecimal path id", http.MethodGet, "/api/v1/payment-request/0123456789abcdef0123456789abcdeZ", "", 400,
			`{"type":"https://x9.matera.com/api/validation-error","title":"Validation Failed","status":400,` +
				`"detail":"One or more fields failed validation.","instance":"/api/v1/payment-request/0123456789abcdef0123456789abcdeZ",` +
				`"violations":["QRCodeId is invalid."]}`},
		{"malformed JSON (no violations)", http.MethodPost, "/api/v1/payment-request", "nope", 400,
			`{"type":"https://x9.matera.com/api/malformed-json","title":"Malformed JSON","status":400,` +
				`"detail":"The request body is not readable or is incorrectly formatted.","instance":"/api/v1/payment-request"}`},
		{"a 500 carries no detail", http.MethodGet, "/api/v1/payment-request/" + strings.Repeat("e", 32), "", 500,
			`{"type":"https://x9.matera.com/api/internal-error","title":"Internal Server Error","status":500,` +
				`"detail":"An unexpected error occurred while processing the request.","instance":"/api/v1/payment-request/eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}`},
		{"a panic is a 500 with no detail", http.MethodGet, "/api/v1/payment-request/" + strings.Repeat("f", 32), "", 500,
			`{"type":"https://x9.matera.com/api/internal-error","title":"Internal Server Error","status":500,` +
				`"detail":"An unexpected error occurred while processing the request.","instance":"/api/v1/payment-request/ffffffffffffffffffffffffffffffff"}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w := serve(a, tc.method, tc.target, ContentTypeJSON, tc.body)
			if w.Code != tc.status || w.Header().Get("Content-Type") != ContentTypeProblem {
				t.Errorf("status %d %q, want %d problem+json", w.Code, w.Header().Get("Content-Type"), tc.status)
			}
			if diff := cmp.Diff(tc.want, w.Body.String()); diff != "" {
				t.Errorf("body (-want +got):\n%s", diff)
			}
		})
	}
	if !strings.Contains(logs.String(), "does not exist") || !strings.Contains(logs.String(), "boom") {
		t.Errorf("the cause of a 500 is logged: %s", logs.String())
	}
}

func TestEveryProblemTypeIsJavas(t *testing.T) {
	rev := int64(3)
	for _, tc := range []struct {
		p    *Problem
		want string
	}{
		{Validation("a: b"), `{"type":"https://x9.matera.com/api/validation-error","title":"Validation Failed","status":400,"detail":"One or more fields failed validation.","violations":["a: b"]}`},
		{MissingHeader("Correlation-Id", "UUID"), `{"type":"https://x9.matera.com/api/validation-error","title":"Validation Failed","status":400,"detail":"One or more headers failed validation.","violations":["Required request header 'Correlation-Id' for method parameter type UUID is not present"]}`},
		{BusinessRule("qrCode", "The QR Code is malformed: x."), `{"type":"https://x9.matera.com/api/business-rule-violation","title":"Business Rule Violation","status":400,"detail":"A business rule was violated.","violations":["qrCode: The QR Code is malformed: x."]}`},
		{BusinessRule("", "Bill invoice must be informed when DEFERRED payment timing."), `{"type":"https://x9.matera.com/api/business-rule-violation","title":"Business Rule Violation","status":400,"detail":"A business rule was violated.","violations":["Bill invoice must be informed when DEFERRED payment timing."]}`},
		{StatusConflict("PAID", "m"), `{"type":"https://x9.matera.com/api/status-conflict","title":"Status Conflict","status":409,"detail":"The QR Code is not in a status that allows this transition.","currentStatus":"PAID","violations":["m"]}`},
		{PreconditionFailed("ACTIVE", rev, "m"), `{"type":"https://x9.matera.com/api/precondition-failed","title":"Precondition Failed","status":412,"detail":"The QR Code changed after it was read, so the condition on this request no longer holds. Nothing was written.","currentStatus":"ACTIVE","currentRevision":3,"violations":["m"]}`},
		{InvalidSignature("BAD_SIGNATURE"), `{"type":"https://x9.matera.com/api/invalid-signature","title":"Invalid Signature","status":401,"detail":"The JWS signature could not be verified, so the request was not processed.","signatureProblem":"BAD_SIGNATURE"}`},
		{PayloadRefused(404, []string{"x"}), `{"type":"https://x9.matera.com/api/payload-refused","title":"Payload Refused","status":404,"detail":"The payee's service refused to release this payload. Its own reason is relayed in ` + "`violations`" + `.","violations":["x"]}`},
		{PayloadUnreachable(), `{"type":"https://x9.matera.com/api/payload-unreachable","title":"Payload Unreachable","status":502,"detail":"The payee's service could not be reached, so this QR Code could not be decoded. Retry."}`},
		{NotificationUndeliverable(), `{"type":"https://x9.matera.com/api/notification-undeliverable","title":"Notification Undeliverable","status":502,"detail":"The payee's answer was not received; the payee may have received the notification. Retrying is safe."}`},
		{NotificationBadCorrelationID(), `{"type":"https://x9.matera.com/api/notification-undeliverable","title":"Notification Undeliverable","status":502,"detail":"failed to pay - payee is not responding (bad correlationId)"}`},
		{Unavailable(), `{"type":"https://x9.matera.com/api/internal-error","title":"Service Unavailable","status":503,"detail":"The database did not answer in time, so nothing was written. Retry."}`},
	} {
		if diff := cmp.Diff(tc.want, string(problemBody(tc.p, "", false))); diff != "" {
			t.Errorf("%s (-want +got):\n%s", tc.p.Type, diff)
		}
	}
	extra := &Problem{Type: TypeValidation, Status: 400, Extra: map[string]any{"statusCode": 400}}
	if got := string(problemBody(extra, "/x", false)); got != `{"type":"https://x9.matera.com/api/validation-error","status":400,"instance":"/x","statusCode":400}` {
		t.Errorf("extra members: %s", got)
	}
}

// S9.2-AC1: what no route takes is answered as Spring answers it: its own error body, with Allow on
// a 405; OPTIONS on a served path is 200 with Allow; a path ServeMux would clean is a 404, never a
// redirect.
func TestUnroutedRequestsAreAnsweredAsSpringAnswersThem(t *testing.T) {
	a, _ := testAPI(t)
	for _, tc := range []struct {
		method, target string
		status         int
		allow, body    string
	}{
		{http.MethodGet, "/nope", 404, "", `{"timestamp":"2026-11-15T13:58:00.403+00:00","status":404,"error":"Not Found","path":"/nope"}`},
		{http.MethodDelete, "/api/v1/payment-request", 405, "POST", `{"timestamp":"2026-11-15T13:58:00.403+00:00","status":405,"error":"Method Not Allowed","path":"/api/v1/payment-request"}`},
		{http.MethodGet, "/api/v1/payment-request", 405, "POST", `{"timestamp":"2026-11-15T13:58:00.403+00:00","status":405,"error":"Method Not Allowed","path":"/api/v1/payment-request"}`},
		{http.MethodOptions, "/api/v1/payment-request", 200, "POST,OPTIONS", ""},
		{http.MethodGet, "/api/v1/payment-request/0123456789abcdef0123456789abcdef/", 404, "", `{"timestamp":"2026-11-15T13:58:00.403+00:00","status":404,"error":"Not Found","path":"/api/v1/payment-request/0123456789abcdef0123456789abcdef/"}`},
		{http.MethodGet, "/api/v1//payment-request/0123456789abcdef0123456789abcdef", 404, "", `{"timestamp":"2026-11-15T13:58:00.403+00:00","status":404,"error":"Not Found","path":"/api/v1//payment-request/0123456789abcdef0123456789abcdef"}`},
		{http.MethodGet, "/API/v1/payment-request/0123456789abcdef0123456789abcdef", 404, "", `{"timestamp":"2026-11-15T13:58:00.403+00:00","status":404,"error":"Not Found","path":"/API/v1/payment-request/0123456789abcdef0123456789abcdef"}`},
	} {
		t.Run(tc.method+" "+tc.target, func(t *testing.T) {
			w := serve(a, tc.method, tc.target, "", "")
			if w.Code != tc.status || w.Header().Get("Allow") != tc.allow {
				t.Errorf("got %d Allow %q, want %d Allow %q", w.Code, w.Header().Get("Allow"), tc.status, tc.allow)
			}
			if w.Body.String() != tc.body {
				t.Errorf("body %s, want %s", w.Body, tc.body)
			}
			if tc.body != "" && w.Header().Get("Content-Type") != ContentTypeJSON {
				t.Errorf("content type %q", w.Header().Get("Content-Type"))
			}
		})
	}
}

// S9.2-AC2: the body is read as Spring reads it.
func TestBodiesAreReadAsSpringReadsThem(t *testing.T) {
	a, _ := testAPI(t)
	for _, tc := range []struct {
		name, contentType, body string
		status                  int
		want                    string
	}{
		{"json", "application/json", `{"amount": 5}`, 201, `{"amount":5}`},
		{"json with a charset", "application/json;charset=UTF-8", `{"amount": 5}`, 201, `{"amount":5}`},
		{"a +json type", "application/merge-patch+json", `{"amount": 5}`, 201, `{"amount":5}`},
		{"a byte-order mark", "application/json", "\xef\xbb\xbf{\"amount\": 5}", 201, `{"amount":5}`},
		{"trailing content is ignored", "application/json", `{"amount": 5} {} junk`, 201, `{"amount":5}`},
		{"keys are case-sensitive", "application/json", `{"AMOUNT": 5}`, 201, `{"amount":0}`},
		{"a repeated key keeps the last value", "application/json", `{"amount": 4, "amount": 5}`, 201, `{"amount":5}`},
		{"empty", "application/json", ``, 400, TypeMalformedJSON},
		{"null", "application/json", `null`, 400, TypeMalformedJSON},
		{"an array", "application/json", `[]`, 400, TypeMalformedJSON},
		{"not JSON", "application/json", `nope`, 400, TypeMalformedJSON},
		{"cut short", "application/json", `{"amount": 5`, 400, TypeMalformedJSON},
		{"past 2^63-1 (X9-AMT-422)", "application/json", `{"amount": 9223372036854775808}`, 400, TypeMalformedJSON},
		{"invalid UTF-8", "application/json", "{\"amount\": 5, \"x\": \"\xff\"}", 400, TypeMalformedJSON},
		{"too large", "application/json", `{"x":"` + strings.Repeat("a", MaxBodyBytes) + `"}`, 400, TypeMalformedJSON},
		{"text/plain is 415", "text/plain", `{}`, 415, `"error":"Unsupported Media Type"`},
		{"no content type is 415", "", `{}`, 415, `"error":"Unsupported Media Type"`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w := serve(a, http.MethodPost, "/api/v1/payment-request", tc.contentType, tc.body)
			if w.Code != tc.status || !strings.Contains(w.Body.String(), tc.want) {
				t.Errorf("got %d %s, want %d containing %s", w.Code, w.Body, tc.status, tc.want)
			}
			if tc.status == 415 && w.Header().Get("Accept") != ContentTypeJSON {
				t.Errorf("a 415 names what is accepted, as Spring does: %v", w.Header())
			}
		})
	}
}

// S9.2-AC3: NON_NULL. A value is omitted only when null; 0, false, "" and [] are written. Amounts
// are JSON numbers, and HTML characters are not escaped.
func TestAnswersOmitOnlyNull(t *testing.T) {
	type answer struct {
		Amount    int64     `json:"amount"`
		Tip       *int64    `json:"tipAmount,omitzero"`
		Allowed   bool      `json:"allowed"`
		Payer     *string   `json:"payerInfo,omitzero"`
		Empty     *string   `json:"empty,omitzero"`
		Presets   []int     `json:"presets,omitzero"`
		Methods   []int     `json:"methods,omitzero"`
		Note      string    `json:"note"`
		CreatedAt time.Time `json:"createdAt,omitzero"`
	}
	empty, zero := "", int64(0)
	got, err := Marshal(answer{Amount: 999999999999999999, Tip: &zero, Empty: &empty, Presets: []int{}, Note: "<a&b>"})
	if err != nil {
		t.Fatal(err)
	}
	want := `{"amount":999999999999999999,"tipAmount":0,"allowed":false,"empty":"","presets":[],"note":"<a&b>"}`
	if string(got) != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
	w := httptest.NewRecorder()
	if err := WriteJSON(w, 200, answer{}); err != nil || w.Header().Get("Content-Type") != "application/json" {
		t.Errorf("WriteJSON: %v %v", err, w.Header())
	}
	var back map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &back); err != nil {
		t.Fatal(err)
	}
	SetETag(w, "0-ACTIVE")
	if w.Header().Get("ETag") != `"0-ACTIVE"` {
		t.Errorf("ETag %q", w.Header().Get("ETag"))
	}
}

// S9.2-AC6: ids are 32 hexadecimal characters in either case on input, and upper case on output.
func TestIDs(t *testing.T) {
	want := uuid.MustParse("01234567-89ab-cdef-0123-456789abcdef")
	for in, ok := range map[string]bool{
		"0123456789abcdef0123456789abcdef":     true,
		"0123456789ABCDEF0123456789ABCDEF":     true,
		"0123456789AbCdEf0123456789aBcDeF":     true,
		"01234567-89ab-cdef-0123-456789abcdef": false, // dashed: refused (S9.6-AC3)
		"0123456789abcdef0123456789abcdeZ":     false,
		"0123456789abcdef0123456789abcde":      false,
		"":                                     false,
	} {
		id, got := ParseID(in)
		if got != ok || ok && id != want {
			t.Errorf("ParseID(%q) = %v %v, want ok %v", in, id, got, ok)
		}
	}
	if got := FormatID(want); got != "0123456789ABCDEF0123456789ABCDEF" {
		t.Errorf("FormatID = %s", got)
	}
	v7 := uuid.Must(uuid.NewV7())
	if back, ok := ParseID(FormatID(v7)); !ok || back != v7 {
		t.Errorf("a v7 id does not round-trip: %s", FormatID(v7))
	}
}

// S9.2-AC7: If-Match is read as UpdateQRCodeStatusRequestMapper.parseIfMatch reads it.
func TestIfMatch(t *testing.T) {
	for _, tc := range []struct {
		headers     []string
		tag         string
		conditional bool
	}{
		{nil, "", false},
		{[]string{""}, "", false},
		{[]string{"  "}, "", false},
		{[]string{"*"}, "", false},
		{[]string{" * "}, "", false},
		{[]string{`"0-ACTIVE"`}, "0-ACTIVE", true},
		{[]string{`0-ACTIVE`}, "0-ACTIVE", true},
		{[]string{`W/"0-ACTIVE"`}, "0-ACTIVE", true},
		{[]string{`W/0-ACTIVE`}, "0-ACTIVE", true},
		{[]string{` "1-PAID" `}, "1-PAID", true},
		{[]string{`7`}, "7", true},
		{[]string{`abc`}, "abc", true},
		{[]string{`"a", "b"`}, `a", "b`, true}, // a list is one tag that matches nothing: 412
		{[]string{`"a"`, `"b"`}, `a","b`, true},
	} {
		h := http.Header{}
		for _, v := range tc.headers {
			h.Add("If-Match", v)
		}
		tag, conditional := ParseIfMatch(h)
		if tag != tc.tag || conditional != tc.conditional {
			t.Errorf("If-Match %q: got (%q, %v), want (%q, %v)", tc.headers, tag, conditional, tc.tag, tc.conditional)
		}
	}
}

func TestQueryInt(t *testing.T) {
	bounds := IntBounds{Min: new(int64(0))}
	for query, want := range map[string]string{
		"":                      "absent",
		"?revision=":            "absent",
		"?revision=0":           "0",
		"?revision=12":          "12",
		"?revision=-1":          "getPaymentRequest.revision: must be greater than or equal to 0",
		"?revision=x":           "revision has invalid value.",
		"?revision=1.5":         "revision has invalid value.",
		"?revision=99999999999": "revision has invalid value.",
	} {
		r := httptest.NewRequestWithContext(context.Background(), http.MethodGet, "/x"+query, nil)
		v, ok, err := QueryInt(r, "getPaymentRequest", "revision", bounds)
		got := "absent"
		switch {
		case err != nil:
			got = strings.Join(violations(err), "")
		case ok:
			got = strconv.FormatInt(v, 10)
		}
		if got != want {
			t.Errorf("%q: got %q, want %q", query, got, want)
		}
	}
	limit := IntBounds{Min: new(int64(1)), Max: new(int64(500))}
	r := httptest.NewRequestWithContext(context.Background(), http.MethodGet, "/x?limit=501", nil)
	_, _, err := QueryInt(r, "listPaymentEvents", "limit", limit)
	if diff := cmp.Diff([]string{"listPaymentEvents.limit: must be less than or equal to 500"}, violations(err)); diff != "" {
		t.Errorf("limit 501 (-want +got):\n%s", diff)
	}
}

func violations(err error) []string {
	if p, ok := errors.AsType[*Problem](err); ok {
		return p.Violations
	}
	return nil
}

// S9.2-AC8: request headers above 30 KB are refused as Tomcat refuses them, counted to the byte:
// 30720 bytes of request line and headers are read, 30721 are a 400 with Tomcat's page.
func TestHeadersAbove30KBAreRefusedAsJavaRefusesThem(t *testing.T) {
	a, _ := testAPI(t)
	a.Mux().HandleFunc("GET /actuator/health", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"status":"UP"}`)
	})
	srv := httptest.NewServer(a)
	defer srv.Close()
	send := func(total int) (int, string) {
		t.Helper()
		head := "GET /actuator/health HTTP/1.1\r\nHost: h\r\nX-Big: "
		head += strings.Repeat("a", total-len(head)-len("\r\n\r\n")) + "\r\n\r\n"
		if len(head) != total {
			t.Fatalf("built %d bytes, want %d", len(head), total)
		}
		conn, err := (&net.Dialer{}).DialContext(context.Background(), "tcp", srv.Listener.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = conn.Close() }()
		if _, err := io.WriteString(conn, head); err != nil {
			t.Fatal(err)
		}
		resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = resp.Body.Close() }()
		body, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(body)
	}
	if status, _ := send(MaxHeaderBytes); status != 200 {
		t.Errorf("30720 bytes: %d, want 200 (Java reads them)", status)
	}
	status, body := send(MaxHeaderBytes + 1)
	if status != 400 || !strings.Contains(body, "HTTP Status 400 – Bad Request") {
		t.Errorf("30721 bytes: %d %s, want Tomcat's 400", status, body)
	}
	if status, _ := send(200 << 10); status != 400 {
		t.Errorf("200 KB: %d, want 400", status)
	}
}

// A signed answer to a payment notification is the problem with statusCode first and no instance,
// the members in the order WriteProblem writes them (INTERPRETATION I-12, Story 10.4).
func TestAnswerBody(t *testing.T) {
	got := string(AnswerBody(StatusConflict("PAYMENT_INITIATED", "held")))
	want := `{"statusCode":409,"type":"https://x9.matera.com/api/status-conflict","title":"Status Conflict","status":409,` +
		`"detail":"The QR Code is not in a status that allows this transition.","currentStatus":"PAYMENT_INITIATED",` +
		`"violations":["held"]}`
	if got != want {
		t.Errorf("\n got %s\nwant %s", got, want)
	}
}
