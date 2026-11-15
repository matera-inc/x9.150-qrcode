// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"log/slog"
	"maps"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/database"
	"github.com/matera-inc/x9.150-qrcode/internal/dbtest"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/emv"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

const host = "x9-150.example.com"

const recipient = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

// body is a create body, as the black-box samples write it, with overrides applied to its top level.
func body(t *testing.T, override map[string]any) string {
	t.Helper()
	b := map[string]any{
		"validUntil": "2030-12-31T23:59:59Z",
		"creditor": map[string]any{
			"name": "Blackbox Co", "MCC": "4900",
			"address": map[string]any{"line1": "1 A St", "line2": nil, "city": "Los Angeles", "country": "US"},
		},
		"bill":                map[string]any{"description": "blackbox", "amountDue": map[string]any{"amount": 1000, "currency": "USDC"}},
		"paymentNotification": map[string]any{"kind": "DEFAULT"},
		"paymentMethods": []any{map[string]any{
			"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
			"networks": map[string]any{"solana": map[string]any{"recipient": recipient}},
		}},
	}
	maps.Copy(b, override)
	raw, err := json.Marshal(b)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

type testClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *testClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *testClock) set(t time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = t
}

type env struct {
	db    *dbtest.DB
	pool  *pgxpool.Pool
	store *store.Store
	clock *testClock
	api   *httpapi.API
}

func newEnv(t *testing.T) *env {
	t.Helper()
	db := dbtest.Start(t)
	pc, err := database.PoolConfig(db.Config(dbtest.AppRole), 4)
	if err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.NewWithConfig(t.Context(), pc)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	e := &env{db: db, pool: pool, clock: &testClock{}}
	e.clock.set(time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC))
	e.store = store.New(pool, pool, e.clock)
	e.api = httpapi.New(slog.New(slog.DiscardHandler), e.clock.Now)
	New(e.store, billing.BundledCurrencies(), host, e.clock.Now).Register(e.api)
	return e
}

type reply struct {
	status int
	etag   string
	body   map[string]any
	text   string
}

func (e *env) do(t *testing.T, method, target, payload string) reply {
	t.Helper()
	r := httptest.NewRequestWithContext(context.Background(), method, target, strings.NewReader(payload))
	if payload != "" {
		r.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	e.api.ServeHTTP(w, r)
	a := reply{status: w.Code, etag: w.Header().Get("ETag"), text: w.Body.String()}
	_ = json.Unmarshal(w.Body.Bytes(), &a.body)
	return a
}

func (e *env) create(t *testing.T, override map[string]any) string {
	t.Helper()
	a := e.do(t, http.MethodPost, "/api/v1/payment-request", body(t, override))
	if a.status != http.StatusCreated {
		t.Fatalf("create: %d %s", a.status, a.text)
	}
	return a.body["id"].(string)
}

func (e *env) count(t *testing.T, sql string) int {
	t.Helper()
	n, err := strconv.Atoi(e.db.Psql(t.Context(), t, "SET search_path TO "+dbtest.Schema+"; "+sql))
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func (e *env) setStatus(t *testing.T, id string, s domain.Status) {
	t.Helper()
	uid, _ := httpapi.ParseID(id)
	_, err := e.store.Transition(t.Context(), uid, nil, func(row domain.Row, last *domain.NotificationRecord, now time.Time) (domain.Decision, error) {
		return domain.UpdateStatus(row, domain.StatusUpdate{Status: s}, last, now, domain.Settings{})
	})
	if err != nil {
		t.Fatal(err)
	}
}

func (e *env) get(t *testing.T, id string) reply {
	t.Helper()
	return e.do(t, http.MethodGet, "/api/v1/payment-request/"+id, "")
}

func TestAgainstPostgres(t *testing.T) {
	e := newEnv(t)
	for _, tc := range []struct {
		name string
		test func(t *testing.T, e *env)
	}{
		{"CreateAnswersJavasFields", testCreateAnswersJavasFields},
		{"RefusedCreateWritesNothing", testRefusedCreateWritesNothing},
		{"GetComposesTheRequest", testGetComposesTheRequest},
		{"GetRevisionAndUnknownIDs", testGetRevisionAndUnknownIDs},
		{"AnExpiredQRCodeIsStillRead", testAnExpiredQRCodeIsStillRead},
		{"RebindReleasesAClosedHolder", testRebindReleasesAClosedHolder},
		{"AnExpiredActiveHolderGivesUpItsLocation", testAnExpiredActiveHolderGivesUpItsLocation},
		{"RebindRefusals", testRebindRefusals},
	} {
		e.clock.set(time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC))
		t.Run(tc.name, func(t *testing.T) { tc.test(t, e) })
	}
}

var upperHex32 = regexp.MustCompile(`^[0-9A-F]{32}$`)

// S9.6-AC1, S9.6-AC3, X9-MAP-001: 201 with Java's four members and an ETag; the location is the
// QR Code's own id; qrCode is internal/emv's string for it and qrCodeB64 that string in Base64;
// no event.
func testCreateAnswersJavasFields(t *testing.T, e *env) {
	events := e.count(t, "SELECT count(*) FROM payment_event")
	a := e.do(t, http.MethodPost, "/api/v1/payment-request", body(t, nil))
	if a.status != http.StatusCreated || a.etag != `"0-ACTIVE"` {
		t.Fatalf("create: %d ETag %s %s", a.status, a.etag, a.text)
	}
	id, _ := a.body["id"].(string)
	if !upperHex32.MatchString(id) {
		t.Fatalf("id %q is not 32 upper-case hex characters", id)
	}
	uid, _ := httpapi.ParseID(id)
	if uid.Version() != 7 {
		t.Errorf("the QR Code id is a UUIDv%d, want v7 (ADR-0026)", uid.Version())
	}
	want, err := emv.Encode(emv.LocationURL(host, id), emv.Merchant{CategoryCode: "4900", Country: "US", Name: "Blackbox Co", City: "Los Angeles"})
	if err != nil {
		t.Fatal(err)
	}
	wantBody := map[string]any{
		"id": id, "qrCode": want, "qrCodeB64": base64.StdEncoding.EncodeToString([]byte(want)),
		"location": map[string]any{"id": id, "endpoint": "https://" + host + "/pub/api/v1/loc/" + id},
	}
	if diff := cmp.Diff(wantBody, a.body); diff != "" {
		t.Errorf("create body (-want +got):\n%s", diff)
	}
	if n := e.count(t, "SELECT count(*) FROM qr_code WHERE id = '"+uid.String()+"' AND location_id = id AND emv_content = '"+want+"'"); n != 1 {
		t.Errorf("the row does not hold its location and EMV string")
	}
	if n := e.count(t, "SELECT count(*) FROM payment_event"); n != events {
		t.Errorf("a create wrote %d events, want none (X9-MAP-001)", n-events)
	}
}

// S9.6-AC1, X9-LIFE-506: a refused create writes nothing, whichever rule refused it.
func testRefusedCreateWritesNothing(t *testing.T, e *env) {
	before := e.count(t, "SELECT count(*) FROM qr_code")
	for _, tc := range []struct {
		name     string
		override map[string]any
		status   int
		naming   string
	}{
		{"an unsupported currency", map[string]any{
			"bill": map[string]any{"description": "x", "amountDue": map[string]any{"amount": 1000, "currency": "EUR"}},
			"paymentMethods": []any{map[string]any{"currency": "EUR", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
				"networks": map[string]any{"fednow": map[string]any{"routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "plaintext"}}}},
		}, 400, "EUR"},
		{"an uninterpreted network", map[string]any{"paymentMethods": []any{map[string]any{
			"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
			"networks": map[string]any{"tron": map[string]any{"walletAddress": "T"}},
		}}}, 400, "paymentMethods.networks: Unsupported network(s): tron."},
		{"a past validUntil", map[string]any{"validUntil": "2026-11-15T11:00:00Z", "paymentMethods": []any{map[string]any{
			"currency": "USDC", "validUntil": "2026-11-15T11:00:00Z", "amount": 1000,
			"networks": map[string]any{"solana": map[string]any{"recipient": recipient}},
		}}}, 400, "must be after or equal to actual date"},
		{"a contract violation", map[string]any{"creditor": nil}, 400, "creditor: must not be null"},
		{"a dashed locationId (S9.6-AC3)", map[string]any{"locationId": "01234567-89ab-cdef-0123-456789abcdef"}, 400,
			"locationId: size must be between 32 and 32"},
		{"a locationId that is not hexadecimal", map[string]any{"locationId": strings.Repeat("Z", 32)}, 400, "LocationId is invalid."},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := e.do(t, http.MethodPost, "/api/v1/payment-request", body(t, tc.override))
			if a.status != tc.status || !strings.Contains(a.text, tc.naming) || a.body["id"] != nil {
				t.Fatalf("%d %s, want %d naming %q", a.status, a.text, tc.status, tc.naming)
			}
		})
	}
	if after := e.count(t, "SELECT count(*) FROM qr_code"); after != before {
		t.Fatalf("refused creates wrote %d rows", after-before)
	}
}

// S9.6-AC5: the answer is composed from the row and its request_json, as Java reads it back: its
// defaults filled in, no null member, no lockVersion, the effective status and the ETag.
func testGetComposesTheRequest(t *testing.T, e *env) {
	id := e.create(t, nil)
	e.clock.set(e.clock.Now().Add(time.Minute))
	a := e.get(t, id)
	if a.status != http.StatusOK || a.etag != `"0-ACTIVE"` {
		t.Fatalf("GET: %d ETag %s %s", a.status, a.etag, a.text)
	}
	created := e.do(t, http.MethodGet, "/api/v1/payment-request/"+strings.ToLower(id), "")
	if created.status != http.StatusOK || created.body["id"] != id {
		t.Fatalf("a lower-case id is read, and answered in upper case: %d %v", created.status, created.body["id"])
	}
	qrCode, _ := a.body["qrCode"].(string)
	want := map[string]any{
		"id": id, "qrCode": qrCode, "qrCodeB64": base64.StdEncoding.EncodeToString([]byte(qrCode)),
		"location":   map[string]any{"id": id, "endpoint": "https://" + host + "/pub/api/v1/loc/" + id},
		"validUntil": "2030-12-31T23:59:59Z",
		"creditor": map[string]any{"name": "Blackbox Co", "MCC": "4900",
			"address": map[string]any{"line1": "1 A St", "city": "Los Angeles", "country": "US"}},
		"bill": map[string]any{"description": "blackbox", "paymentTiming": "immediate", "tip": map[string]any{"allowed": false},
			"amountDue": map[string]any{"amount": float64(1000), "currency": "USDC"}},
		"paymentNotification": map[string]any{"kind": "DEFAULT"},
		"paymentMethods": []any{map[string]any{"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": float64(1000),
			"networks": map[string]any{"solana": map[string]any{"recipient": recipient}}}},
		"revision": float64(0), "createdAt": "2026-11-15T12:00:00Z", "revisedAt": "2026-11-15T12:00:00Z",
		"sentAt": "2026-11-15T12:01:00Z", "status": "ACTIVE",
	}
	if diff := cmp.Diff(want, a.body); diff != "" {
		t.Errorf("GET body (-want +got):\n%s", diff)
	}
	if strings.Contains(a.text, "lockVersion") || strings.Contains(a.text, "null") {
		t.Errorf("GET carries lockVersion or a null: %s", a.text)
	}
}

// S9.6-AC5: ?revision= matches the current revision only, and is otherwise Java's 404; a bad
// revision is Java's 400; an unknown id is 404; a dashed or non-hexadecimal id is 400 (S9.6-AC3).
func testGetRevisionAndUnknownIDs(t *testing.T, e *env) {
	id := e.create(t, nil)
	for _, tc := range []struct {
		target string
		status int
		naming string
	}{
		{id + "?revision=0", 200, ""},
		{id + "?revision=1", 404, "Could not find QR code with id: " + id},
		{id + "?revision=-1", 400, "getPaymentRequest.revision: must be greater than or equal to 0"},
		{id + "?revision=x", 400, "revision has invalid value."},
		{strings.Repeat("0", 32), 404, "Could not find QR code with id: " + strings.Repeat("0", 32)},
		{uuid.New().String(), 400, "getPaymentRequest.id: size must be between 32 and 32"},
		{strings.Repeat("G", 32), 400, "QRCodeId is invalid."},
	} {
		t.Run(tc.target, func(t *testing.T) {
			a := e.get(t, tc.target)
			if a.status != tc.status || !strings.Contains(a.text, tc.naming) {
				t.Fatalf("%d %s, want %d naming %q", a.status, a.text, tc.status, tc.naming)
			}
		})
	}
}

// S9.6-AC5, ADR-0029: time never deletes a QR Code from a read; an expired one is read as it is.
func testAnExpiredQRCodeIsStillRead(t *testing.T, e *env) {
	soon := "2026-11-15T12:05:00Z"
	id := e.create(t, map[string]any{"validUntil": soon, "paymentMethods": []any{map[string]any{
		"currency": "USDC", "validUntil": soon, "amount": 1000,
		"networks": map[string]any{"solana": map[string]any{"recipient": recipient}},
	}}})
	e.clock.set(e.clock.Now().Add(24 * time.Hour))
	if a := e.get(t, id); a.status != http.StatusOK || a.body["status"] != "ACTIVE" || a.body["validUntil"] != soon {
		t.Fatalf("an expired QR Code: %d %s", a.status, a.text)
	}
}

func locationOf(t *testing.T, e *env, id string) (string, string) {
	t.Helper()
	a := e.get(t, id)
	loc, _ := a.body["location"].(map[string]any)
	locID, _ := loc["id"].(string)
	qr, _ := a.body["qrCode"].(string)
	return locID, qr
}

// S9.6-AC2, X9-MAP-050: a CANCELLED holder of the same creditor gives its location up, in the
// create's transaction, to a fresh location with its own EMV string; its revision does not move
// and no event is written for it.
func testRebindReleasesAClosedHolder(t *testing.T, e *env) {
	holder := e.create(t, nil)
	e.setStatus(t, holder, domain.StatusCancelled)
	location, oldEMV := locationOf(t, e, holder)
	events := e.count(t, "SELECT count(*) FROM payment_event")

	taker := e.create(t, map[string]any{"locationId": strings.ToLower(location)})

	if got, _ := locationOf(t, e, taker); got != location {
		t.Fatalf("the taker holds %s, want %s", got, location)
	}
	a := e.get(t, holder)
	fresh, emvContent := locationOf(t, e, holder)
	if fresh == location || !upperHex32.MatchString(fresh) || emvContent == oldEMV ||
		!strings.Contains(emvContent, "/pub/api/v1/loc/"+fresh) {
		t.Fatalf("the holder after the release: location %s, EMV %s", fresh, emvContent)
	}
	if fid, _ := httpapi.ParseID(fresh); fid.Version() != 4 {
		t.Errorf("the fresh location is a UUIDv%d, want v4 (ADR-0026)", fid.Version())
	}
	if a.body["revision"] != float64(0) || a.body["status"] != "CANCELLED" {
		t.Errorf("the holder's revision or status moved: %v %v", a.body["revision"], a.body["status"])
	}
	if n := e.count(t, "SELECT count(*) FROM payment_event"); n != events {
		t.Errorf("the rebind wrote %d events, want none", n-events)
	}
}

// S9.6-AC2, BUG-004: an ACTIVE holder past its validUntil is not live (ADR-0024), so it gives its
// location up; Java, which reads the status only, keeps it.
func testAnExpiredActiveHolderGivesUpItsLocation(t *testing.T, e *env) {
	soon := "2026-11-15T12:02:00Z"
	holder := e.create(t, map[string]any{"validUntil": soon, "paymentMethods": []any{map[string]any{
		"currency": "USDC", "validUntil": soon, "amount": 1000,
		"networks": map[string]any{"solana": map[string]any{"recipient": recipient}},
	}}})
	location, _ := locationOf(t, e, holder)
	e.clock.set(e.clock.Now().Add(3 * time.Minute))

	taker := e.create(t, map[string]any{"locationId": location})

	if got, _ := locationOf(t, e, taker); got != location {
		t.Fatalf("the taker holds %s, want %s", got, location)
	}
	if fresh, _ := locationOf(t, e, holder); fresh == location {
		t.Fatal("the expired holder kept its location")
	}
}

// S9.6-AC2: a live holder, or another creditor, keeps the location with Java's 400; a create
// refused for another reason releases nothing.
func testRebindRefusals(t *testing.T, e *env) {
	live := e.create(t, nil)
	liveLocation, _ := locationOf(t, e, live)
	closed := e.create(t, nil)
	e.setStatus(t, closed, domain.StatusCancelled)
	closedLocation, closedEMV := locationOf(t, e, closed)
	rows := e.count(t, "SELECT count(*) FROM qr_code")

	other := map[string]any{"name": "Another Co", "MCC": "5812",
		"address": map[string]any{"line1": "2 B St", "city": "Denver", "country": "US"}}
	for _, tc := range []struct {
		name     string
		override map[string]any
		naming   string
	}{
		{"a live holder", map[string]any{"locationId": liveLocation},
			"The QRCode needs to be inactive to reuse location. Current status: ACTIVE"},
		{"another creditor", map[string]any{"locationId": closedLocation, "creditor": other},
			"Creditor information must not change when locationId is informed"},
		{"refused for its currency", map[string]any{"locationId": closedLocation,
			"bill": map[string]any{"description": "x", "amountDue": map[string]any{"amount": 1000, "currency": "EUR"}}},
			"EUR"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := e.do(t, http.MethodPost, "/api/v1/payment-request", body(t, tc.override))
			if a.status != http.StatusBadRequest || !strings.Contains(a.text, tc.naming) {
				t.Fatalf("%d %s, want 400 naming %q", a.status, a.text, tc.naming)
			}
		})
	}
	if got, _ := locationOf(t, e, live); got != liveLocation {
		t.Error("the live holder lost its location")
	}
	if got, emvContent := locationOf(t, e, closed); got != closedLocation || emvContent != closedEMV {
		t.Error("a refused create released the closed holder")
	}
	if n := e.count(t, "SELECT count(*) FROM qr_code"); n != rows {
		t.Errorf("refused creates wrote %d rows", n-rows)
	}
}
