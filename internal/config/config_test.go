// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package config

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// complete is the smallest environment that starts.
func complete() []string {
	return []string{
		"DATABASE_HOST=db.example.com",
		"DATABASE_NAME=bank",
		"DATABASE_USER=x9_qrcode_app",
		"DATABASE_PASSWORD=secret",
		"DATABASE_SSLMODE=require",
	}
}

func writeFile(t *testing.T, name, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func without(env []string, name string) []string {
	var out []string
	for _, kv := range env {
		if !strings.HasPrefix(kv, name+"=") {
			out = append(out, kv)
		}
	}
	return out
}

// S7.1-AC1: the defaults hold where nothing security-relevant is concerned.
func TestLoadDefaults(t *testing.T) {
	cfg, err := Load("", complete())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Server.Port != 8080 || cfg.Database.Port != 5432 || cfg.Database.Schema != "x9_qrcode" ||
		cfg.Database.PoolWrites != 8 || cfg.Database.PoolReads != 4 || cfg.Reservation.TTLSeconds != 90 ||
		cfg.Log.Level != "info" {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
}

// S7.1-AC1: the environment takes precedence over the file.
func TestEnvironmentOverridesFile(t *testing.T) {
	path := writeFile(t, "x9.yaml", `
server:
  port: 9000
database:
  host: from-file
  name: bank
  user: x9_qrcode_app
  password: secret
  sslmode: disable
  pool-writes: 6
reservation:
  ttl-seconds: 30
`)
	cfg, err := Load(path, []string{"DATABASE_HOST=from-env", "X9_RESERVATION_TTL_SECONDS=0"})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Database.Host != "from-env" {
		t.Errorf("database.host = %q, want the environment's", cfg.Database.Host)
	}
	if cfg.Reservation.TTLSeconds != 0 {
		t.Errorf("reservation.ttl-seconds = %d, want the environment's 0", cfg.Reservation.TTLSeconds)
	}
	if cfg.Server.Port != 9000 || cfg.Database.PoolWrites != 6 || cfg.Database.SSLMode != "disable" {
		t.Errorf("the file's values were lost: %+v", cfg)
	}
}

// S7.1-AC1: every required key is validated, and the error names the key and its variable.
func TestRequiredKeysAreNamed(t *testing.T) {
	for _, tc := range []struct{ drop, want string }{
		{"DATABASE_HOST", "database.host (DATABASE_HOST): required"},
		{"DATABASE_NAME", "database.name (DATABASE_NAME): required"},
		{"DATABASE_USER", "database.user (DATABASE_USER): required"},
		{"DATABASE_PASSWORD", "database.password-file (DATABASE_PASSWORD_FILE): required"},
		{"DATABASE_SSLMODE", "database.sslmode (DATABASE_SSLMODE): required"},
	} {
		t.Run(tc.drop, func(t *testing.T) {
			_, err := Load("", without(complete(), tc.drop))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to contain %q", err, tc.want)
			}
		})
	}
}

// S7.1-AC1: all problems are reported together.
func TestAllProblemsAtOnce(t *testing.T) {
	_, err := Load("", nil)
	if err == nil {
		t.Fatal("an empty configuration started")
	}
	for _, key := range []string{"database.host", "database.name", "database.user", "database.password-file", "database.sslmode"} {
		if !strings.Contains(err.Error(), key) {
			t.Errorf("the error does not name %s: %v", key, err)
		}
	}
}

// S7.1-AC1: nothing security-relevant has a silent default.
func TestSecurityKeysHaveNoDefault(t *testing.T) {
	d := defaults().Database
	if d.Host != "" || d.User != "" || d.Password != "" || d.PasswordFile != "" || d.SSLMode != "" || d.SSLRootCert != "" {
		t.Fatalf("a security-relevant key has a default: %+v", d)
	}
}

func TestInvalidValues(t *testing.T) {
	for _, tc := range []struct {
		name string
		env  []string
		want string
	}{
		{"port not a number", []string{"DATABASE_PORT=abc"}, "database.port (DATABASE_PORT): \"abc\" is not an integer"},
		{"port out of range", []string{"X9_SERVER_PORT=70000"}, "server.port (X9_SERVER_PORT): must be a port"},
		{"bad sslmode", []string{"DATABASE_SSLMODE=sometimes"}, "database.sslmode (DATABASE_SSLMODE): must be one of"},
		{"verify-full without a root CA", []string{"DATABASE_SSLMODE=verify-full"}, "database.sslrootcert (DATABASE_SSLROOTCERT): required when database.sslmode is verify-full"},
		{"missing root CA file", []string{"DATABASE_SSLROOTCERT=/nonexistent/ca.pem"}, "database.sslrootcert (DATABASE_SSLROOTCERT): cannot be read"},
		{"password and file", []string{"DATABASE_PASSWORD_FILE=/run/secrets/x"}, "not both"},
		{"bad schema", []string{"DATABASE_SCHEMA=X9; DROP"}, "database.schema (DATABASE_SCHEMA): must be a lowercase identifier"},
		{"no write pool", []string{"DATABASE_POOL_WRITES=0"}, "database.pool-writes (DATABASE_POOL_WRITES): must be at least 1"},
		{"bad exec mode", []string{"DATABASE_QUERY_EXEC_MODE=fast"}, "database.query-exec-mode (DATABASE_QUERY_EXEC_MODE)"},
		{"negative reservation", []string{"X9_RESERVATION_TTL_SECONDS=-1"}, "reservation.ttl-seconds (X9_RESERVATION_TTL_SECONDS): must not be negative"},
		{"bad log level", []string{"X9_LOG_LEVEL=loud"}, "log.level (X9_LOG_LEVEL)"},
		{"test clock", []string{"X9_TESTING_CLOCKCONTROL=true"}, "testing.clock-control (X9_TESTING_CLOCKCONTROL): the movable test clock is not available"},
		{"bool not a bool", []string{"X9_TESTING_CLOCKCONTROL=yes please"}, "is not true or false"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Load("", append(complete(), tc.env...))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to contain %q", err, tc.want)
			}
		})
	}
}

func TestPasswordFile(t *testing.T) {
	path := writeFile(t, "password", "secret\n")
	cfg, err := Load("", append(without(complete(), "DATABASE_PASSWORD"), "DATABASE_PASSWORD_FILE="+path))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Database.PasswordFile != path {
		t.Fatalf("password-file = %q", cfg.Database.PasswordFile)
	}
	_, err = Load("", append(without(complete(), "DATABASE_PASSWORD"), "DATABASE_PASSWORD_FILE=/nonexistent/password"))
	if err == nil || !strings.Contains(err.Error(), "database.password-file (DATABASE_PASSWORD_FILE): cannot be read") {
		t.Fatalf("err = %v", err)
	}
}

func TestUnknownKeyIsRefused(t *testing.T) {
	path := writeFile(t, "x9.yaml", "database:\n  hostname: db\n")
	_, err := Load(path, complete())
	if err == nil || !strings.Contains(err.Error(), "hostname") {
		t.Fatalf("err = %v, want the unknown key named", err)
	}
}

func TestEmptyFileIsAllowed(t *testing.T) {
	if _, err := Load(writeFile(t, "x9.yaml", "\n"), complete()); err != nil {
		t.Fatal(err)
	}
}

// S7.1-AC2: a Spring-style configuration is refused, naming the converter.
func TestSpringFileIsRefused(t *testing.T) {
	for _, tc := range []struct{ name, file, content, want string }{
		{"spring key", "application.yml", "spring:\n  data:\n    mongodb:\n      database: x9\n", `top-level key "spring"`},
		{"management key", "application.yml", "management:\n  endpoints: {}\n", `top-level key "management"`},
		{"x9 key", "application.yml", "x9:\n  reservation:\n    ttl-seconds: 90\n", `top-level key "x9"`},
		{"jasypt value", "secrets.yml", "database:\n  password: ENC(abcdef)\n", "Jasypt ENC(…)"},
		{"classpath", "x9.yaml", "database:\n  sslrootcert: classpath:/certificate/ca.pem\n", "classpath:"},
		{"placeholder", "x9.yaml", "database:\n  host: ${DB_HOST:localhost}\n", "placeholder"},
		{"properties file", "application.properties", "spring.application.name=x9-qrcode\n", "properties file"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Load(writeFile(t, tc.file, tc.content), complete())
			if !errors.Is(err, ErrOldConfiguration) {
				t.Fatalf("err = %v, want ErrOldConfiguration", err)
			}
			if !strings.Contains(err.Error(), Converter) || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to name %s and %q", err, Converter, tc.want)
			}
		})
	}
}

// S7.1-AC2: a Spring or MongoDB variable is refused, naming the converter.
func TestOldEnvironmentIsRefused(t *testing.T) {
	for _, name := range []string{"SPRING_CONFIG_LOCATION", "SPRING_DATA_MONGODB_HOST", "MONGODB_URI", "MONGO_INITDB_DATABASE", "JASYPT_ENCRYPTOR_PASSWORD"} {
		t.Run(name, func(t *testing.T) {
			_, err := Load("", append(complete(), name+"=x"))
			if !errors.Is(err, ErrOldConfiguration) || !strings.Contains(err.Error(), name) || !strings.Contains(err.Error(), Converter) {
				t.Fatalf("err = %v, want ErrOldConfiguration naming %s and %s", err, name, Converter)
			}
		})
	}
}

// The example file documents every key and variable, and loads.
func TestExampleFileIsCompleteAndLoads(t *testing.T) {
	const example = "../../cmd/x9-qrcode/config.example.yaml"
	data, err := os.ReadFile(example)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range envVars {
		if !strings.Contains(string(data), e.name) {
			t.Errorf("the example does not document %s (%s)", e.name, e.key)
		}
	}
	env := []string{
		"DATABASE_PASSWORD_FILE=" + writeFile(t, "password", "pw"),
		"DATABASE_SSLROOTCERT=" + writeFile(t, "ca.pem", "-----BEGIN CERTIFICATE-----"),
	}
	cfg, err := Load(example, env)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Database.SSLMode != "verify-full" || cfg.Database.Host != "postgres.example.com" {
		t.Fatalf("unexpected: %+v", cfg.Database)
	}
}

func TestPasswordIsNeverLogged(t *testing.T) {
	cfg, err := Load("", complete())
	if err != nil {
		t.Fatal(err)
	}
	if v := cfg.Database.LogValue().String(); strings.Contains(v, "secret") {
		t.Fatalf("the password reached the log value: %s", v)
	}
}
