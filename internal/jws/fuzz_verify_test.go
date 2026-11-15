// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

// goldenSeeds are the golden tokens, as fuzz seeds.
func goldenSeeds(f *testing.F) []string {
	f.Helper()
	matches, err := filepath.Glob(filepath.Join(goldenDir, "jws", "*.jws"))
	if err != nil || len(matches) == 0 {
		f.Fatalf("no golden tokens: %v", err)
	}
	seeds := make([]string, 0, len(matches))
	for _, m := range matches {
		b, err := os.ReadFile(filepath.Clean(m))
		if err != nil {
			f.Fatal(err)
		}
		seeds = append(seeds, string(b))
	}
	return seeds
}

// S8.5-AC3: header parsing and the crit and profile checks take any bytes without panicking, and
// every refusal carries a code. When they accept a header, the iat they read is exactly the
// integer written, never one that went through float64.
func FuzzParseHeader(f *testing.F) {
	for _, s := range goldenSeeds(f) {
		if c, r := splitCompact(s); r == nil {
			f.Add(c.header, int64(1790812801357))
		}
	}
	f.Add([]byte(`{"alg":"PS512","crit":["correlationId","iat","ttl","statusCode"],"correlationId":"64a7948e-6827-45db-8b20-d9162bcb0bc3","iat":9007199254740993,"ttl":1,"statusCode":"200"}`), int64(9007199254740993))
	f.Add([]byte(`{"alg":"PS512","crit":null,"iat":1e3,"ttl":-1}`), int64(0))
	f.Add([]byte(`{"alg":"none"}`), int64(0))
	f.Fuzz(func(t *testing.T, header []byte, now int64) {
		h, r := parseHeader(header, []byte{1})
		if r != nil {
			if r.Problem == "" {
				t.Fatalf("a refusal without a code: %v", r)
			}
			return
		}
		p, r := checkProfile(h, now, DefaultClockSkew.Milliseconds())
		if r != nil {
			if r.Problem == "" {
				t.Fatalf("a refusal without a code: %v", r)
			}
			return
		}
		var fields map[string]json.RawMessage
		dec := json.NewDecoder(bytes.NewReader(header))
		dec.UseNumber()
		if err := dec.Decode(&fields); err != nil {
			t.Fatalf("accepted a header that is not JSON: %v", err)
		}
		var n json.Number
		if err := json.Unmarshal(fields[HeaderIssuedAt], &n); err != nil {
			t.Fatalf("accepted an iat that is not a number: %s", fields[HeaderIssuedAt])
		}
		if want, err := strconv.ParseInt(n.String(), 10, 64); err != nil || p.iat != want {
			t.Fatalf("iat %s read as %d", n, p.iat)
		}
	})
}

// S8.5-AC3: the whole verifier takes any string without panicking, and refuses with a code; no
// certificate is fetched (no fetcher), and a golden token does not verify against a truststore
// that holds none of its anchors.
func FuzzVerify(f *testing.F) {
	for _, s := range goldenSeeds(f) {
		f.Add(s)
	}
	f.Add("")
	f.Add("..")
	f.Add("a.b.")
	v := newTestVerifier(f, epoch, nil, keyFor(f, "RSA"))
	f.Fuzz(func(t *testing.T, token string) {
		got, err := v.Verify(t.Context(), token, Expect{LocationID: "01JD4ZQ7XK"})
		if err == nil {
			t.Fatalf("verified %q as signed by %s", token, got.Signer)
		}
		if ProblemOf(err) == "" {
			t.Fatalf("a refusal without a code: %v", err)
		}
	})
}
