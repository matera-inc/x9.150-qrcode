// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package keys

import (
	"io"
	"strings"
	"testing"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
)

// A set enforced-alias is accepted but never silently: one startup WARN says the Go build does not
// check it and that the keystore must hold exactly one private key.
func TestEnforcedAliasIsWarnedAbout(t *testing.T) {
	load := func(alias string) string {
		t.Helper()
		logs := quiet()
		cfg := config.Certificate{
			PrivateKeystore: config.PrivateKeystore{
				Location: testPKI + "x9-test-payer.p12", Password: javaTestPassword, EnforcedAlias: alias,
			},
			Truststore: config.Truststore{Location: goldenPKI + "x9-test-ca.pem"},
		}
		if _, err := Load(cfg, newLogger(logs)); err != nil {
			t.Fatal(err)
		}
		return logs.String()
	}
	out := load("x9-test-payer")
	if strings.Count(out, "X9_CERTIFICATE_PRIVATEKEYSTORE_ENFORCEDALIAS") != 1 || !strings.Contains(out, `"level":"WARN"`) ||
		!strings.Contains(out, "exactly one private key") || !strings.Contains(out, `"enforcedAlias":"x9-test-payer"`) {
		t.Fatalf("no single WARN about the unchecked alias:\n%s", out)
	}
	if strings.Contains(load(""), "ENFORCEDALIAS") {
		t.Fatal("a WARN about an alias that is not set")
	}
}

// Several keys in one file are refused, naming the alias setting and the one-key rule.
func TestSeveralKeysNameTheAliasSetting(t *testing.T) {
	ca := newCA(t, "Example Test Root CA")
	key, other := newECKey(t), newECKey(t)
	dir := t.TempDir()
	cfg := config.Certificate{
		PrivateKeystore: config.PrivateKeystore{
			Location:      writeFile(t, dir, "signer.pem", pkcs8PEM(t, key), pkcs8PEM(t, other), certPEM(ca.issue(t, "a.example.com", key))),
			EnforcedAlias: "a",
		},
		Truststore: config.Truststore{Location: writeFile(t, dir, "trust.pem", certPEM(ca.cert))},
	}
	_, err := Load(cfg, newLogger(io.Discard))
	if err == nil || !strings.Contains(err.Error(), "X9_CERTIFICATE_PRIVATEKEYSTORE_ENFORCEDALIAS") ||
		!strings.Contains(err.Error(), "exactly one private key per keystore file") {
		t.Fatalf("err = %v", err)
	}
}
