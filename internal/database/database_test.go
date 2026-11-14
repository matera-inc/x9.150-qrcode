// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package database

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.yaml.in/yaml/v3"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/dbtest"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

func baseConfig() config.Database {
	return config.Database{
		Host: "db.example.com", Port: 5432, Name: "bank", User: "x9_qrcode_app", Password: "pw",
		Schema: "x9_qrcode", SSLMode: "disable", PoolWrites: 8, PoolReads: 4,
	}
}

// writeCA writes a self-signed CA certificate, generated here (no bundled key is used).
func writeCA(t *testing.T) string {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test CA"},
		NotBefore: time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC), NotAfter: time.Date(2100, 1, 1, 0, 0, 0, 0, time.UTC), IsCA: true, BasicConstraintsValid: true,
		KeyUsage: x509.KeyUsageCertSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "ca.pem")
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

// S7.2-AC1: two pools, writes 8 and reads 4, application_name=x9-qrcode, configurable TLS.
func TestPoolConfig(t *testing.T) {
	cfg := baseConfig()
	for _, size := range []int{cfg.PoolWrites, cfg.PoolReads} {
		pc, err := PoolConfig(cfg, size)
		if err != nil {
			t.Fatal(err)
		}
		if int(pc.MaxConns) != size || pc.MinConns != 0 || pc.MaxConnIdleTime != 5*time.Minute {
			t.Errorf("pool: max %d min %d idle %v", pc.MaxConns, pc.MinConns, pc.MaxConnIdleTime)
		}
		if got := pc.ConnConfig.RuntimeParams["application_name"]; got != ApplicationName {
			t.Errorf("application_name = %q", got)
		}
		if pc.ConnConfig.Password != "pw" || strings.Contains(connString(cfg), "pw") {
			t.Errorf("the password is not set, or is in the connection string")
		}
		// No session state: nothing but application_name is sent at connection start.
		for k := range pc.ConnConfig.RuntimeParams {
			if k != "application_name" {
				t.Errorf("unexpected session parameter %q", k)
			}
		}
	}
}

func TestPoolConfigTLS(t *testing.T) {
	ca := writeCA(t)
	for _, tc := range []struct {
		mode       string
		rootCert   string
		wantTLS    bool
		wantVerify bool
	}{
		{"disable", "", false, false},
		{"require", "", true, false},
		{"verify-ca", ca, true, true},
		{"verify-full", ca, true, true},
	} {
		t.Run(tc.mode, func(t *testing.T) {
			cfg := baseConfig()
			cfg.SSLMode, cfg.SSLRootCert = tc.mode, tc.rootCert
			pc, err := PoolConfig(cfg, 1)
			if err != nil {
				t.Fatal(err)
			}
			tlsCfg := pc.ConnConfig.TLSConfig
			if (tlsCfg != nil) != tc.wantTLS {
				t.Fatalf("TLS = %v, want %v", tlsCfg != nil, tc.wantTLS)
			}
			if tc.wantVerify && tlsCfg.RootCAs == nil {
				t.Fatal("no root CA for a verifying mode")
			}
			if tc.mode == "verify-full" && tlsCfg.ServerName != cfg.Host {
				t.Fatalf("ServerName = %q, want %q", tlsCfg.ServerName, cfg.Host)
			}
			if tc.mode == "verify-full" && len(pc.ConnConfig.Fallbacks) != 0 {
				t.Fatal("verify-full falls back to plain text")
			}
		})
	}
}

func TestPoolConfigPasswordFileAndExecMode(t *testing.T) {
	file := filepath.Join(t.TempDir(), "pw")
	if err := os.WriteFile(file, []byte("first\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := baseConfig()
	cfg.Password, cfg.PasswordFile, cfg.QueryExecMode = "", file, "exec"
	pc, err := PoolConfig(cfg, 1)
	if err != nil {
		t.Fatal(err)
	}
	if pc.ConnConfig.DefaultQueryExecMode != pgx.QueryExecModeExec {
		t.Errorf("exec mode = %v", pc.ConnConfig.DefaultQueryExecMode)
	}
	cc := pc.ConnConfig.Copy()
	if err := pc.BeforeConnect(t.Context(), cc); err != nil || cc.Password != "first" {
		t.Fatalf("password %q, err %v", cc.Password, err)
	}
	// Rotation: the next connection reads the new file.
	if err := os.WriteFile(file, []byte("second"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := pc.BeforeConnect(t.Context(), cc); err != nil || cc.Password != "second" {
		t.Fatalf("after rotation: password %q, err %v", cc.Password, err)
	}
}

func TestConnStringQuotes(t *testing.T) {
	cfg := baseConfig()
	cfg.Name = `it's a \ name`
	pc, err := PoolConfig(cfg, 1)
	if err != nil {
		t.Fatal(err)
	}
	if pc.ConnConfig.Database != cfg.Name {
		t.Fatalf("database = %q", pc.ConnConfig.Database)
	}
}

func TestUnreachable(t *testing.T) {
	for _, tc := range []struct {
		name string
		err  error
		want bool
	}{
		{"no answer", errors.New("dial tcp: connection refused"), true},
		{"connection exception", &pgconn.PgError{Code: "08006"}, true},
		{"starting up", fmt.Errorf("wrapped: %w", &pgconn.PgError{Code: "57P03"}), true},
		{"bad password", &pgconn.PgError{Code: "28P01"}, false},
		{"permission denied", &pgconn.PgError{Code: "42501"}, false},
		{"wrong schema", fmt.Errorf("x: %w", ErrWrongSchema), false},
		{"wrong changelog", ErrWrongChangeset, false},
		{"nil", nil, false},
	} {
		if got := Unreachable(tc.err); got != tc.want {
			t.Errorf("%s: Unreachable = %v, want %v", tc.name, got, tc.want)
		}
	}
}

// ExpectedChangeset must be the last changeset of db/changelog, so the binary and the schema it
// checks for cannot drift apart unnoticed.
func TestExpectedChangesetIsTheChangelogsLast(t *testing.T) {
	dir := filepath.Join(dbtest.RepoRoot(t), "db", "changelog")
	var master struct {
		DatabaseChangeLog []struct {
			Include *struct {
				File string `yaml:"file"`
			} `yaml:"include"`
		} `yaml:"databaseChangeLog"`
	}
	readYAML(t, filepath.Join(dir, "db.changelog-master.yaml"), &master)
	var last string
	for _, e := range master.DatabaseChangeLog {
		if e.Include == nil {
			continue
		}
		var file struct {
			DatabaseChangeLog []struct {
				ChangeSet *struct {
					ID string `yaml:"id"`
				} `yaml:"changeSet"`
			} `yaml:"databaseChangeLog"`
		}
		readYAML(t, filepath.Join(dir, e.Include.File), &file)
		for _, c := range file.DatabaseChangeLog {
			if c.ChangeSet != nil {
				last = c.ChangeSet.ID
			}
		}
	}
	if last != ExpectedChangeset {
		t.Fatalf("the changelog's last changeset is %q, ExpectedChangeset is %q: move the constant with the changelog", last, ExpectedChangeset)
	}
}

func readYAML(t *testing.T, path string, v any) {
	t.Helper()
	b, err := os.ReadFile(path) //nolint:gosec // G304: a file of this repository.
	if err != nil {
		t.Fatal(err)
	}
	if err := yaml.Unmarshal(b, v); err != nil {
		t.Fatalf("%s: %v", path, err)
	}
}

func applicationName(ctx context.Context, t *testing.T, pool *pgxpool.Pool) string {
	t.Helper()
	var name string
	if err := pool.QueryRow(ctx, "SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()").Scan(&name); err != nil {
		t.Fatal(err)
	}
	return name
}

// The database tests share one PostgreSQL with the real changelog applied.
func TestAgainstPostgres(t *testing.T) {
	db := dbtest.Start(t)
	ctx := t.Context()

	// S7.2-AC1: two pools on the primary, sized 8 and 4, named x9-qrcode.
	t.Run("pools", func(t *testing.T) {
		pools, err := Open(ctx, db.Config(dbtest.AppRole))
		if err != nil {
			t.Fatal(err)
		}
		defer pools.Close()
		if err := pools.Ping(ctx); err != nil {
			t.Fatal(err)
		}
		if w, r := pools.Write.Config().MaxConns, pools.Read.Config().MaxConns; w != 8 || r != 4 {
			t.Fatalf("pool sizes %d/%d, want 8/4", w, r)
		}
		for _, p := range []*pgxpool.Pool{pools.Write, pools.Read} {
			if got := applicationName(ctx, t, p); got != ApplicationName {
				t.Fatalf("application_name = %q", got)
			}
		}
	})

	// S7.2-AC2: the right schema and changelog pass.
	t.Run("schema check passes", func(t *testing.T) {
		pools, err := Open(ctx, db.Config(dbtest.AppRole))
		if err != nil {
			t.Fatal(err)
		}
		defer pools.Close()
		if err := CheckSchema(ctx, pools.Read, dbtest.Schema, ExpectedChangeset); err != nil {
			t.Fatal(err)
		}
	})

	// S7.2-AC2: current_schema() differs from the configured schema → refused, saying which.
	t.Run("configured schema differs", func(t *testing.T) {
		pools, err := Open(ctx, db.Config(dbtest.AppRole))
		if err != nil {
			t.Fatal(err)
		}
		defer pools.Close()
		err = CheckSchema(ctx, pools.Read, "bank_x9", ExpectedChangeset)
		if !errors.Is(err, ErrWrongSchema) || !strings.Contains(err.Error(), `current_schema() is "x9_qrcode", the configured schema is "bank_x9"`) {
			t.Fatalf("err = %v", err)
		}
	})

	// S7.2-AC2: a role whose search_path does not select the schema → refused.
	t.Run("role without the search_path", func(t *testing.T) {
		pools, err := Open(ctx, db.Config("postgres"))
		if err != nil {
			t.Fatal(err)
		}
		defer pools.Close()
		err = CheckSchema(ctx, pools.Read, dbtest.Schema, ExpectedChangeset)
		if !errors.Is(err, ErrWrongSchema) || !strings.Contains(err.Error(), `current_schema() is "public"`) {
			t.Fatalf("err = %v", err)
		}
	})

	t.Run("search_path names no visible schema", func(t *testing.T) {
		db.Psql(ctx, t, "CREATE ROLE x9_lost LOGIN; ALTER ROLE x9_lost SET search_path = nowhere")
		pools, err := Open(ctx, db.Config("x9_lost"))
		if err != nil {
			t.Fatal(err)
		}
		defer pools.Close()
		err = CheckSchema(ctx, pools.Read, dbtest.Schema, ExpectedChangeset)
		if !errors.Is(err, ErrWrongSchema) || !strings.Contains(err.Error(), "current_schema() is NULL") {
			t.Fatalf("err = %v", err)
		}
	})

	// S7.2-AC2: the latest applied changeset differs from the one the binary expects → refused.
	t.Run("changelog differs", func(t *testing.T) {
		pools, err := Open(ctx, db.Config(dbtest.AppRole))
		if err != nil {
			t.Fatal(err)
		}
		defer pools.Close()
		err = CheckSchema(ctx, pools.Read, dbtest.Schema, "0003-01-not-applied-yet")
		want := `the latest changeset applied in schema "x9_qrcode" is "` + ExpectedChangeset + `", this binary expects "0003-01-not-applied-yet"`
		if !errors.Is(err, ErrWrongChangeset) || !strings.Contains(err.Error(), want) {
			t.Fatalf("err = %v", err)
		}
	})

	// S7.2-AC3: PgBouncer ≥ 1.21 in transaction mode.
	t.Run("pgbouncer transaction mode", func(t *testing.T) {
		for _, tc := range []struct {
			name                  string
			maxPreparedStatements int
			execMode              string
		}{
			{"prepared statements", 100, ""},
			{"max_prepared_statements=0 with exec mode", 0, "exec"},
		} {
			t.Run(tc.name, func(t *testing.T) {
				host, port := startPgBouncer(ctx, t, db, tc.maxPreparedStatements)
				cfg := db.Config(dbtest.AppRole)
				cfg.Host, cfg.Port, cfg.QueryExecMode = host, port, tc.execMode
				pools, err := Open(ctx, cfg)
				if err != nil {
					t.Fatal(err)
				}
				defer pools.Close()
				exerciseThroughBouncer(ctx, t, pools)
			})
		}
	})
}

// exerciseThroughBouncer runs the schema check many times, concurrently, on both pools: with
// session state it would fail as transactions land on different server connections.
func exerciseThroughBouncer(ctx context.Context, t *testing.T, pools *Pools) {
	t.Helper()
	errs := make(chan error, 48)
	for i := range cap(errs) {
		pool := pools.Read
		if i%2 == 0 {
			pool = pools.Write
		}
		go func() { errs <- CheckSchema(ctx, pool, dbtest.Schema, ExpectedChangeset) }()
	}
	for range cap(errs) {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
	}
}
