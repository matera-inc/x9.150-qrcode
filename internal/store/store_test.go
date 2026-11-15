// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// S9.5-AC1: which failures re-run the script, and what the others are answered with.
func TestFailuresAreRerunOrAnswered(t *testing.T) {
	pg := func(code string) error {
		return fmt.Errorf("wrapped: %w", &pgconn.PgError{Code: code, ConstraintName: "ux_qr_code_location_id"})
	}
	tests := []struct {
		name  string
		err   error
		rerun bool
		want  error
	}{
		{"zero rows", fmt.Errorf("x: %w", errStale), true, nil},
		{"serialization failure", pg("40001"), true, nil},
		{"deadlock", pg("40P01"), true, nil},
		{"lock timeout", pg("55P03"), true, nil},
		{"statement timeout is a 503", pg("57014"), false, ErrUnavailable},
		{"unique violation is the caller's", pg("23505"), false, ErrDuplicate},
		{"anything else is passed on", pg("42501"), false, nil},
		{"not found", ErrNotFound, false, ErrNotFound},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := rerun(tt.err); got != tt.rerun {
				t.Fatalf("rerun = %v, want %v", got, tt.rerun)
			}
			if tt.rerun || tt.want == nil {
				return
			}
			got := answer(tt.err)
			if !errors.Is(got, tt.want) {
				t.Fatalf("answer = %v, want %v", got, tt.want)
			}
		})
	}
	var dup *DuplicateError
	if !errors.As(answer(pg("23505")), &dup) || dup.Constraint != "ux_qr_code_location_id" {
		t.Fatalf("a 23505 does not name its constraint: %v", dup)
	}
}

// S9.5-AC1: exhausted re-runs are a 409 "busy" carrying the current status, never a 500.
func TestBusyIsAConflictWithTheCurrentStatus(t *testing.T) {
	err := busy(domain.StatusPaymentInitiated)
	if !errors.Is(err, ErrBusy) || !errors.Is(err, domain.ErrConflict) {
		t.Fatalf("busy is not ErrBusy and a conflict: %v", err)
	}
	var r *domain.Refusal
	if !errors.As(err, &r) || r.Kind != domain.KindConflict || r.CurrentStatus != domain.StatusPaymentInitiated {
		t.Fatalf("busy carries %+v", r)
	}
}

// S9.5-AC3, S9.5-AC4: QR Code and event ids are UUIDv7, location ids UUIDv4, and event ids come
// from one generator in increasing order.
func TestIDs(t *testing.T) {
	qr, err := NewQRCodeID()
	if err != nil || qr.Version() != 7 {
		t.Fatalf("QR Code id %s (v%d), %v", qr, qr.Version(), err)
	}
	loc, err := NewLocationID()
	if err != nil || loc.Version() != 4 {
		t.Fatalf("location id %s (v%d), %v", loc, loc.Version(), err)
	}
	prev := uuid.Nil
	for range 20_000 {
		id, err := newEventID()
		if err != nil {
			t.Fatal(err)
		}
		if id.Version() != 7 || string(id[:]) <= string(prev[:]) {
			t.Fatalf("event id %s (v%d) does not follow %s", id, id.Version(), prev)
		}
		prev = id
	}
}

// S9.5-AC7: a notify wakes every waiter at once, and the next wait is a fresh channel.
func TestSignal(t *testing.T) {
	s := newSignal()
	a, b := s.wait(), s.wait()
	select {
	case <-a:
		t.Fatal("closed before any notify")
	default:
	}
	s.notify()
	for _, ch := range []<-chan struct{}{a, b} {
		select {
		case <-ch:
		default:
			t.Fatal("a waiter was not woken")
		}
	}
	select {
	case <-s.wait():
		t.Fatal("the next wait is already closed")
	default:
	}
}

// S9.5-AC2, S9.5-AC4: every statement is plain and portable: no RETURNING, never
// schema-qualified, no clock of the database, no upsert (ADR-0023).
func TestSQLIsPortable(t *testing.T) {
	stmts := map[string]string{
		"select":           sqlSelectQRCode,
		"select location":  sqlSelectQRCodeByLocation,
		"insert":           sqlInsertQRCode,
		"notifications":    sqlSelectNotifications,
		"notification":     sqlInsertNotification,
		"audit":            sqlInsertAudit,
		"next seq":         sqlNextSeq,
		"event":            sqlInsertEvent,
		"events":           sqlSelectEvents,
		"last seq":         sqlSelectLastSeq,
		"update lifecycle": updateSQL(false, false),
		"update content":   updateSQL(true, false),
		"update location":  updateSQL(false, true),
		"lock timeout":     lockTimeoutSQL(EventLockTimeout),
	}
	forbidden := regexp.MustCompile(`(?i)\bRETURNING\b|\bx9_qrcode\.|\bnow\(\)|\bCURRENT_TIMESTAMP\b|\bLOCALTIMESTAMP\b|\bON\s+CONFLICT\b|::`)
	for name, sql := range stmts {
		if m := forbidden.FindString(sql); m != "" {
			t.Errorf("%s uses %q: %s", name, m, sql)
		}
	}
	if got := lockTimeoutSQL(EventLockTimeout); got != "SET LOCAL lock_timeout = '2s'" {
		t.Errorf("lock timeout statement = %q", got)
	}
	// Every placeholder an UPDATE form names is bound by writeRow.
	for _, f := range []struct {
		content, location bool
		params            int
	}{{false, false, 11}, {true, false, 13}, {false, true, 15}, {true, true, 15}} {
		if got := maxPlaceholder(updateSQL(f.content, f.location)); got != f.params {
			t.Errorf("updateSQL(%v, %v) names $%d, want $%d", f.content, f.location, got, f.params)
		}
	}
}

func maxPlaceholder(sql string) int {
	most := 0
	for _, m := range regexp.MustCompile(`\$(\d+)`).FindAllStringSubmatch(sql, -1) {
		var n int
		_, _ = fmt.Sscan(m[1], &n)
		most = max(most, n)
	}
	return most
}

// A Change the store must not apply is refused before anything is written.
func TestChangeValidation(t *testing.T) {
	now := time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC)
	id, loc := uuid.New(), uuid.New()
	before := &QRCode{ID: id, LocationID: loc, EMVContent: "emv"}
	written := domain.Decision{Row: before.Row, Write: true}
	moved := written
	moved.Row.LocationID = uuid.New()
	refused := domain.Decision{Row: before.Row, Refusal: &domain.Refusal{Kind: domain.KindBusinessRule}}
	release := &Release{Holder: QRCode{Row: domain.Row{ID: uuid.New(), LocationID: loc}}, EMVContent: "emv2"}
	release.Decision = domain.ReleaseLocation(domain.Row{ID: release.Holder.ID, LocationID: loc, Status: domain.StatusCancelled}, uuid.New(), now)
	notified := written
	notified.Notification = &domain.NotificationRecord{}

	tests := []struct {
		name   string
		change Change
		ok     bool
	}{
		{"a plain update", Change{Before: before, Decision: written}, true},
		{"a refusal that writes nothing", Change{Before: before, Decision: refused}, true},
		{"a create with its EMV content", Change{Decision: written, EMVContent: "emv"}, true},
		{"a create without EMV content", Change{Decision: written}, false},
		{"a moved location with its EMV content", Change{Before: before, Decision: moved, EMVContent: "x"}, true},
		{"a moved location without EMV content", Change{Before: before, Decision: moved}, false},
		{"EMV content recomputed for a location that did not move", Change{Before: before, Decision: written, EMVContent: "x"}, false},
		{"another row than the one read", Change{Before: &QRCode{ID: uuid.New(), LocationID: loc}, Decision: written}, false},
		{"a release", Change{Release: release, Before: before, Decision: written}, true},
		{"a release with a refused transition", Change{Release: release, Before: before, Decision: refused}, false},
		{"an audit row with a refusal", Change{Audit: &Audit{}, Before: before, Decision: refused}, false},
		{"a notification without its evidence", Change{Before: before, Decision: notified}, false},
		{"a notification with its evidence", Change{Before: before, Decision: notified, Evidence: &Evidence{JSON: json.RawMessage(`{}`)}}, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.change.validate()
			if (err == nil) != tt.ok {
				t.Fatalf("validate = %v, want ok %v", err, tt.ok)
			}
			if err != nil && !errors.Is(err, ErrInvalidChange) {
				t.Fatalf("validate = %v, not ErrInvalidChange", err)
			}
		})
	}

	refusedRelease := *release
	refusedRelease.Decision = domain.ReleaseLocation(domain.Row{ID: release.Holder.ID, LocationID: loc, Status: domain.StatusActive, ValidUntil: now.Add(time.Hour)}, uuid.New(), now)
	if err := (Change{Release: &refusedRelease, Before: before, Decision: written}).validate(); !strings.Contains(fmt.Sprint(err), "refused location release") {
		t.Fatalf("a live holder's release was accepted: %v", err)
	}
}
