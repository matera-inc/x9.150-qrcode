// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package config reads the Go service's one configuration: a YAML file of Go keys, overridden by
// the documented environment variables (ADR-0031). Every key is validated before anything starts,
// and every error names the key and its variable. A key that matters to security has no default:
// leaving it out is a refusal, never a guess.
//
// A Spring-style file or a MongoDB variable is refused outright, naming x9-config-convert: the Go
// build does not read the Java build's configuration, and saying so at start is kinder than
// half-reading it.
package config

import (
	"bytes"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"go.yaml.in/yaml/v3"

	"github.com/matera-inc/x9.150-qrcode/internal/emv"
)

// Converter is the tool that turns the Java build's configuration into this one (ADR-0031).
const Converter = "x9-config-convert"

// ErrOldConfiguration is returned for a Spring-style file or a MongoDB / Spring variable.
var ErrOldConfiguration = errors.New("old configuration")

// Config is the whole configuration. Its YAML keys are the Go keys; the environment variable that
// overrides each one is listed in envVars.
type Config struct {
	Server          Server          `yaml:"server"`
	PublicEndpoints PublicEndpoints `yaml:"public-endpoints"`
	Database        Database        `yaml:"database"`
	Log             Log             `yaml:"log"`
	Reservation     Reservation     `yaml:"reservation"`
	Testing         Testing         `yaml:"testing"`
}

// PublicEndpoints is where payers reach this service. Its environment variable is the Java
// build's (x9.public-endpoints.host, X9_PUBLICENDPOINTS_HOST).
type PublicEndpoints struct {
	// Host is the public host, with no scheme, written into every QR Code's location URL (EMV tag
	// 26). At most 37 characters, or the URL would not fit the tag (emv.MaxHostLength).
	Host string `yaml:"host"`
}

// Server is the HTTP listener. Health is served on the same port, as the Java build does.
type Server struct {
	Port int `yaml:"port"`
	// ShutdownDelaySeconds is how long readiness reports OUT_OF_SERVICE before the listener stops accepting,
	// so that a load balancer sees the pod leave before its connections are refused.
	ShutdownDelaySeconds int `yaml:"shutdown-delay-seconds"`
}

// Database is the PostgreSQL connection (ADR-0032).
type Database struct {
	Host string `yaml:"host"`
	Port int    `yaml:"port"`
	Name string `yaml:"name"`
	User string `yaml:"user"`
	// Password and PasswordFile are mutually exclusive. A file is re-read on every new connection,
	// so rotating the credential is a file change and a reconnect.
	Password     string `yaml:"password"`
	PasswordFile string `yaml:"password-file"`
	// Schema is the schema the application role's search_path must select (ADR-0032).
	Schema string `yaml:"schema"`
	// SSLMode has no default: an operator states it. verify-full is the recommended value.
	SSLMode     string `yaml:"sslmode"`
	SSLRootCert string `yaml:"sslrootcert"`
	PoolWrites  int    `yaml:"pool-writes"`
	PoolReads   int    `yaml:"pool-reads"`
	// QueryExecMode is pgx's default_query_exec_mode. Empty keeps pgx's default; "exec" is for a
	// PgBouncer older than 1.21 or one with max_prepared_statements=0.
	QueryExecMode string `yaml:"query-exec-mode"`
}

// LogValue keeps the password out of every log line.
func (d Database) LogValue() slog.Value {
	return slog.GroupValue(
		slog.String("host", d.Host),
		slog.Int("port", d.Port),
		slog.String("name", d.Name),
		slog.String("user", d.User),
		slog.Bool("passwordFromFile", d.PasswordFile != ""),
		slog.String("schema", d.Schema),
		slog.String("sslmode", d.SSLMode),
		slog.Int("poolWrites", d.PoolWrites),
		slog.Int("poolReads", d.PoolReads),
	)
}

// Log is the log level of the JSON log.
type Log struct {
	Level string `yaml:"level"`
}

// Reservation is how long a PAYMENT_INITIATED reservation holds (ADR-0019, ADR-0036). 0 means no
// window; a negative value is invalid.
type Reservation struct {
	TTLSeconds int `yaml:"ttl-seconds"`
}

// Testing holds the movable test clock's switch (ADR-0033). It is always off in this build: the
// listener and its certificate guard need the signing keys.
type Testing struct {
	ClockControl bool `yaml:"clock-control"`
}

// defaults are the values a key takes when neither the file nor the environment sets it. Nothing
// security-relevant is here: no host, credential, TLS mode or certificate.
func defaults() Config {
	return Config{
		Server:      Server{Port: 8080, ShutdownDelaySeconds: 5},
		Database:    Database{Port: 5432, Schema: "x9_qrcode", PoolWrites: 8, PoolReads: 4},
		Log:         Log{Level: "info"},
		Reservation: Reservation{TTLSeconds: 90},
	}
}

type envVar struct {
	name string // the environment variable
	key  string // the YAML key it overrides
	set  func(c *Config, v string) error
}

// envVars lists every environment variable this service reads, and the key each one overrides.
var envVars = []envVar{
	{"X9_SERVER_PORT", "server.port", setInt(func(c *Config) *int { return &c.Server.Port })},
	{"X9_SERVER_SHUTDOWNDELAYSECONDS", "server.shutdown-delay-seconds", setInt(func(c *Config) *int { return &c.Server.ShutdownDelaySeconds })},
	{"X9_PUBLICENDPOINTS_HOST", "public-endpoints.host", setString(func(c *Config) *string { return &c.PublicEndpoints.Host })},
	{"DATABASE_HOST", "database.host", setString(func(c *Config) *string { return &c.Database.Host })},
	{"DATABASE_PORT", "database.port", setInt(func(c *Config) *int { return &c.Database.Port })},
	{"DATABASE_NAME", "database.name", setString(func(c *Config) *string { return &c.Database.Name })},
	{"DATABASE_USER", "database.user", setString(func(c *Config) *string { return &c.Database.User })},
	{"DATABASE_PASSWORD", "database.password", setString(func(c *Config) *string { return &c.Database.Password })},
	{"DATABASE_PASSWORD_FILE", "database.password-file", setString(func(c *Config) *string { return &c.Database.PasswordFile })},
	{"DATABASE_SCHEMA", "database.schema", setString(func(c *Config) *string { return &c.Database.Schema })},
	{"DATABASE_SSLMODE", "database.sslmode", setString(func(c *Config) *string { return &c.Database.SSLMode })},
	{"DATABASE_SSLROOTCERT", "database.sslrootcert", setString(func(c *Config) *string { return &c.Database.SSLRootCert })},
	{"DATABASE_POOL_WRITES", "database.pool-writes", setInt(func(c *Config) *int { return &c.Database.PoolWrites })},
	{"DATABASE_POOL_READS", "database.pool-reads", setInt(func(c *Config) *int { return &c.Database.PoolReads })},
	{"DATABASE_QUERY_EXEC_MODE", "database.query-exec-mode", setString(func(c *Config) *string { return &c.Database.QueryExecMode })},
	{"X9_LOG_LEVEL", "log.level", setString(func(c *Config) *string { return &c.Log.Level })},
	{"X9_RESERVATION_TTL_SECONDS", "reservation.ttl-seconds", setInt(func(c *Config) *int { return &c.Reservation.TTLSeconds })},
	{"X9_TESTING_CLOCKCONTROL", "testing.clock-control", setBool(func(c *Config) *bool { return &c.Testing.ClockControl })},
}

// envName returns the variable that overrides key, for error messages.
func envName(key string) string {
	for _, e := range envVars {
		if e.key == key {
			return e.name
		}
	}
	return ""
}

func setString(field func(*Config) *string) func(*Config, string) error {
	return func(c *Config, v string) error {
		*field(c) = v
		return nil
	}
}

func setInt(field func(*Config) *int) func(*Config, string) error {
	return func(c *Config, v string) error {
		n, err := strconv.Atoi(strings.TrimSpace(v))
		if err != nil {
			return fmt.Errorf("%q is not an integer", v)
		}
		*field(c) = n
		return nil
	}
}

func setBool(field func(*Config) *bool) func(*Config, string) error {
	return func(c *Config, v string) error {
		b, err := strconv.ParseBool(strings.TrimSpace(v))
		if err != nil {
			return fmt.Errorf("%q is not true or false", v)
		}
		*field(c) = b
		return nil
	}
}

// Load reads the YAML file at path (none when path is empty), then applies the environment, then
// validates. env is the process environment in os.Environ form. Every problem found is returned
// together, so an operator fixes them in one pass.
func Load(path string, env []string) (Config, error) {
	if err := refuseOldEnvironment(env); err != nil {
		return Config{}, err
	}
	cfg := defaults()
	if path != "" {
		if err := readFile(path, &cfg); err != nil {
			return Config{}, err
		}
	}
	vars := make(map[string]string, len(env))
	for _, kv := range env {
		if name, value, ok := strings.Cut(kv, "="); ok {
			vars[name] = value
		}
	}
	var errs []error
	for _, e := range envVars {
		v, ok := vars[e.name]
		if !ok {
			continue
		}
		if err := e.set(&cfg, v); err != nil {
			errs = append(errs, fmt.Errorf("%s (%s): %w", e.key, e.name, err))
		}
	}
	errs = append(errs, cfg.validate()...)
	if len(errs) > 0 {
		return Config{}, fmt.Errorf("invalid configuration:\n%w", errors.Join(errs...))
	}
	return cfg, nil
}

// oldEnvPrefixes are the variables of the Java build that have no meaning here.
var oldEnvPrefixes = []string{"SPRING_", "MONGO", "JASYPT_"}

func refuseOldEnvironment(env []string) error {
	var found []string
	for _, kv := range env {
		name, _, _ := strings.Cut(kv, "=")
		for _, p := range oldEnvPrefixes {
			if strings.HasPrefix(name, p) {
				found = append(found, name)
			}
		}
	}
	if len(found) == 0 {
		return nil
	}
	return fmt.Errorf("%w: the environment sets %s, which the Java build read and this build does not; "+
		"convert the configuration with %s and remove them (ADR-0031)", ErrOldConfiguration, strings.Join(found, ", "), Converter)
}

// springKeys are top-level keys that only a Spring configuration has.
var springKeys = []string{"spring", "management", "jasypt", "info", "logging", "x9"}

func readFile(path string, cfg *Config) error {
	if ext := strings.ToLower(filepath.Ext(path)); ext == ".properties" {
		return fmt.Errorf("%w: %s is a Spring properties file; convert it with %s (ADR-0031)", ErrOldConfiguration, path, Converter)
	}
	data, err := os.ReadFile(path) //nolint:gosec // G304: the path is the operator's own configuration file.
	if err != nil {
		return fmt.Errorf("reading the configuration file: %w", err)
	}
	var doc yaml.Node
	if err := yaml.Unmarshal(data, &doc); err != nil {
		return fmt.Errorf("configuration file %s: %w", path, err)
	}
	if err := refuseSpringDocument(path, &doc); err != nil {
		return err
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return nil
	}
	dec := yaml.NewDecoder(bytes.NewReader(data))
	dec.KnownFields(true)
	if err := dec.Decode(cfg); err != nil {
		return fmt.Errorf("configuration file %s: %w", path, err)
	}
	return nil
}

// refuseSpringDocument looks for what only a Spring configuration contains: its top-level keys,
// Jasypt's ENC(…), classpath: locations and ${…} placeholders.
func refuseSpringDocument(path string, doc *yaml.Node) error {
	var found []string
	if len(doc.Content) == 1 && doc.Content[0].Kind == yaml.MappingNode {
		top := doc.Content[0].Content
		for i := 0; i+1 < len(top); i += 2 {
			for _, k := range springKeys {
				if top[i].Value == k {
					found = append(found, "top-level key "+strconv.Quote(k))
				}
			}
		}
	}
	walkScalars(doc, func(v string) {
		switch {
		case strings.Contains(v, "ENC("):
			found = append(found, "a Jasypt ENC(…) value")
		case strings.HasPrefix(v, "classpath:"):
			found = append(found, "a classpath: location")
		case strings.Contains(v, "${"):
			found = append(found, "a ${…} placeholder")
		}
	})
	if len(found) == 0 {
		return nil
	}
	return fmt.Errorf("%w: %s is a Spring-style configuration (%s); convert it with %s (ADR-0031)",
		ErrOldConfiguration, path, strings.Join(found, ", "), Converter)
}

func walkScalars(n *yaml.Node, visit func(string)) {
	if n.Kind == yaml.ScalarNode {
		visit(n.Value)
	}
	for _, c := range n.Content {
		walkScalars(c, visit)
	}
}

var identifier = regexp.MustCompile(`^[a-z_][a-z0-9_]*$`)

var sslModes = map[string]bool{
	"disable": true, "allow": true, "prefer": true, "require": true, "verify-ca": true, "verify-full": true,
}

var queryExecModes = map[string]bool{
	"": true, "cache_statement": true, "cache_describe": true, "describe_exec": true, "exec": true, "simple_protocol": true,
}

var logLevels = map[string]bool{"debug": true, "info": true, "warn": true, "error": true}

func problem(key, format string, args ...any) error {
	msg := fmt.Sprintf(format, args...)
	if name := envName(key); name != "" {
		return fmt.Errorf("%s (%s): %s", key, name, msg)
	}
	return fmt.Errorf("%s: %s", key, msg)
}

func (c Config) validate() []error {
	var errs []error
	required := func(key, v string) {
		if strings.TrimSpace(v) == "" {
			errs = append(errs, problem(key, "required, and has no default"))
		}
	}
	port := func(key string, v int) {
		if v < 1 || v > 65535 {
			errs = append(errs, problem(key, "must be a port between 1 and 65535, got %d", v))
		}
	}

	port("server.port", c.Server.Port)
	if c.Server.ShutdownDelaySeconds < 0 {
		errs = append(errs, problem("server.shutdown-delay-seconds", "must not be negative, got %d", c.Server.ShutdownDelaySeconds))
	}

	if h := c.PublicEndpoints.Host; h != "" {
		if err := emv.CheckHost(h); err != nil {
			errs = append(errs, problem("public-endpoints.host", "%v", err))
		}
	}

	d := c.Database
	required("database.host", d.Host)
	port("database.port", d.Port)
	required("database.name", d.Name)
	required("database.user", d.User)
	switch {
	case d.Password != "" && d.PasswordFile != "":
		errs = append(errs, problem("database.password", "set either it or database.password-file (DATABASE_PASSWORD_FILE), not both"))
	case d.Password == "" && d.PasswordFile == "":
		errs = append(errs, problem("database.password-file", "required (or database.password, DATABASE_PASSWORD), and has no default"))
	case d.PasswordFile != "":
		if _, err := os.Stat(d.PasswordFile); err != nil {
			errs = append(errs, problem("database.password-file", "cannot be read: %v", err))
		}
	}
	if !identifier.MatchString(d.Schema) {
		errs = append(errs, problem("database.schema", "must be a lowercase identifier ([a-z_][a-z0-9_]*), got %q", d.Schema))
	}
	switch {
	case d.SSLMode == "":
		errs = append(errs, problem("database.sslmode", "required, and has no default; verify-full is recommended"))
	case !sslModes[d.SSLMode]:
		errs = append(errs, problem("database.sslmode", "must be one of disable, allow, prefer, require, verify-ca, verify-full; got %q", d.SSLMode))
	case (d.SSLMode == "verify-ca" || d.SSLMode == "verify-full") && d.SSLRootCert == "":
		errs = append(errs, problem("database.sslrootcert", "required when database.sslmode is %s", d.SSLMode))
	}
	if d.SSLRootCert != "" {
		if _, err := os.Stat(d.SSLRootCert); err != nil {
			errs = append(errs, problem("database.sslrootcert", "cannot be read: %v", err))
		}
	}
	if d.PoolWrites < 1 {
		errs = append(errs, problem("database.pool-writes", "must be at least 1, got %d", d.PoolWrites))
	}
	if d.PoolReads < 1 {
		errs = append(errs, problem("database.pool-reads", "must be at least 1, got %d", d.PoolReads))
	}
	if !queryExecModes[d.QueryExecMode] {
		errs = append(errs, problem("database.query-exec-mode",
			"must be one of cache_statement, cache_describe, describe_exec, exec, simple_protocol; got %q", d.QueryExecMode))
	}

	if !logLevels[c.Log.Level] {
		errs = append(errs, problem("log.level", "must be one of debug, info, warn, error; got %q", c.Log.Level))
	}
	if c.Reservation.TTLSeconds < 0 {
		errs = append(errs, problem("reservation.ttl-seconds", "must not be negative (0 means no window), got %d", c.Reservation.TTLSeconds))
	}
	if c.Testing.ClockControl {
		errs = append(errs, problem("testing.clock-control",
			"the movable test clock is not available in this build yet: its listener and certificate guard need the signing keys (ADR-0033)"))
	}
	return errs
}

// SlogLevel is the configured log level.
func (l Log) SlogLevel() slog.Level {
	switch l.Level {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}
