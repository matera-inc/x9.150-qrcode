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
	// S8.6-AC3: the test clock is off by default; its listener's address is Java's default.
	if cfg.Testing.ClockControl || cfg.Testing.ClockListen != "127.0.0.1:8099" {
		t.Fatalf("unexpected test clock defaults: %+v", cfg.Testing)
	}
}

// S8.6-AC1: with the clock on, a valid listener of its own is accepted (port 0 picks a free one).
func TestClockListenAccepted(t *testing.T) {
	for _, listen := range []string{"127.0.0.1:8099", "[::1]:8099", "0.0.0.0:0", "localhost:9099"} {
		cfg, err := Load("", append(complete(), "X9_TESTING_CLOCKCONTROL=true", "X9_TESTING_CLOCKLISTEN="+listen))
		if err != nil {
			t.Fatalf("%s: %v", listen, err)
		}
		if !cfg.Testing.ClockControl || cfg.Testing.ClockListen != listen {
			t.Fatalf("%s: %+v", listen, cfg.Testing)
		}
	}
	// With the clock off, the address is not read, as in Java.
	if _, err := Load("", append(complete(), "X9_TESTING_CLOCKLISTEN=nonsense")); err != nil {
		t.Fatalf("an unused clock address was validated: %v", err)
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
		// S8.6-AC1: the clock listener is host:port, and never the API port.
		{"clock on the API port", []string{"X9_TESTING_CLOCKCONTROL=true", "X9_TESTING_CLOCKLISTEN=0.0.0.0:8080"},
			"testing.clock-listen (X9_TESTING_CLOCKLISTEN): \"0.0.0.0:8080\" must not be the API port 8080"},
		{"clock listen without a port", []string{"X9_TESTING_CLOCKCONTROL=true", "X9_TESTING_CLOCKLISTEN=8099"},
			"testing.clock-listen (X9_TESTING_CLOCKLISTEN): must be host:port"},
		{"clock listen without a host", []string{"X9_TESTING_CLOCKCONTROL=true", "X9_TESTING_CLOCKLISTEN=:8099"}, "must be host:port"},
		{"clock listen with an empty port", []string{"X9_TESTING_CLOCKCONTROL=true", "X9_TESTING_CLOCKLISTEN=localhost:"}, "must be host:port"},
		{"clock listen with a named port", []string{"X9_TESTING_CLOCKCONTROL=true", "X9_TESTING_CLOCKLISTEN=localhost:http"}, "must be host:port"},
		{"clock listen out of range", []string{"X9_TESTING_CLOCKCONTROL=true", "X9_TESTING_CLOCKLISTEN=localhost:70000"}, "must be host:port"},
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

// S8.1-AC4: a public host longer than 37 characters stops the service at start, naming the limit,
// as the Java build does (X9PropertiesHostLengthTest); 37 characters start. The variable is the
// Java build's.
func TestPublicHostLongerThan37IsRefused(t *testing.T) {
	host37 := strings.Repeat("a", 37-len(".example.com")) + ".example.com"
	cfg, err := Load("", append(complete(), "X9_PUBLICENDPOINTS_HOST="+host37))
	if err != nil {
		t.Fatalf("a 37-character host: %v", err)
	}
	if cfg.PublicEndpoints.Host != host37 {
		t.Fatalf("public-endpoints.host = %q", cfg.PublicEndpoints.Host)
	}
	_, err = Load("", append(complete(), "X9_PUBLICENDPOINTS_HOST=a"+host37))
	want := "public-endpoints.host (X9_PUBLICENDPOINTS_HOST): must be at most 37 characters"
	if err == nil || !strings.Contains(err.Error(), want) {
		t.Fatalf("a 38-character host: err = %v, want it to contain %q", err, want)
	}
	path := writeFile(t, "x9.yaml", "public-endpoints:\n  host: a"+host37+"\n")
	if _, err := Load(path, complete()); err == nil || !strings.Contains(err.Error(), "37") {
		t.Fatalf("a 38-character host in the file: err = %v, want the limit 37 named", err)
	}
	if _, err := Load("", append(complete(), "X9_PUBLICENDPOINTS_HOST=x9 example")); err == nil {
		t.Fatal("a host with a space started")
	}
}

// Owner, 2026-11-15: with no public host set, the host is the Java build's default profile's,
// x9-150.example.com; the file or the variable replace it.
func TestPublicHostDefaultsToJavasPlaceholder(t *testing.T) {
	cfg, err := Load("", complete())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.PublicEndpoints.Host != "x9-150.example.com" || DefaultPublicHost != "x9-150.example.com" {
		t.Fatalf("public-endpoints.host = %q, want x9-150.example.com", cfg.PublicEndpoints.Host)
	}
	path := writeFile(t, "x9.yaml", "public-endpoints:\n  host: from-file.example.com\n")
	if cfg, err = Load(path, complete()); err != nil || cfg.PublicEndpoints.Host != "from-file.example.com" {
		t.Fatalf("host from the file: %q, %v", cfg.PublicEndpoints.Host, err)
	}
	cfg, err = Load(path, append(complete(), "X9_PUBLICENDPOINTS_HOST=from-env.example.com"))
	if err != nil || cfg.PublicEndpoints.Host != "from-env.example.com" {
		t.Fatalf("host from the environment: %q, %v", cfg.PublicEndpoints.Host, err)
	}
}

// A public host set explicitly to empty or blank stops the service, naming the key, as Java's
// X9Properties refuses a blank x9.public-endpoints.host; it never falls back to the placeholder.
func TestEmptyPublicHostIsRefused(t *testing.T) {
	want := "public-endpoints.host (X9_PUBLICENDPOINTS_HOST): is set but empty"
	for _, v := range []string{"", " ", "\t"} {
		if _, err := Load("", append(complete(), "X9_PUBLICENDPOINTS_HOST="+v)); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("X9_PUBLICENDPOINTS_HOST=%q: err = %v, want it to contain %q", v, err, want)
		}
	}
	path := writeFile(t, "x9.yaml", "public-endpoints:\n  host: \"\"\n")
	if _, err := Load(path, complete()); err == nil || !strings.Contains(err.Error(), want) {
		t.Errorf("an empty host in the file: err = %v, want it to contain %q", err, want)
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
