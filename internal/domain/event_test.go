// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import (
	"errors"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/google/go-cmp/cmp"
)

// S9.3-AC6: BuildEvent, field for field as Java builds a PaymentEvent.
func TestBuildEvent(t *testing.T) {
	paidRow := closed(StatusPaid)
	last := &NotificationRecord{
		Notification: withTx(chain(holderA, ActionSent), "TX-1"),
		Receipt:      Receipt{ReceivedIn: StatusPaymentInitiated},
	}
	noTip := &NotificationRecord{Notification: bank(noPayerInfoA, "FedNow")}

	tests := []struct {
		name  string
		typ   EventType
		row   Row
		last  *NotificationRecord
		cause Cause
		want  Event
	}{
		{
			name: "from the last stored notification", typ: EventCleared, row: paidRow, last: last,
			want: Event{
				Type: EventCleared, OccurredAt: t0, QRCodeID: qrID, QRCodeRevision: 3, LocationID: locationID,
				Amount: new(int64(1100)), TipAmount: new(int64(100)), PayerInfo: new("holder@example.com"),
				Currency: new("USDC"), Network: new("solana"), TransactionID: new("TX-1"),
				InvoiceNumber: new("INV-1"), OrderNumber: new("ORD-1"), SchemaVersion: "1.1",
			},
		},
		{
			name: "an absent tip, payer and transactionId stay absent", typ: EventInitiated, row: active(), last: noTip,
			cause: Cause{Receipt: &Receipt{ReceivedIn: StatusActive}},
			want: Event{
				Type: EventInitiated, OccurredAt: t0, QRCodeID: qrID, QRCodeRevision: 3, LocationID: locationID,
				Amount: new(int64(1000)), Currency: new("USD"), Network: new("FedNow"),
				InvoiceNumber: new("INV-1"), OrderNumber: new("ORD-1"),
				ReceivedInStatus: new(StatusActive), SchemaVersion: "1.1",
			},
		},
		{
			name: "no notification: the first payment method and the payment details", typ: EventCleared, row: paidRow,
			want: Event{
				Type: EventCleared, OccurredAt: t0, QRCodeID: qrID, QRCodeRevision: 3, LocationID: locationID,
				Amount: new(int64(1000)), Currency: new("USDC"), Network: new("solana"), TransactionID: new("E2E-PAID"),
				InvoiceNumber: new("INV-1"), OrderNumber: new("ORD-1"), SchemaVersion: "1.1",
			},
		},
		{
			name: "deviations, receipt and reason", typ: EventSent, row: held(holderA), last: last,
			cause: Cause{Reason: "why", Receipt: &Receipt{ReceivedIn: StatusPaid, Deviations: []Deviation{DeviationReceivedInWrongStatus}}},
			want: Event{
				Type: EventSent, OccurredAt: t0, QRCodeID: qrID, QRCodeRevision: 3, LocationID: locationID,
				Amount: new(int64(1100)), TipAmount: new(int64(100)), PayerInfo: new("holder@example.com"),
				Currency: new("USDC"), Network: new("solana"), TransactionID: new("TX-1"),
				InvoiceNumber: new("INV-1"), OrderNumber: new("ORD-1"), Reason: new("why"),
				Deviations: []Deviation{DeviationReceivedInWrongStatus}, ReceivedInStatus: new(StatusPaid), SchemaVersion: "1.1",
			},
		},
		{
			name: "no payment method, no bill numbers", typ: EventCancelled,
			row:  func() Row { r := active(); r.RequestJSON = []byte(`{"bill":{},"paymentMethods":[]}`); return r }(),
			want: Event{Type: EventCancelled, OccurredAt: t0, QRCodeID: qrID, QRCodeRevision: 3, LocationID: locationID, SchemaVersion: "1.1"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := BuildEvent(tt.typ, tt.row, tt.last, t0, tt.cause)
			if err != nil {
				t.Fatal(err)
			}
			if diff := cmp.Diff(tt.want, got); diff != "" {
				t.Errorf("BuildEvent (-want +got):\n%s", diff)
			}
		})
	}
}

func TestBuildEventDeviationsAbsentNotEmpty(t *testing.T) {
	e, err := BuildEvent(EventSent, held(holderA), lastFrom(holderA), t0, Cause{Receipt: &Receipt{ReceivedIn: StatusPaymentInitiated, Deviations: []Deviation{}}})
	if err != nil {
		t.Fatal(err)
	}
	if e.Deviations != nil {
		t.Errorf("deviations %#v, want absent", e.Deviations)
	}
}

func TestBuildEventDoesNotAliasItsInputs(t *testing.T) {
	last := lastFrom(holderA)
	devs := []Deviation{DeviationReceivedInWrongStatus}
	e, err := BuildEvent(EventSent, held(holderA), last, t0, Cause{Receipt: &Receipt{ReceivedIn: StatusActive, Deviations: devs}})
	if err != nil {
		t.Fatal(err)
	}
	*last.Notification.TipAmount = 7
	devs[0] = DeviationActionContradictsHash
	if *e.TipAmount != 100 || e.Deviations[0] != DeviationReceivedInWrongStatus {
		t.Error("the event shares memory with the notification or the receipt")
	}
}

func TestBuildEventRefusesUnreadableRequest(t *testing.T) {
	r := active()
	r.RequestJSON = []byte(`{"paymentMethods":`)
	if _, err := BuildEvent(EventCleared, r, nil, t0, Cause{}); err == nil {
		t.Fatal("a corrupt request_json built an event")
	}
	if _, err := UpdateStatus(r, StatusUpdate{Status: StatusCancelled}, nil, t0, settings); err == nil {
		t.Fatal("a transition emitted from a corrupt request_json")
	}
}

func TestEventTypeCodes(t *testing.T) {
	want := map[EventType]string{EventInitiated: "INIT", EventSent: "SENT", EventFailed: "FAIL", EventCleared: "CLRD", EventCancelled: "CANC", "payment.other": ""}
	for typ, code := range want {
		if got := typ.Code(); got != code {
			t.Errorf("%s.Code() = %q, want %q", typ, got, code)
		}
		if code == "" {
			continue
		}
		if back, err := EventTypeFromCode(code); err != nil || back != typ {
			t.Errorf("EventTypeFromCode(%q) = %q, %v; want %s", code, back, err, typ)
		}
	}
	for _, code := range []string{"", "init", "PAID", "XXXX"} {
		if _, err := EventTypeFromCode(code); !errors.Is(err, ErrUnknownEventType) {
			t.Errorf("EventTypeFromCode(%q): %v, want ErrUnknownEventType", code, err)
		}
	}
}

// ------------------------------------------------------------------ the boundaries

const modulePath = "github.com/matera-inc/x9.150-qrcode"

// S9.3-AC1: internal/domain imports no database and no HTTP package. Only the standard library
// (without its network and SQL packages) and the UUID library are allowed.
func TestDomainImportsNoDatabaseAndNoHTTP(t *testing.T) {
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	fset := token.NewFileSet()
	for _, name := range files {
		if strings.HasSuffix(name, "_test.go") {
			continue
		}
		f, err := parser.ParseFile(fset, name, nil, parser.ImportsOnly)
		if err != nil {
			t.Fatal(err)
		}
		for _, imp := range f.Imports {
			path, _ := strconv.Unquote(imp.Path.Value)
			if !importAllowed(path) {
				t.Errorf("%s imports %s: the domain imports no database, no HTTP and no other package of this module", name, path)
			}
		}
	}
}

func importAllowed(path string) bool {
	if path == "github.com/google/uuid" {
		return true
	}
	first, _, _ := strings.Cut(path, "/")
	if strings.Contains(first, ".") {
		return false // a module outside the standard library
	}
	for _, banned := range []string{"net", "database", "crypto/tls"} {
		if path == banned || strings.HasPrefix(path, banned+"/") {
			return false
		}
	}
	return true
}

// S9.3-AC6: BuildEvent is the only code that constructs a payment event. Every Go file of the
// module outside this package is searched for a domain.Event literal or new(domain.Event).
func TestOnlyBuildEventConstructsAnEvent(t *testing.T) {
	repo := os.DirFS(filepath.Join("..", ".."))
	files := 0
	err := fs.WalkDir(repo, ".", func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			name := d.Name()
			if path == "internal/domain" || (path != "." && (strings.HasPrefix(name, ".") || name == "testdata" || name == "target" || name == "node_modules")) {
				return fs.SkipDir
			}
			return nil
		}
		if !strings.HasSuffix(path, ".go") {
			return nil
		}
		files++
		src, err := fs.ReadFile(repo, path)
		if err != nil {
			return err
		}
		for _, where := range eventConstructions(t, path, src) {
			t.Errorf("%s builds a payment event; only domain.BuildEvent may (ADR-0035)", where)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if files < 10 {
		t.Fatalf("searched only %d Go files: the walk is not over the module", files)
	}
}

// The detector itself, on planted code, so the test above cannot pass by finding nothing.
func TestEventConstructionDetector(t *testing.T) {
	planted := `package store
import d "` + modulePath + `/internal/domain"
var a = d.Event{Type: d.EventSent}
var b = &d.Event{}
var c = new(d.Event)
var ok, _ = d.BuildEvent(d.EventSent, d.Row{}, nil, now, d.Cause{})
`
	if got := eventConstructions(t, "planted.go", []byte(planted)); len(got) != 3 {
		t.Errorf("found %d constructions in planted code, want 3: %v", len(got), got)
	}
	clean := `package store
import "` + modulePath + `/internal/domain"
type Event struct{}
var e = Event{}
var x []domain.Event
`
	if got := eventConstructions(t, "clean.go", []byte(clean)); len(got) != 0 {
		t.Errorf("false positives: %v", got)
	}
}

func eventConstructions(t *testing.T, path string, src []byte) []string {
	t.Helper()
	fset := token.NewFileSet()
	f, err := parser.ParseFile(fset, path, src, parser.SkipObjectResolution)
	if err != nil {
		t.Fatalf("parsing %s: %v", path, err)
	}
	alias := ""
	for _, imp := range f.Imports {
		p, _ := strconv.Unquote(imp.Path.Value)
		if p != modulePath+"/internal/domain" {
			continue
		}
		alias = "domain"
		if imp.Name != nil {
			alias = imp.Name.Name
		}
	}
	if alias == "" {
		return nil
	}
	isEvent := func(e ast.Expr) bool {
		sel, ok := e.(*ast.SelectorExpr)
		if !ok || sel.Sel.Name != "Event" {
			return false
		}
		x, ok := sel.X.(*ast.Ident)
		return ok && x.Name == alias
	}
	var found []string
	ast.Inspect(f, func(n ast.Node) bool {
		switch n := n.(type) {
		case *ast.CompositeLit:
			if isEvent(n.Type) {
				found = append(found, fset.Position(n.Pos()).String())
			}
		case *ast.CallExpr:
			if id, ok := n.Fun.(*ast.Ident); ok && id.Name == "new" && len(n.Args) == 1 && isEvent(n.Args[0]) {
				found = append(found, fset.Position(n.Pos()).String())
			}
		}
		return true
	})
	return found
}
