// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package database connects to PostgreSQL as a polite guest in a bank's database (ADR-0032): two
// small pools on the primary, writes and reads (ADR-0025), named x9-qrcode, with the TLS the
// operator configured, and no session state, so PgBouncer (≥ 1.21) in transaction mode works.
//
// Before the service serves anything, CheckSchema proves it is in the right place: the role's own
// search_path selects the configured schema, and the changelog applied there is the one this
// binary was written for.
package database

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
)

// ApplicationName is what a DBA sees in pg_stat_activity for every connection of this service.
const ApplicationName = "x9-qrcode"

// ExpectedChangeset is the id of the last changeset in db/changelog that this binary was written
// for. A test keeps it equal to the changelog's last changeset, so adding a changeset without
// moving this constant fails the build.
const ExpectedChangeset = "0005-02-event-deviations-comment"

const (
	// idleTimeout releases an idle connection back to the bank's database (ADR-0032).
	idleTimeout = 5 * time.Minute
	// maxLifetime bounds a connection's life, so a rotated credential or a moved primary is picked
	// up without a restart.
	maxLifetime = 30 * time.Minute
)

// Pools are the two connection pools. Both connect to the primary: reading from a standby would
// break read-your-writes after a PATCH (ADR-0025).
type Pools struct {
	Write *pgxpool.Pool
	Read  *pgxpool.Pool
}

// Open creates both pools. It does not wait for the database: a pool connects lazily, so call Ping
// or CheckSchema to know whether it is reachable.
func Open(ctx context.Context, cfg config.Database) (*Pools, error) {
	write, err := newPool(ctx, cfg, cfg.PoolWrites)
	if err != nil {
		return nil, fmt.Errorf("the write pool: %w", err)
	}
	read, err := newPool(ctx, cfg, cfg.PoolReads)
	if err != nil {
		write.Close()
		return nil, fmt.Errorf("the read pool: %w", err)
	}
	return &Pools{Write: write, Read: read}, nil
}

// Close closes both pools, waiting for borrowed connections to come back.
func (p *Pools) Close() {
	p.Write.Close()
	p.Read.Close()
}

// Ping proves both pools can reach the database.
func (p *Pools) Ping(ctx context.Context) error {
	if err := p.Write.Ping(ctx); err != nil {
		return fmt.Errorf("the write pool: %w", err)
	}
	if err := p.Read.Ping(ctx); err != nil {
		return fmt.Errorf("the read pool: %w", err)
	}
	return nil
}

// PoolConfig builds one pool's configuration. It is exported so the settings can be tested without
// a database.
func PoolConfig(cfg config.Database, maxConns int) (*pgxpool.Config, error) {
	pc, err := pgxpool.ParseConfig(connString(cfg))
	if err != nil {
		return nil, err
	}
	if maxConns < 1 || maxConns > 1000 {
		return nil, fmt.Errorf("pool size %d is out of range", maxConns)
	}
	pc.MaxConns = int32(maxConns)
	pc.MinConns = 0
	pc.MaxConnIdleTime = idleTimeout
	pc.MaxConnLifetime = maxLifetime
	if cfg.QueryExecMode != "" {
		mode, err := queryExecMode(cfg.QueryExecMode)
		if err != nil {
			return nil, err
		}
		pc.ConnConfig.DefaultQueryExecMode = mode
	}
	if cfg.PasswordFile != "" {
		// Read on every new connection, so rotating the credential is a file change and a reconnect.
		file := cfg.PasswordFile
		pc.BeforeConnect = func(_ context.Context, cc *pgx.ConnConfig) error {
			b, err := os.ReadFile(file) //nolint:gosec // G304: the operator's own secret file.
			if err != nil {
				return fmt.Errorf("reading the database password file: %w", err)
			}
			cc.Password = strings.TrimRight(string(b), "\r\n")
			return nil
		}
	} else {
		pc.ConnConfig.Password = cfg.Password
	}
	return pc, nil
}

func newPool(ctx context.Context, cfg config.Database, maxConns int) (*pgxpool.Pool, error) {
	pc, err := PoolConfig(cfg, maxConns)
	if err != nil {
		return nil, err
	}
	return pgxpool.NewWithConfig(ctx, pc)
}

// connString is a keyword/value connection string. The password is not in it: it is set on the
// configuration, so it never appears in a parse error.
func connString(cfg config.Database) string {
	kv := []string{
		"host=" + quote(cfg.Host),
		"port=" + strconv.Itoa(cfg.Port),
		"dbname=" + quote(cfg.Name),
		"user=" + quote(cfg.User),
		"sslmode=" + quote(cfg.SSLMode),
		"application_name=" + ApplicationName,
	}
	if cfg.SSLRootCert != "" {
		kv = append(kv, "sslrootcert="+quote(cfg.SSLRootCert))
	}
	return strings.Join(kv, " ")
}

// quote writes a value the way libpq's keyword/value syntax expects.
func quote(v string) string {
	return "'" + strings.NewReplacer(`\`, `\\`, `'`, `\'`).Replace(v) + "'"
}

func queryExecMode(s string) (pgx.QueryExecMode, error) {
	switch s {
	case "cache_statement":
		return pgx.QueryExecModeCacheStatement, nil
	case "cache_describe":
		return pgx.QueryExecModeCacheDescribe, nil
	case "describe_exec":
		return pgx.QueryExecModeDescribeExec, nil
	case "exec":
		return pgx.QueryExecModeExec, nil
	case "simple_protocol":
		return pgx.QueryExecModeSimpleProtocol, nil
	default:
		return 0, fmt.Errorf("unknown query exec mode %q", s)
	}
}

// Unreachable reports whether err means the database could not be reached, or is not ready yet,
// rather than that it answered: only then is waiting for it worth anything. A refused credential,
// a missing privilege or a wrong schema is an answer, and retrying it would only hide it.
func Unreachable(err error) bool {
	if err == nil || errors.Is(err, ErrWrongSchema) || errors.Is(err, ErrWrongChangeset) {
		return false
	}
	if pgErr, ok := errors.AsType[*pgconn.PgError](err); ok {
		// Class 08: connection exceptions. 57P01–57P03: shutting down, or starting up.
		return strings.HasPrefix(pgErr.Code, "08") ||
			pgErr.Code == "57P01" || pgErr.Code == "57P02" || pgErr.Code == "57P03"
	}
	return true // no answer from the server at all: refused, timed out, unresolvable
}

// ErrWrongSchema and ErrWrongChangeset say why CheckSchema refused.
var (
	ErrWrongSchema    = errors.New("wrong schema")
	ErrWrongChangeset = errors.New("wrong changelog")
)

// CheckSchema refuses unless current_schema() is the configured schema and the latest changeset
// applied there has the id this binary expects (ADR-0032). It runs in one short READ COMMITTED
// transaction, set explicitly, as every transaction of this service is (ADR-0023).
func CheckSchema(ctx context.Context, pool *pgxpool.Pool, schema, changeset string) error {
	var current *string
	var applied string
	err := pgx.BeginTxFunc(ctx, pool, pgx.TxOptions{IsoLevel: pgx.ReadCommitted, AccessMode: pgx.ReadOnly}, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "SELECT set_config('statement_timeout', '30s', true)"); err != nil {
			return err
		}
		// Non-portable: current_schema() (ADR-0023 recorded exception). It is how PostgreSQL says
		// which schema the role's search_path selected, which is exactly what ADR-0032 checks.
		if err := tx.QueryRow(ctx, "SELECT current_schema()").Scan(&current); err != nil {
			return fmt.Errorf("reading current_schema(): %w", err)
		}
		if current == nil || *current != schema {
			return nil // reported below, without touching a table that may not be ours
		}
		err := tx.QueryRow(ctx,
			"SELECT id FROM databasechangelog ORDER BY orderexecuted DESC FETCH FIRST 1 ROWS ONLY").Scan(&applied)
		if pgErr, ok := errors.AsType[*pgconn.PgError](err); errors.Is(err, pgx.ErrNoRows) || (ok && pgErr.Code == "42P01") {
			applied = "" // no changeset, or no changelog table at all: nothing was applied here
			return nil
		}
		if err != nil {
			return fmt.Errorf("reading the applied changelog (databasechangelog): %w", err)
		}
		return nil
	})
	if err != nil {
		return fmt.Errorf("checking the schema: %w", err)
	}
	if current == nil {
		return fmt.Errorf("%w: current_schema() is NULL, the configured schema is %q: the search_path of the "+
			"application role names no schema it can see; the changelog sets it with ALTER ROLE … SET search_path (db/README.md)",
			ErrWrongSchema, schema)
	}
	if *current != schema {
		return fmt.Errorf("%w: current_schema() is %q, the configured schema is %q: connect as the application role, "+
			"whose search_path selects the schema, or set database.schema (DATABASE_SCHEMA) to match (db/README.md)",
			ErrWrongSchema, *current, schema)
	}
	if applied != changeset {
		if applied == "" {
			applied = "nothing"
		} else {
			applied = strconv.Quote(applied)
		}
		return fmt.Errorf("%w: the latest changeset applied in schema %q is %s, this binary expects %q: "+
			"apply db/changelog with Liquibase before starting this version (db/README.md)",
			ErrWrongChangeset, schema, applied, changeset)
	}
	return nil
}
