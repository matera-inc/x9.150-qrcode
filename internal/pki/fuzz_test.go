// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package pki

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// S8.3-AC3: a jku or x5u URL is unauthenticated input. Whatever it is, it never panics, never
// carries a credential, and never leaves the deployment in the clear.
func FuzzFetchTarget(f *testing.F) {
	for _, seed := range []string{
		"https://payer.example/chain.pem", "http://payer.example/chain.pem", "payer.example/x.pem",
		"http://x9-150.example.com/x9.pem", "https://user:pw@payer.example/", "ftp://payer.example/",
		"http://evil.example/?h=x9-150.example.com", "//payer.example", "http://[::1]:80/", "%zz",
	} {
		f.Add(seed)
	}
	fetcher, err := NewFetcher(FetcherConfig{Clock: newTestClock(fixedNow), OwnHost: "x9-150.example.com"})
	if err != nil {
		f.Fatal(err)
	}
	f.Fuzz(func(t *testing.T, raw string) {
		u, err := fetcher.target(raw)
		if err != nil {
			return
		}
		if u.User != nil {
			t.Fatalf("%q keeps a credential", raw)
		}
		if u.Scheme != "https" && !strings.EqualFold(u.Host, "x9-150.example.com") {
			t.Fatalf("%q is fetched in the clear from %s", raw, u.Host)
		}
	})
}

// S8.3-AC3: an x5u document is unauthenticated input; parsing it never panics.
func FuzzParseChain(f *testing.F) {
	for _, name := range []string{"signer-rsa.pem", "signer-ec.pem", "x9-test-ca.pem"} {
		if b, err := os.ReadFile(filepath.Clean(filepath.Join(goldenDir, "pki", name))); err == nil {
			f.Add(b)
		}
	}
	f.Add([]byte("-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n"))
	f.Fuzz(func(_ *testing.T, body []byte) {
		_, _ = parseChain(body)
	})
}

// S8.3-AC2: a fetched CRL is read before its signature is checked; parsing it never panics.
func FuzzParseCRL(f *testing.F) {
	for _, name := range []string{"test-ca.crl", "test-ca-revoked.crl"} {
		if b, err := os.ReadFile(filepath.Clean(filepath.Join(goldenDir, "pki", name))); err == nil {
			f.Add(b)
		}
	}
	f.Add([]byte("-----BEGIN X509 CRL-----\n-----END X509 CRL-----\n"))
	f.Fuzz(func(_ *testing.T, body []byte) {
		_, _ = parseCRL(body)
	})
}
