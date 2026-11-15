// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package outbound

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
)

// sequence answers the i-th delivery (from 0) with answers[i], and every one past the end with the
// last.
func sequence(answers ...http.HandlerFunc) http.HandlerFunc {
	var (
		mu sync.Mutex
		n  int
	)
	return func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		i := min(n, len(answers)-1)
		n++
		mu.Unlock()
		answers[i](w, r)
	}
}

// underAnotherID answers a signed yes under a correlationId that is not the one sent.
func (f *fixture) underAnotherID() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) { f.answerWith(w, uuid.New(), 200, 200) }
}

// alteredUnderAnotherID answers a signed yes under another correlationId, its body altered after
// signing: the signature fails first.
func (f *fixture) alteredUnderAnotherID() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		token, err := f.signer.SignAnswer(f.store.Current().Identity, uuid.New(), 200)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		parts := strings.Split(token, ".")
		parts[1] = "eyJzdGF0dXNDb2RlIjoyMDF9" // {"statusCode":201}
		w.Header().Set("Content-Type", ContentTypeJOSE)
		_, _ = io.WriteString(w, strings.Join(parts, "."))
	}
}

func hangUp(w http.ResponseWriter, _ *http.Request) {
	conn, _, err := w.(http.Hijacker).Hijack()
	if err == nil {
		_ = conn.Close()
	}
}

// sentUnder is the correlationId each delivery was signed under, and its signed payload.
func (f *fixture) sentUnder(t *testing.T) ([]uuid.UUID, []string) {
	t.Helper()
	var (
		ids      []uuid.UUID
		payloads []string
	)
	for _, d := range f.payee.received() {
		v, err := f.verifier.Verify(t.Context(), d.body, jws.Expect{})
		if err != nil {
			t.Fatalf("a delivery does not verify: %v", err)
		}
		ids, payloads = append(ids, v.Header.CorrelationID), append(payloads, string(v.Payload))
	}
	return ids, payloads
}

// Owner, 2026-11-15 (ADR-0027): before the money moves (a pre-payment, no transactionId), a signed
// answer under another correlationId than the one sent, the caller's or the generated one, is
// refused after one delivery: 502 notification-undeliverable, "failed to pay - payee is not
// responding (bad correlationId)". One that fails its signature first is reported as before.
func TestABadCorrelationIDBeforeTheMoneyMovesIs502(t *testing.T) {
	for _, generated := range []bool{false, true} {
		name := "the caller's correlationId"
		if generated {
			name = "a generated correlationId"
		}
		t.Run(name, func(t *testing.T) {
			f := serve(t, 5*time.Second)
			f.payee.answer = f.underAnotherID()
			req := f.request(false)
			if generated {
				delete(req, "correlationId")
			}

			got := f.post(PrePaymentPath, req)

			assertUndeliverable(t, got)
			if got.body["detail"] != httpapi.DetailBadCorrelationID {
				t.Errorf("detail %q, want %q", got.body["detail"], httpapi.DetailBadCorrelationID)
			}
			if n := len(f.payee.received()); n != 1 {
				t.Errorf("%d deliveries, want 1: nothing is sent again before the money moves", n)
			}
			if recs := f.logs.records(t, MsgUntiedAnswers); len(recs) != 0 {
				t.Errorf("a pre-commit refusal is not a post-commit record: %v", recs)
			}
		})
	}
	t.Run("a bad signature comes first", func(t *testing.T) {
		f := serve(t, 5*time.Second)
		f.payee.answer = f.alteredUnderAnotherID()

		got := f.post(PrePaymentPath, f.request(false))

		if got.status != http.StatusOK || got.body["signatureProblem"] != "BAD_SIGNATURE" {
			t.Fatalf("status %d, want 200 BAD_SIGNATURE: %s", got.status, got.raw)
		}
	})
}

// Owner, 2026-11-15 (ADR-0027): after the money moved (a post-payment with a transactionId), an
// answer under another correlationId is followed by one more delivery of the same notification,
// under a new correlationId. A good answer to it is reported as any other, and nothing is recorded.
func TestABadCorrelationIDAfterTheMoneyMovedIsSentOnceMore(t *testing.T) {
	f := serve(t, 5*time.Second)
	f.payee.answer = sequence(f.underAnotherID(), func(w http.ResponseWriter, r *http.Request) {
		f.signedAnswer(w, r, http.StatusOK)
	})

	got := f.post(PostPaymentPath, f.request(true))

	if got.status != http.StatusOK || got.body["signature"] != "VERIFIED" || got.body["accepted"] != true {
		t.Fatalf("status %d, want 200 VERIFIED accepted: %s", got.status, got.raw)
	}
	ids, payloads := f.sentUnder(t)
	if len(ids) != 2 {
		t.Fatalf("%d deliveries, want 2", len(ids))
	}
	if ids[0] != uuid.MustParse(cid) {
		t.Errorf("the first went out under %s, want the caller's %s", ids[0], cid)
	}
	if ids[1] == ids[0] || ids[1] == uuid.Nil {
		t.Errorf("the retry went out under %s: a new exchange gets a new correlationId", ids[1])
	}
	if payloads[0] != payloads[1] {
		t.Errorf("the retry is not the same notification:\n%s\n%s", payloads[0], payloads[1])
	}
	if recs := f.logs.records(t, MsgUntiedAnswers); len(recs) != 0 {
		t.Errorf("a good retry records nothing: %v", recs)
	}
}

// Owner, 2026-11-15 (ADR-0027): after the money moved, a second answer under another correlationId
// ends the exchange. Exactly two deliveries; the retry's answer is reported as it is (200, INVALID,
// CORRELATION_ID_MISMATCH); and the record is written at WARN for the payer bank: the QR Code, the
// transactionId, both correlationIds sent, what came back under each, and when; never the
// endpoint. When the retry gets no answer at all, the record says so and the caller gets the usual
// 502.
func TestABadCorrelationIDTwiceAfterTheMoneyMovedIsRecorded(t *testing.T) {
	t.Run("bad twice", func(t *testing.T) {
		f := serve(t, 5*time.Second)
		f.payee.answer = f.underAnotherID()

		got := f.post(PostPaymentPath, f.request(true))

		if got.status != http.StatusOK {
			t.Fatalf("status %d: %s", got.status, got.raw)
		}
		want := map[string]any{"statusCode": 200.0, "accepted": true, "signature": "INVALID",
			"signatureProblem": "CORRELATION_ID_MISMATCH"}
		delete(got.body, "body")
		delete(got.body, "signatureDetail")
		if diff := cmp.Diff(want, got.body); diff != "" {
			t.Errorf("report (-want +got):\n%s", diff)
		}
		ids, _ := f.sentUnder(t)
		if len(ids) != 2 {
			t.Fatalf("%d deliveries, want exactly 2", len(ids))
		}
		recs := f.logs.records(t, MsgUntiedAnswers)
		if len(recs) != 1 {
			t.Fatalf("%d records, want 1", len(recs))
		}
		rec := recs[0]
		if rec["level"] != "WARN" || rec["qrcodeId"] != qrID || rec["transactionId"] != txHash ||
			rec["at"] != "2026-11-15T12:00:00Z" {
			t.Errorf("record %v", rec)
		}
		sent, _ := rec["correlationIdsSent"].([]any)
		if len(sent) != 2 || sent[0] != ids[0].String() || sent[1] != ids[1].String() {
			t.Errorf("correlationIdsSent %v, want %v", sent, ids)
		}
		answered, _ := rec["correlationIdsAnswered"].([]any)
		if len(answered) != 2 || answered[0] == ids[0].String() || answered[1] == ids[1].String() ||
			answered[0] == "missing" || answered[1] == "missing" {
			t.Errorf("correlationIdsAnswered %v: each another id", answered)
		}
		raw, _ := json.Marshal(rec)
		if strings.Contains(string(raw), f.payee.endpoint) || strings.Contains(string(raw), "127.0.0.1") {
			t.Errorf("the record carries an address: %s", raw)
		}
	})
	t.Run("bad, then no answer", func(t *testing.T) {
		f := serve(t, 5*time.Second)
		f.payee.answer = sequence(f.underAnotherID(), hangUp)

		assertUndeliverable(t, f.post(PostPaymentPath, f.request(true)))
		if n := len(f.payee.received()); n != 2 {
			t.Errorf("%d deliveries, want 2", n)
		}
		recs := f.logs.records(t, MsgUntiedAnswers)
		if len(recs) != 1 {
			t.Fatalf("%d records, want 1", len(recs))
		}
		answered, _ := recs[0]["correlationIdsAnswered"].([]any)
		if len(answered) != 2 || answered[1] != "no answer" {
			t.Errorf("correlationIdsAnswered %v", answered)
		}
	})
}
