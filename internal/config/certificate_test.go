// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package config

import (
	"errors"
	"strings"
	"testing"
	"time"
)

// S8.2-AC1: the keystore and truststore keep the Java build's variable names (ADR-0031), and
// Spring's file: prefix keeps its meaning.
func TestCertificateVariablesAreJavas(t *testing.T) {
	ks, ts := writeFile(t, "signer.pem", "pem"), writeFile(t, "trust.pem", "pem")
	cfg, err := Load("", append(complete(),
		"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=file:"+ks,
		"X9_CERTIFICATE_PRIVATEKEYSTORE_TYPE=PKCS12",
		"X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD=store-pw",
		"X9_CERTIFICATE_PRIVATEKEYSTORE_PRIVATEKEYPASSWORD=key-pw",
		"X9_CERTIFICATE_PRIVATEKEYSTORE_ALIAS=x9-private-keystore",
		"X9_CERTIFICATE_PRIVATEKEYSTORE_ENFORCEDALIAS=signer",
		"X9_CERTIFICATE_TRUSTSTORE_LOCATION=file://"+ts,
		"X9_CERTIFICATE_TRUSTSTORE_TYPE=JKS",
		"X9_CERTIFICATE_TRUSTSTORE_PASSWORD=trust-pw",
		"X9_CERTIFICATE_TRUSTSTORE_ALIAS=x9-truststore",
		"KEYSTORE_RELOAD_FREQUENCY=5000",
	))
	if err != nil {
		t.Fatal(err)
	}
	want := Certificate{
		EndpointType: "JWK_SET", JWKAlgorithm: "PS512", IssuerName: "X9",
		PrivateKeystore: PrivateKeystore{
			Location: ks, Type: "PKCS12", Password: "store-pw", PrivateKeyPassword: "key-pw",
			Alias: "x9-private-keystore", EnforcedAlias: "signer",
		},
		Truststore: Truststore{Location: ts, Type: "JKS", Password: "trust-pw", Alias: "x9-truststore"},
	}
	if cfg.Certificate != want {
		t.Errorf("certificate = %+v, want %+v", cfg.Certificate, want)
	}
	if cfg.Keystore.Reload.Frequency != 5000 {
		t.Errorf("keystore.reload.frequency = %d", cfg.Keystore.Reload.Frequency)
	}
	if v := cfg.Certificate.LogValue().String(); strings.Contains(v, "-pw") {
		t.Errorf("a keystore password reached the log value: %s", v)
	}
}

// S8.2-AC4: with nothing configured, the bundled demo identity is used (empty locations), and the
// keystore files are re-read every 30 s, as in Java.
func TestCertificateDefaults(t *testing.T) {
	cfg, err := Load("", complete())
	if err != nil {
		t.Fatal(err)
	}
	want := Certificate{EndpointType: "JWK_SET", JWKAlgorithm: "ES256", IssuerName: "X9"}
	if cfg.Certificate != want || cfg.Keystore.Reload.Frequency != 30000 {
		t.Fatalf("certificate = %+v, reload = %d", cfg.Certificate, cfg.Keystore.Reload.Frequency)
	}
}

// ADR-0040: with the algorithm unset, the bundled demo identity (EC P-256) signs ES256 and an
// operator's own keystore signs PS512, as before 3.0.1, whatever else is set; a configured
// algorithm is kept as given, with either. An empty value is unset.
func TestTheDefaultAlgorithmFollowsTheIdentity(t *testing.T) {
	own := writeFile(t, "own.p12", "p12")
	for _, tc := range []struct {
		name string
		env  []string
		want string
	}{
		{"bundled demo, unset", nil, "ES256"},
		{"bundled demo, empty", []string{"X9_CERTIFICATE_JWKALGORITHM="}, "ES256"},
		{"bundled demo, own truststore", []string{"X9_CERTIFICATE_TRUSTSTORE_LOCATION=" + own}, "ES256"},
		{"bundled demo, PS512 set", []string{"X9_CERTIFICATE_JWKALGORITHM=PS512"}, "PS512"},
		{"own keystore, unset", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=" + own}, "PS512"},
		{"own keystore, file: prefix, unset", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=file:" + own}, "PS512"},
		{"own keystore, empty", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=" + own, "X9_CERTIFICATE_JWKALGORITHM="}, "PS512"},
		{"own keystore, PS256", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=" + own, "X9_CERTIFICATE_JWKALGORITHM=PS256"}, "PS256"},
		{"own keystore, PS384", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=" + own, "X9_CERTIFICATE_JWKALGORITHM=PS384"}, "PS384"},
		{"own keystore, PS512", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=" + own, "X9_CERTIFICATE_JWKALGORITHM=PS512"}, "PS512"},
		{"own keystore, ES384", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=" + own, "X9_CERTIFICATE_JWKALGORITHM=ES384"}, "ES384"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg, err := Load("", append(complete(), tc.env...))
			if err != nil {
				t.Fatal(err)
			}
			if cfg.Certificate.JWKAlgorithm != tc.want {
				t.Errorf("jwk-algorithm = %q, want %q", cfg.Certificate.JWKAlgorithm, tc.want)
			}
		})
	}
	// The same from the file: a jwk-algorithm left out of it is unset.
	file := writeFile(t, "x9.yaml", "certificate:\n  private-keystore:\n    location: "+own+"\n")
	if cfg, err := Load(file, complete()); err != nil || cfg.Certificate.JWKAlgorithm != "PS512" {
		t.Errorf("own keystore from the file: jwk-algorithm = %q, err = %v, want PS512", cfg.Certificate.JWKAlgorithm, err)
	}
}

// S8.4-AC1: the signing settings keep the Java build's names and defaults (x9.certificate.endpoint-type
// JWK_SET, x9.certificate.jwk-algorithm PS512, x9.public-endpoints.jws-ttl-seconds 300), and a value
// the Go build cannot sign with is refused at start.
func TestSigningSettings(t *testing.T) {
	cfg, err := Load("", complete())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.PublicEndpoints.JWSTTLSeconds != 300 {
		t.Errorf("jws-ttl-seconds = %d, want 300", cfg.PublicEndpoints.JWSTTLSeconds)
	}
	cfg, err = Load("", append(complete(), "X9_CERTIFICATE_ENDPOINTTYPE=NONE", "X9_CERTIFICATE_JWKALGORITHM=ES384",
		"X9_PUBLICENDPOINTS_JWSTTLSECONDS=30"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Certificate.EndpointType != "NONE" || cfg.Certificate.JWKAlgorithm != "ES384" || cfg.PublicEndpoints.JWSTTLSeconds != 30 {
		t.Errorf("certificate = %+v, jws-ttl-seconds = %d", cfg.Certificate, cfg.PublicEndpoints.JWSTTLSeconds)
	}
	for _, tc := range []struct {
		env  string
		want string
	}{
		{"X9_CERTIFICATE_ENDPOINTTYPE=X5C", "certificate.endpoint-type (X9_CERTIFICATE_ENDPOINTTYPE): must be JWK_SET"},
		{"X9_CERTIFICATE_JWKALGORITHM=HS256", "certificate.jwk-algorithm (X9_CERTIFICATE_JWKALGORITHM): must be RS256"},
		{"X9_CERTIFICATE_JWKALGORITHM=ES256K", "certificate.jwk-algorithm (X9_CERTIFICATE_JWKALGORITHM)"},
		{"X9_CERTIFICATE_JWKALGORITHM=none", "certificate.jwk-algorithm (X9_CERTIFICATE_JWKALGORITHM)"},
		{"X9_PUBLICENDPOINTS_JWSTTLSECONDS=0", "public-endpoints.jws-ttl-seconds (X9_PUBLICENDPOINTS_JWSTTLSECONDS): must be at least 1"},
	} {
		if _, err := Load("", append(complete(), tc.env)); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: err = %v, want it to contain %q", tc.env, err, tc.want)
		}
	}
}

// S10.2-AC1: the verifier's settings keep the Java build's keys, variables and defaults:
// x9.public-endpoints.jws-clock-skew-seconds (X9_JWS_CLOCK_SKEW_SECONDS, 60) and
// x9.certificate.issuer-name (X9_CERTIFICATE_ISSUERNAME, X9). A configured 0 is no drift, not the
// verifier's default, and a negative skew is read as 0, as Java reads it.
func TestVerifierSettings(t *testing.T) {
	cfg, err := Load("", complete())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.PublicEndpoints.JWSClockSkewSeconds != 60 || cfg.PublicEndpoints.JWSClockSkew() != time.Minute {
		t.Errorf("skew = %d (%v), want 60 (1m)", cfg.PublicEndpoints.JWSClockSkewSeconds, cfg.PublicEndpoints.JWSClockSkew())
	}
	if cfg.Certificate.IssuerName != "X9" {
		t.Errorf("issuer-name = %q, want X9", cfg.Certificate.IssuerName)
	}
	for _, tc := range []struct {
		env  string
		want time.Duration
	}{
		{"X9_JWS_CLOCK_SKEW_SECONDS=5", 5 * time.Second},
		{"X9_JWS_CLOCK_SKEW_SECONDS=0", -1},
		{"X9_JWS_CLOCK_SKEW_SECONDS=-30", -1},
	} {
		cfg, err := Load("", append(complete(), tc.env))
		if err != nil {
			t.Fatalf("%s: %v", tc.env, err)
		}
		if got := cfg.PublicEndpoints.JWSClockSkew(); got != tc.want {
			t.Errorf("%s: JWSClockSkew = %v, want %v", tc.env, got, tc.want)
		}
	}
	if _, err := Load("", append(complete(), "X9_JWS_CLOCK_SKEW_SECONDS=soon")); err == nil ||
		!strings.Contains(err.Error(), "public-endpoints.jws-clock-skew-seconds (X9_JWS_CLOCK_SKEW_SECONDS)") {
		t.Errorf("err = %v, want it to name the key and its variable", err)
	}
	cfg, err = Load("", append(complete(), "X9_CERTIFICATE_ISSUERNAME="))
	if err != nil || cfg.Certificate.IssuerName != "" {
		t.Errorf("an empty issuer-name: %q, %v; want it kept empty (the check skipped, as in Java)",
			cfg.Certificate.IssuerName, err)
	}
}

// S8.2-AC1: what the Go build cannot honour is refused at start, saying what to do instead.
func TestCertificateRefusals(t *testing.T) {
	for _, tc := range []struct {
		name string
		env  []string
		want string
	}{
		{
			"classpath location", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=classpath:/certificate/x9-demo.jks"},
			`certificate.private-keystore.location (X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION): "classpath:/certificate/x9-demo.jks" is a Java classpath location`,
		},
		{
			"missing file", []string{"X9_CERTIFICATE_TRUSTSTORE_LOCATION=/nonexistent/trust.pem"},
			"certificate.truststore.location (X9_CERTIFICATE_TRUSTSTORE_LOCATION): cannot be read",
		},
		{
			"PKCS11 type", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_TYPE=PKCS11"},
			"certificate.private-keystore.type (X9_CERTIFICATE_PRIVATEKEYSTORE_TYPE): PKCS11 (an HSM) is not supported",
		},
		{
			"unknown type", []string{"X9_CERTIFICATE_TRUSTSTORE_TYPE=BKS"},
			"certificate.truststore.type (X9_CERTIFICATE_TRUSTSTORE_TYPE): must be PKCS12 or PEM",
		},
		{
			"provider", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_PROVIDER=SunPKCS11-HSM"},
			`X9_CERTIFICATE_PRIVATEKEYSTORE_PROVIDER: "SunPKCS11-HSM" selects an HSM or a security provider`,
		},
		{"HSM", []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_HSMENABLED=true"}, "X9_CERTIFICATE_PRIVATEKEYSTORE_HSMENABLED"},
		{"load strategy", []string{"X9_CERTIFICATE_TRUSTSTORE_LOADSTRATEGY=DO_NOT_LOAD"}, "X9_CERTIFICATE_TRUSTSTORE_LOADSTRATEGY"},
		{
			"no reload interval", []string{"KEYSTORE_RELOAD_FREQUENCY=0"},
			"keystore.reload.frequency (KEYSTORE_RELOAD_FREQUENCY): must be at least 1",
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Load("", append(complete(), tc.env...))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to contain %q", err, tc.want)
			}
		})
	}
	// Java's own defaults for the HSM settings are harmless, and accepted.
	if _, err := Load("", append(complete(), "X9_CERTIFICATE_PRIVATEKEYSTORE_HSMENABLED=false",
		"X9_CERTIFICATE_PRIVATEKEYSTORE_LOADSTRATEGY=LOAD_INPUT_STREAM")); err != nil {
		t.Fatal(err)
	}
}

// S8.2-AC2, S13.2-AC2: an ENC(…) value is refused at start, naming the mapping document; Go never
// decrypts Jasypt.
func TestEncryptedValuesAreRefused(t *testing.T) {
	for _, name := range []string{"X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD", "X9_CERTIFICATE_TRUSTSTORE_PASSWORD", "DATABASE_PASSWORD"} {
		t.Run(name, func(t *testing.T) {
			_, err := Load("", append(without(complete(), name), name+"=ENC(n3SxV0l1E2fZ)"))
			if !errors.Is(err, ErrOldConfiguration) {
				t.Fatalf("err = %v, want ErrOldConfiguration", err)
			}
			for _, w := range []string{name, "Jasypt", MappingDoc, "mounted file"} {
				if !strings.Contains(err.Error(), w) {
					t.Errorf("the refusal does not name %q: %v", w, err)
				}
			}
		})
	}
	path := writeFile(t, "x9.yaml", "certificate:\n  private-keystore:\n    password: ENC(n3SxV0l1E2fZ)\n")
	if _, err := Load(path, complete()); !errors.Is(err, ErrOldConfiguration) || !strings.Contains(err.Error(), MappingDoc) {
		t.Fatalf("an ENC(…) value in the file: err = %v", err)
	}
}
