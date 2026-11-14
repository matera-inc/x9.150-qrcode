// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package database

import (
	"context"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/matera-inc/x9.150-qrcode/internal/dbtest"
)

// pgBouncerImage is the oldest PgBouncer the service supports (ADR-0032): 1.21, the first with
// prepared statements in transaction mode.
const pgBouncerImage = "edoburu/pgbouncer:1.21.0-p2"

// startPgBouncer puts PgBouncer in transaction mode in front of db and returns where to reach it.
func startPgBouncer(ctx context.Context, t *testing.T, db *dbtest.DB, maxPreparedStatements int) (string, int) {
	t.Helper()
	dir := t.TempDir()
	ini := "[databases]\n" +
		"postgres = host=" + db.Alias + " port=5432 dbname=postgres\n" +
		"[pgbouncer]\n" +
		"listen_addr = 0.0.0.0\n" +
		"listen_port = 6432\n" +
		"auth_type = trust\n" +
		"auth_file = /etc/pgbouncer/userlist.txt\n" +
		"pool_mode = transaction\n" +
		"default_pool_size = 3\n" +
		"max_prepared_statements = " + strconv.Itoa(maxPreparedStatements) + "\n"
	for name, content := range map[string]string{
		"pgbouncer.ini": ini,
		"userlist.txt":  `"` + dbtest.AppRole + `" ""` + "\n",
	} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o644); err != nil { //nolint:gosec // G306: read by the container's own user.
			t.Fatal(err)
		}
	}
	c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		Image:        pgBouncerImage,
		ExposedPorts: []string{"6432/tcp"},
		Networks:     []string{db.Network},
		Files: []testcontainers.ContainerFile{
			{HostFilePath: filepath.Join(dir, "pgbouncer.ini"), ContainerFilePath: "/etc/pgbouncer/pgbouncer.ini", FileMode: 0o644},
			{HostFilePath: filepath.Join(dir, "userlist.txt"), ContainerFilePath: "/etc/pgbouncer/userlist.txt", FileMode: 0o644},
		},
		WaitingFor: wait.ForListeningPort("6432/tcp").WithStartupTimeout(time.Minute),
		Started:    true,
	})
	testcontainers.CleanupContainer(t, c)
	if err != nil {
		t.Fatalf("starting %s: %v", pgBouncerImage, err)
	}
	host, err := c.Host(ctx)
	if err != nil {
		t.Fatal(err)
	}
	port, err := c.MappedPort(ctx, "6432/tcp")
	if err != nil {
		t.Fatal(err)
	}
	return host, int(port.Num())
}
