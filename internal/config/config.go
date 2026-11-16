// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package config reads the Go service's one configuration: a YAML file of Go keys, overridden by
// the documented environment variables (ADR-0031). Every key is validated before anything starts,
// and every error names the key and its variable. A key that matters to security has no default:
// leaving it out is a refusal, never a guess.
//
// A Spring-style file or a MongoDB variable is refused outright, naming MappingDoc: the Go build
// does not read the Java build's configuration, and saying so at start is kinder than half-reading
// it. There is no converter (ADR-0038): the document maps every Java setting to its Go key.
package config

import (
	"bytes"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"go.yaml.in/yaml/v3"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/emv"
)

// MappingDoc is the document, by its path in the repository, that maps every setting of the Java
// build to this build's key and environment variable, or says why it is dropped (ADR-0038). Every
// refusal of the Java build's configuration names it.
const MappingDoc = "docs/CONFIGURATION-FROM-JAVA.md"

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
	Currency        Currency        `yaml:"currency"`
	Testing         Testing         `yaml:"testing"`
	Certificate     Certificate     `yaml:"certificate"`
	Keystore        Keystore        `yaml:"keystore"`
	Events          Events          `yaml:"events"`
	QRCode          QRCode          `yaml:"qrcode"`
	Retention       Retention       `yaml:"retention"`
}

// DefaultPublicHost is the public host when neither the file nor the environment sets one: the
// Java build's default profile's placeholder (RFC 2606), which no phone can reach. A deployment
// that payers scan points it at its own host.
const DefaultPublicHost = "x9-150.example.com"

// PublicEndpoints is where payers reach this service. Its environment variable is the Java
// build's (x9.public-endpoints.host, X9_PUBLICENDPOINTS_HOST).
type PublicEndpoints struct {
	// Host is the public host, with no scheme, written into every QR Code's location URL (EMV tag
	// 26) and into every jku or x5u this service signs. DefaultPublicHost when unset; set to empty
	// or blank, it stops the service at start, as in Java. At most 37 characters, or the URL would
	// not fit the tag (emv.MaxHostLength), and printable ASCII with no space.
	Host string `yaml:"host"`
	// JWSTTLSeconds is the ttl of every JWS this service signs, in seconds; the header carries it in
	// milliseconds. The Java build's x9.public-endpoints.jws-ttl-seconds, 300 by default.
	JWSTTLSeconds int `yaml:"jws-ttl-seconds"`
	// JWSClockSkewSeconds is how far ahead of this service's clock a JWS's iat may be before it is
	// refused FUTURE_IAT (ADR-0027 step 5). The Java build's x9.public-endpoints.jws-clock-skew-seconds
	// and its variable X9_JWS_CLOCK_SKEW_SECONDS, 60 by default. 0 allows no drift, and a negative
	// value is read as 0, as Java reads it (JWSClockSkew).
	JWSClockSkewSeconds int `yaml:"jws-clock-skew-seconds"`
}

// JWSClockSkew is the allowed drift as internal/jws's VerifierConfig.ClockSkew reads it: a positive
// duration, or a negative one for none (its zero is the 60 s default, which a configured 0 is not).
func (p PublicEndpoints) JWSClockSkew() time.Duration {
	if p.JWSClockSkewSeconds <= 0 {
		return -1
	}
	return time.Duration(p.JWSClockSkewSeconds) * time.Second
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

// Currency is where the currency configuration is read from (INTERPRETATION I-3): the Java build's
// x9.currency.supported-currencies-location and x9.currency.pegged-currencies-location. Each is a
// path (Spring's file: prefix is accepted and dropped); empty is the file bundled with the service,
// the Java build's own. Both files are read, and refused if broken, at start.
type Currency struct {
	SupportedCurrenciesLocation string `yaml:"supported-currencies-location"`
	PeggedCurrenciesLocation    string `yaml:"pegged-currencies-location"`
}

// Testing is the movable test clock (ADR-0033): off by default, and with it off nothing listens
// on ClockListen.
type Testing struct {
	// ClockControl turns /test/clock on. Start-up then refuses any signing certificate that is not
	// one bundled with this repository (internal/testclock).
	ClockControl bool `yaml:"clock-control"`
	// ClockListen is the host:port of the clock's own listener; never the API port.
	ClockListen string `yaml:"clock-listen"`
}

// Certificate is where the signing key and the truststore are read from (ADR-0027, ADR-0031). Its
// keys and variables are the Java build's x9.certificate.* ones, so a mounted keystore keeps its
// configuration. An empty location is the bundled DEMO identity (NON-PRODUCTION), as in Java.
type Certificate struct {
	// EndpointType is where a signed JWS says its certificate is published, Java's
	// x9.certificate.endpoint-type: JWK_SET (jku, the default), PEM (x5u) or NONE (the chain itself,
	// in x5c).
	EndpointType string `yaml:"endpoint-type"`
	// JWKAlgorithm is the JWS alg this service signs with, Java's x9.certificate.jwk-algorithm:
	// PS512 by default; RS256, RS384, RS512, PS256, PS384 or PS512 for an RSA key, ES256 (P-256),
	// ES384 (P-384) or ES512 (P-521) for an EC key.
	JWKAlgorithm string `yaml:"jwk-algorithm"`
	// IssuerName must appear, ignoring case, in the issuer of the trust anchor that a counterpart's
	// certificate chains to (ADR-0027 step 4): Java's x9.certificate.issuer-name, "X9" by default.
	// Set to empty, the check is skipped, as in Java.
	IssuerName      string          `yaml:"issuer-name"`
	PrivateKeystore PrivateKeystore `yaml:"private-keystore"`
	Truststore      Truststore      `yaml:"truststore"`
}

// LogValue keeps the passwords out of every log line.
func (c Certificate) LogValue() slog.Value {
	return slog.GroupValue(
		slog.String("endpointType", c.EndpointType),
		slog.String("jwkAlgorithm", c.JWKAlgorithm),
		slog.String("issuerName", c.IssuerName),
		slog.String("privateKeystore", c.PrivateKeystore.Location),
		slog.String("privateKeystoreAlias", c.PrivateKeystore.Alias),
		slog.String("truststore", c.Truststore.Location),
		slog.String("truststoreAlias", c.Truststore.Alias),
	)
}

// PrivateKeystore is the file holding the signing key and its certificate: PKCS#12 or PEM. A JKS
// file is refused when it is read, naming the conversion (ADR-0031).
type PrivateKeystore struct {
	// Location is a path; Spring's file: prefix is accepted and dropped. Empty is the bundled demo.
	Location string `yaml:"location"`
	// Type is informative, as in Java, whose default "JKS" reads PKCS#12 too: the file's content
	// decides. PKCS11 is refused: the Go build has no HSM signer yet (ADR-0027).
	Type               string `yaml:"type"`
	Password           string `yaml:"password"`
	PrivateKeyPassword string `yaml:"private-key-password"`
	// Alias names the keystore in logs, as in Java; it does not select an entry.
	Alias string `yaml:"alias"`
	// EnforcedAlias is Java's entry selector. It is accepted and not used: the PKCS#12 reader cannot
	// select an entry by name, so the file must hold exactly one private key.
	EnforcedAlias string `yaml:"enforced-alias"`
}

// Truststore is the file of trusted certificates: PKCS#12 or PEM.
type Truststore struct {
	Location string `yaml:"location"`
	Type     string `yaml:"type"`
	Password string `yaml:"password"`
	Alias    string `yaml:"alias"`
}

// Keystore is the Java build's keystore.reload.frequency: how often, in milliseconds, the keystore
// and truststore files are re-read.
type Keystore struct {
	Reload KeystoreReload `yaml:"reload"`
}

// KeystoreReload is the re-read interval.
type KeystoreReload struct {
	Frequency int `yaml:"frequency"`
}

// Events is how long the event log keeps an event (ADR-0029): payment_event rows older than
// Retention, by the business clock, are deleted by the retention job. A consumer must read its
// events within it.
type Events struct {
	Retention Duration `yaml:"retention"`
}

// QRCode is how long a QR Code is kept (ADR-0029): a qr_code row whose valid_until and revised_at
// are both older than Retention is deleted, in any status, with its audit-log and notification
// rows. An expired QR Code stays readable until then. It must not be shorter than
// events.retention.
type QRCode struct {
	Retention Duration `yaml:"retention"`
}

// Retention is the retention job's timer (ADR-0029): how long it waits, in real time, between the
// end of one run and the start of the next. A test deployment shortens it so that a moved test
// clock is acted on quickly.
type Retention struct {
	Interval Duration `yaml:"interval"`
}

// Duration is an ISO-8601 duration of days, hours, minutes and seconds (P30D, PT1H, P1DT12H,
// PT90S), as the Java build writes its durations. Years, months and weeks are refused: their
// length is not fixed.
type Duration time.Duration

// isoDuration is the subset of ISO-8601 that Duration reads.
var isoDuration = regexp.MustCompile(`^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$`)

// maxDurationDays bounds a Duration to about a century, as the test clock's offset is bounded, so
// that no cutoff arithmetic overflows.
const maxDurationDays = 36_525

// ParseDuration reads an ISO-8601 duration such as P30D or PT1H, case-insensitively.
func ParseDuration(s string) (Duration, error) {
	text := strings.ToUpper(strings.TrimSpace(s))
	m := isoDuration.FindStringSubmatch(text)
	if m == nil || text == "P" || strings.HasSuffix(text, "T") {
		return 0, fmt.Errorf("%q is not an ISO-8601 duration of days, hours, minutes and seconds, such as P30D or PT1H", s)
	}
	var total time.Duration
	for i, unit := range []time.Duration{24 * time.Hour, time.Hour, time.Minute, time.Second} {
		if m[i+1] == "" {
			continue
		}
		n, err := strconv.ParseInt(m[i+1], 10, 64)
		if err != nil || n > maxDurationDays*int64(24*time.Hour/unit) {
			return 0, fmt.Errorf("%q is longer than %d days", s, maxDurationDays)
		}
		total += time.Duration(n) * unit
	}
	if total > maxDurationDays*24*time.Hour {
		return 0, fmt.Errorf("%q is longer than %d days", s, maxDurationDays)
	}
	return Duration(total), nil
}

// String writes d as ISO-8601: P30D, PT1H, P1DT12H, PT0S.
func (d Duration) String() string {
	left := time.Duration(d)
	days := left / (24 * time.Hour)
	left -= days * 24 * time.Hour
	var b strings.Builder
	b.WriteString("P")
	if days > 0 {
		b.WriteString(strconv.FormatInt(int64(days), 10) + "D")
	}
	if left > 0 || days == 0 {
		b.WriteString("T")
		for _, u := range []struct {
			unit time.Duration
			name string
		}{{time.Hour, "H"}, {time.Minute, "M"}, {time.Second, "S"}} {
			if n := left / u.unit; n > 0 {
				b.WriteString(strconv.FormatInt(int64(n), 10) + u.name)
				left -= n * u.unit
			}
		}
		if days == 0 && time.Duration(d) < time.Second {
			b.WriteString("0S")
		}
	}
	return b.String()
}

// UnmarshalYAML reads an ISO-8601 duration from the configuration file.
func (d *Duration) UnmarshalYAML(n *yaml.Node) error {
	v, err := ParseDuration(n.Value)
	if err != nil {
		return err
	}
	*d = v
	return nil
}

// defaults are the values a key takes when neither the file nor the environment sets it. Nothing
// security-relevant is here: no database host, credential, TLS mode or certificate. The public
// host defaults to the Java build's placeholder, DefaultPublicHost.
func defaults() Config {
	return Config{
		Server:          Server{Port: 8080, ShutdownDelaySeconds: 5},
		PublicEndpoints: PublicEndpoints{Host: DefaultPublicHost, JWSTTLSeconds: 300, JWSClockSkewSeconds: 60},
		Database:        Database{Port: 5432, Schema: "x9_qrcode", PoolWrites: 8, PoolReads: 4},
		Log:             Log{Level: "info"},
		Reservation:     Reservation{TTLSeconds: 90},
		Testing:         Testing{ClockListen: "127.0.0.1:8099"},
		Certificate:     Certificate{EndpointType: "JWK_SET", JWKAlgorithm: "PS512", IssuerName: "X9"},
		Keystore:        Keystore{Reload: KeystoreReload{Frequency: 30000}},
		Events:          Events{Retention: Duration(30 * 24 * time.Hour)},
		QRCode:          QRCode{Retention: Duration(400 * 24 * time.Hour)},
		Retention:       Retention{Interval: Duration(time.Hour)},
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
	{"X9_PUBLICENDPOINTS_JWSTTLSECONDS", "public-endpoints.jws-ttl-seconds", setInt(func(c *Config) *int { return &c.PublicEndpoints.JWSTTLSeconds })},
	{"X9_JWS_CLOCK_SKEW_SECONDS", "public-endpoints.jws-clock-skew-seconds", setInt(func(c *Config) *int { return &c.PublicEndpoints.JWSClockSkewSeconds })},
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
	{"X9_CURRENCY_SUPPORTEDCURRENCIESLOCATION", "currency.supported-currencies-location", setString(func(c *Config) *string { return &c.Currency.SupportedCurrenciesLocation })},
	{"X9_CURRENCY_PEGGEDCURRENCIESLOCATION", "currency.pegged-currencies-location", setString(func(c *Config) *string { return &c.Currency.PeggedCurrenciesLocation })},
	{"X9_TESTING_CLOCKCONTROL", "testing.clock-control", setBool(func(c *Config) *bool { return &c.Testing.ClockControl })},
	{"X9_TESTING_CLOCKLISTEN", "testing.clock-listen", setString(func(c *Config) *string { return &c.Testing.ClockListen })},
	{"X9_CERTIFICATE_ENDPOINTTYPE", "certificate.endpoint-type", setString(func(c *Config) *string { return &c.Certificate.EndpointType })},
	{"X9_CERTIFICATE_JWKALGORITHM", "certificate.jwk-algorithm", setString(func(c *Config) *string { return &c.Certificate.JWKAlgorithm })},
	{"X9_CERTIFICATE_ISSUERNAME", "certificate.issuer-name", setString(func(c *Config) *string { return &c.Certificate.IssuerName })},
	{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION", "certificate.private-keystore.location", setString(func(c *Config) *string { return &c.Certificate.PrivateKeystore.Location })},
	{"X9_CERTIFICATE_PRIVATEKEYSTORE_TYPE", "certificate.private-keystore.type", setString(func(c *Config) *string { return &c.Certificate.PrivateKeystore.Type })},
	{"X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD", "certificate.private-keystore.password", setString(func(c *Config) *string { return &c.Certificate.PrivateKeystore.Password })},
	{"X9_CERTIFICATE_PRIVATEKEYSTORE_PRIVATEKEYPASSWORD", "certificate.private-keystore.private-key-password", setString(func(c *Config) *string { return &c.Certificate.PrivateKeystore.PrivateKeyPassword })},
	{"X9_CERTIFICATE_PRIVATEKEYSTORE_ALIAS", "certificate.private-keystore.alias", setString(func(c *Config) *string { return &c.Certificate.PrivateKeystore.Alias })},
	{"X9_CERTIFICATE_PRIVATEKEYSTORE_ENFORCEDALIAS", "certificate.private-keystore.enforced-alias", setString(func(c *Config) *string { return &c.Certificate.PrivateKeystore.EnforcedAlias })},
	{"X9_CERTIFICATE_TRUSTSTORE_LOCATION", "certificate.truststore.location", setString(func(c *Config) *string { return &c.Certificate.Truststore.Location })},
	{"X9_CERTIFICATE_TRUSTSTORE_TYPE", "certificate.truststore.type", setString(func(c *Config) *string { return &c.Certificate.Truststore.Type })},
	{"X9_CERTIFICATE_TRUSTSTORE_PASSWORD", "certificate.truststore.password", setString(func(c *Config) *string { return &c.Certificate.Truststore.Password })},
	{"X9_CERTIFICATE_TRUSTSTORE_ALIAS", "certificate.truststore.alias", setString(func(c *Config) *string { return &c.Certificate.Truststore.Alias })},
	{"KEYSTORE_RELOAD_FREQUENCY", "keystore.reload.frequency", setInt(func(c *Config) *int { return &c.Keystore.Reload.Frequency })},
	{"X9_EVENTS_RETENTION", "events.retention", setDuration(func(c *Config) *Duration { return &c.Events.Retention })},
	{"X9_QRCODE_RETENTION", "qrcode.retention", setDuration(func(c *Config) *Duration { return &c.QRCode.Retention })},
	{"X9_RETENTION_INTERVAL", "retention.interval", setDuration(func(c *Config) *Duration { return &c.Retention.Interval })},
}

// unsupportedKeystoreVars are the Java build's HSM and provider settings, each with the value it
// has by default there. Any other value is refused: the Go build signs only with a file-backed key
// (ADR-0027; a PKCS#11 signer is deferred until a deployment needs it).
var unsupportedKeystoreVars = []struct{ suffix, javaDefault string }{
	{"PROVIDER", ""},
	{"EXTERNALPROVIDERCLASS", ""},
	{"LOADSTRATEGY", "LOAD_INPUT_STREAM"},
	{"PKCS11_CONFIGFILEPATH", ""},
	{"PKCS11_CONFIGURABLEBASEPROVIDER", "SunPKCS11"},
	{"HSMENABLED", "false"},
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

func setDuration(field func(*Config) *Duration) func(*Config, string) error {
	return func(c *Config, v string) error {
		d, err := ParseDuration(v)
		if err != nil {
			return err
		}
		*field(c) = d
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
	if err := refuseEncryptedValues(vars); err != nil {
		return Config{}, err
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
	errs = append(errs, refuseUnsupportedKeystores(vars)...)
	cfg.Certificate.PrivateKeystore.Location = trimFileScheme(cfg.Certificate.PrivateKeystore.Location)
	cfg.Certificate.Truststore.Location = trimFileScheme(cfg.Certificate.Truststore.Location)
	cfg.Currency.SupportedCurrenciesLocation = trimFileScheme(cfg.Currency.SupportedCurrenciesLocation)
	cfg.Currency.PeggedCurrenciesLocation = trimFileScheme(cfg.Currency.PeggedCurrenciesLocation)
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
		"remove them and set this build's variables instead, as %s lists them (ADR-0031)",
		ErrOldConfiguration, strings.Join(found, ", "), MappingDoc)
}

// refuseEncryptedValues refuses a Jasypt ENC(…) value in any variable this service reads. Go never
// decrypts Jasypt: the operator passes the plain secret, from the environment or a mounted file,
// as MappingDoc shows (ADR-0027, ADR-0038).
func refuseEncryptedValues(vars map[string]string) error {
	var found []string
	for _, e := range envVars {
		if strings.Contains(vars[e.name], "ENC(") {
			found = append(found, e.name)
		}
	}
	if len(found) == 0 {
		return nil
	}
	return fmt.Errorf("%w: %s holds a Jasypt ENC(…) value, which this build never decrypts; "+
		"pass the plain secret instead, from the environment or a mounted file, as %s shows (ADR-0031)",
		ErrOldConfiguration, strings.Join(found, ", "), MappingDoc)
}

func refuseUnsupportedKeystores(vars map[string]string) []error {
	var errs []error
	for _, store := range []string{"PRIVATEKEYSTORE", "TRUSTSTORE"} {
		for _, u := range unsupportedKeystoreVars {
			name := "X9_CERTIFICATE_" + store + "_" + u.suffix
			v, ok := vars[name]
			if !ok || strings.EqualFold(strings.TrimSpace(v), u.javaDefault) {
				continue
			}
			errs = append(errs, fmt.Errorf("%s: %q selects an HSM or a security provider, which the Go build does not "+
				"support yet: it signs with a PKCS#12 or PEM file (ADR-0027); unset it, or stay on the Java build", name, v))
		}
	}
	return errs
}

// trimFileScheme drops Spring's file: prefix (file:/x, file:///x), which the Java build's
// documented values carry, so that the same variable keeps its meaning.
func trimFileScheme(location string) string {
	if rest, ok := strings.CutPrefix(location, "file://"); ok {
		return rest
	}
	if rest, ok := strings.CutPrefix(location, "file:"); ok {
		return rest
	}
	return location
}

// springKeys are top-level keys that only a Spring configuration has.
var springKeys = []string{"spring", "management", "jasypt", "info", "logging", "x9"}

func readFile(path string, cfg *Config) error {
	if ext := strings.ToLower(filepath.Ext(path)); ext == ".properties" {
		return fmt.Errorf("%w: %s is a Spring properties file; write this build's YAML instead, as %s maps "+
			"each setting (ADR-0031)", ErrOldConfiguration, path, MappingDoc)
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
	return fmt.Errorf("%w: %s is a Spring-style configuration (%s); write this build's YAML instead, "+
		"as %s maps each setting (ADR-0031)", ErrOldConfiguration, path, strings.Join(found, ", "), MappingDoc)
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

	// Unset, the host is DefaultPublicHost; set to empty or blank, it is refused, as Java refuses a
	// blank x9.public-endpoints.host at start.
	if h := c.PublicEndpoints.Host; strings.TrimSpace(h) == "" {
		errs = append(errs, problem("public-endpoints.host",
			"is set but empty: set it to this deployment's public host, or leave it unset for %s", DefaultPublicHost))
	} else if err := emv.CheckHost(h); err != nil {
		errs = append(errs, problem("public-endpoints.host", "%v", err))
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
	if c.PublicEndpoints.JWSTTLSeconds < 1 {
		errs = append(errs, problem("public-endpoints.jws-ttl-seconds", "must be at least 1, got %d", c.PublicEndpoints.JWSTTLSeconds))
	}
	errs = append(errs, c.Currency.validate()...)
	errs = append(errs, c.Certificate.validate()...)
	if c.Keystore.Reload.Frequency < 1 {
		errs = append(errs, problem("keystore.reload.frequency", "must be at least 1 (milliseconds), got %d", c.Keystore.Reload.Frequency))
	}
	if c.Testing.ClockControl {
		errs = append(errs, c.Testing.validate(c.Server.Port)...)
	}
	errs = append(errs, c.validateRetention()...)
	return errs
}

// validateRetention refuses a retention or an interval of zero, and a QR Code retention shorter
// than the events' (ADR-0029): an event may describe a QR Code already purged, but a shorter QR
// Code retention would make that the rule rather than the exception.
func (c Config) validateRetention() []error {
	var errs []error
	for _, d := range []struct {
		key   string
		value Duration
	}{
		{"events.retention", c.Events.Retention},
		{"qrcode.retention", c.QRCode.Retention},
		{"retention.interval", c.Retention.Interval},
	} {
		if time.Duration(d.value) < time.Second {
			errs = append(errs, problem(d.key, "must be at least one second, got %s", d.value))
		}
	}
	if c.QRCode.Retention < c.Events.Retention {
		errs = append(errs, problem("qrcode.retention", "%s is shorter than events.retention (%s) %s: a QR Code must be kept "+
			"at least as long as its events (ADR-0029); lengthen qrcode.retention or shorten events.retention",
			c.QRCode.Retention, envName("events.retention"), c.Events.Retention))
	}
	return errs
}

// validate refuses a classpath: location, which only the Java build can resolve, and reads both
// files, so that a missing or broken one stops the service at start, as it stops the Java build.
func (c Currency) validate() []error {
	var errs []error
	for _, l := range []struct{ key, value string }{
		{"currency.supported-currencies-location", c.SupportedCurrenciesLocation},
		{"currency.pegged-currencies-location", c.PeggedCurrenciesLocation},
	} {
		if strings.HasPrefix(l.value, "classpath:") {
			errs = append(errs, problem(l.key, "must be a path; leave it empty for the bundled file, got %q", l.value))
		}
	}
	if len(errs) > 0 || (c.SupportedCurrenciesLocation == "" && c.PeggedCurrenciesLocation == "") {
		return errs
	}
	if _, err := billing.LoadCurrencies(c.SupportedCurrenciesLocation, c.PeggedCurrenciesLocation); err != nil {
		errs = append(errs, fmt.Errorf("currency: %w", err))
	}
	return errs
}

// validate checks the clock listener's address, only when the clock is on, as the Java build does:
// host:port, with a host, and never the API port (ADR-0033).
func (t Testing) validate(apiPort int) []error {
	host, portText, err := net.SplitHostPort(t.ClockListen)
	port, portErr := strconv.Atoi(portText)
	if err != nil || host == "" || portErr != nil || port < 0 || port > 65535 {
		return []error{problem("testing.clock-listen", "must be host:port, e.g. 127.0.0.1:8099; got %q", t.ClockListen)}
	}
	if port != 0 && port == apiPort {
		return []error{problem("testing.clock-listen",
			"%q must not be the API port %d: /test/clock is never served on the API port", t.ClockListen, apiPort)}
	}
	return nil
}

var keystoreTypes = map[string]bool{"": true, "JKS": true, "PKCS12": true, "PEM": true}

// endpointTypes are the values of Java's CertificateEndpointTypeEnum.
var endpointTypes = map[string]bool{"JWK_SET": true, "PEM": true, "NONE": true}

// jwkAlgorithms are the algorithms the Go build signs with (ADR-0027): RS*, PS* and ES*.
var jwkAlgorithms = map[string]bool{
	"RS256": true, "RS384": true, "RS512": true,
	"PS256": true, "PS384": true, "PS512": true,
	"ES256": true, "ES384": true, "ES512": true,
}

func (c Certificate) validate() []error {
	var errs []error
	if !endpointTypes[c.EndpointType] {
		errs = append(errs, problem("certificate.endpoint-type", "must be JWK_SET (jku), PEM (x5u) or NONE (x5c); got %q", c.EndpointType))
	}
	if !jwkAlgorithms[c.JWKAlgorithm] {
		errs = append(errs, problem("certificate.jwk-algorithm",
			"must be RS256, RS384, RS512, PS256, PS384 or PS512 (an RSA key), or ES256, ES384 or ES512 (an EC key); got %q",
			c.JWKAlgorithm))
	}
	store := func(prefix, location, typ string) {
		switch t := strings.ToUpper(strings.TrimSpace(typ)); {
		case t == "PKCS11":
			errs = append(errs, problem(prefix+".type", "PKCS11 (an HSM) is not supported by the Go build yet (ADR-0027); "+
				"use a PKCS#12 or PEM file, or stay on the Java build"))
		case !keystoreTypes[t]:
			errs = append(errs, problem(prefix+".type", "must be PKCS12 or PEM (JKS is accepted as a name, as in Java, "+
				"but a JKS file is refused); got %q", typ))
		}
		switch {
		case location == "":
		case strings.HasPrefix(location, "classpath:"):
			errs = append(errs, problem(prefix+".location", "%q is a Java classpath location; leave it empty for the "+
				"bundled demo identity, or mount the file and give its path (%s maps each setting, ADR-0031)", location, MappingDoc))
		default:
			if _, err := os.Stat(location); err != nil {
				errs = append(errs, problem(prefix+".location", "cannot be read: %v", err))
			}
		}
	}
	store("certificate.private-keystore", c.PrivateKeystore.Location, c.PrivateKeystore.Type)
	store("certificate.truststore", c.Truststore.Location, c.Truststore.Type)
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
