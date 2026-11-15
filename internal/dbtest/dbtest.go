// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package dbtest starts a throwaway PostgreSQL with the real changelog applied, for tests. It runs
// what a bank's DBA and Liquibase would (db/README.md): db/local/bootstrap.sql as a superuser, then
// `liquibase update` as the owner role, with the official liquibase/liquibase:4.33.0 image. Files
// are copied into the containers, never bind-mounted, so it works on Docker hosts that cannot mount
// the repository's directory.
//
// The database trusts every connection, so any password works. It is skipped under -short.
// X9_TEST_POSTGRES_IMAGE picks another PostgreSQL image (default postgres:16).
package dbtest

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/testcontainers/testcontainers-go"
	tcexec "github.com/testcontainers/testcontainers-go/exec"
	"github.com/testcontainers/testcontainers-go/network"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
)

// Images used by the tests. Liquibase is pinned to the last Apache-2.0 release (ADR-0031).
const (
	DefaultPostgresImage = "postgres:16"
	LiquibaseImage       = "liquibase/liquibase:4.33.0"
)

// Names the changelog uses by default (db/changelog/db.changelog-master.yaml).
const (
	Schema   = "x9_qrcode"
	Owner    = "x9_qrcode_owner"
	AppRole  = "x9_qrcode_app"
	ReadOnly = "x9_qrcode_readonly"
	// EventReader is the plain-SQL event reader of docs/EVENTS-TABLE.md (ADR-0037).
	EventReader = "x9_qrcode_event_reader"
)

// DB is a running PostgreSQL with the schema applied.
type DB struct {
	// Host and Port reach PostgreSQL from the test process.
	Host string
	Port int
	// Network and Alias reach it from another container on the same network.
	Network string
	Alias   string
	pg      testcontainers.Container
}

// Config is a complete database configuration for user, as the service would read it.
func (d *DB) Config(user string) config.Database {
	return config.Database{
		Host: d.Host, Port: d.Port, Name: "postgres", User: user, Password: "unused",
		Schema: Schema, SSLMode: "disable", PoolWrites: 8, PoolReads: 4,
	}
}

// Psql runs SQL as the superuser and returns its output.
func (d *DB) Psql(ctx context.Context, t testing.TB, sql string) string {
	t.Helper()
	code, out, err := d.pg.Exec(ctx, []string{"psql", "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-c", sql}, tcexec.Multiplexed())
	if err != nil {
		t.Fatalf("psql: %v", err)
	}
	b, _ := io.ReadAll(out)
	if code != 0 {
		t.Fatalf("psql %q exited %d: %s", sql, code, b)
	}
	return strings.TrimSpace(string(b))
}

// Stop stops PostgreSQL (the container stays, so Start can bring it back).
func (d *DB) Stop(ctx context.Context, t testing.TB) {
	t.Helper()
	timeout := 10 * time.Second
	if err := d.pg.Stop(ctx, &timeout); err != nil {
		t.Fatal(err)
	}
}

// Start starts PostgreSQL again after Stop; its published port may change, and Host/Port follow.
func (d *DB) Start(ctx context.Context, t testing.TB) {
	t.Helper()
	if err := d.pg.Start(ctx); err != nil {
		t.Fatal(err)
	}
	d.refreshEndpoint(ctx, t)
}

// RepoRoot is the repository's root directory.
func RepoRoot(t testing.TB) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("cannot locate the repository")
	}
	return filepath.Clean(filepath.Join(filepath.Dir(file), "..", ".."))
}

// Start starts PostgreSQL, bootstraps the owner role and schema, and applies the changelog. The
// containers are removed when the test ends.
func Start(t testing.TB) *DB {
	t.Helper()
	if testing.Short() {
		t.Skip("needs Docker; skipped under -short")
	}
	ctx := t.Context()
	root := RepoRoot(t)
	image := DefaultPostgresImage
	if v, ok := os.LookupEnv("X9_TEST_POSTGRES_IMAGE"); ok && v != "" { //nolint:forbidigo // a test-only switch, not service configuration
		image = v
	}

	net, err := network.New(ctx)
	if err != nil {
		t.Fatalf("creating a Docker network: %v", err)
	}
	t.Cleanup(func() { _ = net.Remove(context.WithoutCancel(ctx)) })

	const alias = "postgres"
	pg, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		Image:          image,
		Env:            map[string]string{"POSTGRES_HOST_AUTH_METHOD": "trust"},
		ExposedPorts:   []string{"5432/tcp"},
		Networks:       []string{net.Name},
		NetworkAliases: map[string][]string{net.Name: {alias}},
		Files: []testcontainers.ContainerFile{{
			HostFilePath: filepath.Join(root, "db", "local", "bootstrap.sql"), ContainerFilePath: "/x9/bootstrap.sql", FileMode: 0o644,
		}},
		WaitingFor: wait.ForAll(
			wait.ForLog("database system is ready to accept connections").WithOccurrence(2),
			wait.ForListeningPort("5432/tcp"),
		).WithDeadline(2 * time.Minute),
		Started: true,
	})
	testcontainers.CleanupContainer(t, pg)
	if err != nil {
		t.Fatalf("starting %s: %v", image, err)
	}
	d := &DB{Network: net.Name, Alias: alias, pg: pg}
	d.refreshEndpoint(ctx, t)

	code, out, err := pg.Exec(ctx, []string{"psql", "-X", "-q", "-U", "postgres", "-v", "owner=" + Owner, "-v", "schema=" + Schema, "-f", "/x9/bootstrap.sql"}, tcexec.Multiplexed())
	if err != nil || code != 0 {
		b, _ := io.ReadAll(out)
		t.Fatalf("bootstrap.sql: exit %d, %v: %s", code, err, b)
	}

	lb, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		Image:    LiquibaseImage,
		Networks: []string{net.Name},
		Files: []testcontainers.ContainerFile{{
			HostFilePath: filepath.Join(root, "db", "changelog"), ContainerFilePath: "/liquibase/changelog", FileMode: 0o755,
		}},
		Cmd: []string{
			"--show-banner=false", "--search-path=/liquibase",
			"--changelog-file=changelog/db.changelog-master.yaml",
			fmt.Sprintf("--url=jdbc:postgresql://%s:5432/postgres", alias), "--username=" + Owner,
			"--default-schema-name=" + Schema, "--liquibase-schema-name=" + Schema, "update",
		},
		WaitingFor: wait.ForExit().WithExitTimeout(3 * time.Minute),
		Started:    true,
	})
	testcontainers.CleanupContainer(t, lb)
	if err != nil {
		t.Fatalf("running Liquibase: %v", err)
	}
	state, err := lb.State(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if state.ExitCode != 0 {
		logs, _ := lb.Logs(ctx)
		b, _ := io.ReadAll(logs)
		t.Fatalf("liquibase update exited %d:\n%s", state.ExitCode, b)
	}
	return d
}

func (d *DB) refreshEndpoint(ctx context.Context, t testing.TB) {
	t.Helper()
	host, err := d.pg.Host(ctx)
	if err != nil {
		t.Fatal(err)
	}
	port, err := d.pg.MappedPort(ctx, "5432/tcp")
	if err != nil {
		t.Fatal(err)
	}
	d.Host, d.Port = host, int(port.Num())
}
