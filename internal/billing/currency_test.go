// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/google/go-cmp/cmp"
)

const javaResources = "../../x9-qrcode-infrastructure/src/main/resources/"

// S9.4-AC4: Go ships Java's two files, byte for byte.
func TestTheBundledFilesAreJavas(t *testing.T) {
	for name, bundled := range map[string][]byte{
		"supported-currencies.json": bundledSupported,
		"pegged-currencies.json":    bundledPegged,
	} {
		java, err := os.ReadFile(javaResources + name) //nolint:gosec // G304: a fixed file of this repository.
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(java, bundled) {
			t.Errorf("internal/billing/%s differs from the Java build's; copy it again", name)
		}
	}
}

// S9.4-AC4: the bundled configuration is USD, USDC and FRNT, all in the dollar group.
func TestBundledCurrencies(t *testing.T) {
	c := BundledCurrencies()
	if diff := cmp.Diff([]string{"USD", "USDC", "FRNT"}, c.Supported()); diff != "" {
		t.Errorf("supported (-want +got):\n%s", diff)
	}
	if err := c.CheckPegged([]string{"USD", "USDC", "FRNT", "USDT"}); err != nil {
		t.Errorf("the dollar group: %v", err)
	}
	if err := c.CheckPegged([]string{"BRL", "BRL1"}); err != nil {
		t.Errorf("the real group: %v", err)
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

// S9.4-AC4: a replacement is read from the location given; _comment and any other key are
// ignored; a missing or null array is an empty list.
func TestLoadCurrenciesFromLocations(t *testing.T) {
	supported := writeFile(t, "s.json", `{"_comment": ["x"], "other": 1, "supported": ["EUR", " usd ", "", "USD"]}`)
	pegged := writeFile(t, "p.json", `{"_comment": "x", "groups": [["eur", "USD"], [], null]}`)
	c, err := LoadCurrencies(supported, pegged)
	if err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff([]string{"EUR", "usd"}, c.Supported()); diff != "" {
		t.Errorf("supported: trimmed, blank dropped, the first spelling kept (-want +got):\n%s", diff)
	}
	if err := c.CheckPegged([]string{"EUR", "usd"}); err != nil {
		t.Errorf("one group, any case: %v", err)
	}

	empty := writeFile(t, "e.json", `{"supported": null}`)
	c, err = LoadCurrencies(empty, writeFile(t, "g.json", `{}`))
	if err != nil {
		t.Fatal(err)
	}
	if err := c.CheckSupported([]string{"XYZ", "usd"}); err != nil {
		t.Errorf("an empty allow list disables the check: %v", err)
	}
	if err := c.CheckPegged([]string{"USD", "USDC"}); err == nil {
		t.Error("with no groups, two currencies mixed")
	}
}

// S9.4-AC4: the files are strict JSON; a broken or missing one is an error naming it.
func TestLoadCurrenciesRefusesWhatIsNotStrictJSON(t *testing.T) {
	cases := map[string]string{
		"a comment":     "// USD only\n{\"supported\": [\"USD\"]}",
		"trailing data": `{"supported": ["USD"]} {}`,
		"not a list":    `{"supported": "USD"}`,
		"a number":      `{"supported": [840]}`,
		"truncated":     `{"supported": [`,
	}
	for name, content := range cases {
		path := writeFile(t, "bad.json", content)
		if _, err := LoadCurrencies(path, ""); err == nil || !strings.Contains(err.Error(), path) {
			t.Errorf("%s: %v, want an error naming %s", name, err, path)
		}
		path = writeFile(t, "bad.json", strings.ReplaceAll(content, `"supported"`, `"groups"`))
		if _, err := LoadCurrencies("", path); err == nil || !strings.Contains(err.Error(), path) {
			t.Errorf("%s (pegged): %v, want an error naming %s", name, err, path)
		}
	}
	missing := filepath.Join(t.TempDir(), "missing.json")
	if _, err := LoadCurrencies(missing, ""); err == nil || !strings.Contains(err.Error(), "missing.json") {
		t.Errorf("a missing file: %v", err)
	}
}

// S9.4-AC1 (I-3): a currency not on the allow list, or written in another case, with Java's
// AllowListSupportedCurrencyPolicy messages. Java's X9-CUR-420, -422.
func TestCheckSupported(t *testing.T) {
	c := BundledCurrencies()
	cases := []struct {
		name       string
		currencies []string
		want       string
	}{
		{"all supported", []string{"USD", "USDC", "FRNT"}, ""},
		{"blank skipped", []string{"USD", "", "  "}, ""},
		{"lower case", []string{"usd"},
			`currency: Currency "usd" must be written as "USD". This service accepts one spelling on its own API — the one it emits.`},
		{"mixed case", []string{"USD", "Usdc"},
			`currency: Currency "Usdc" must be written as "USDC". This service accepts one spelling on its own API — the one it emits.`},
		{"unsupported", []string{"EUR"},
			"currency: Currency EUR is not settled by any payment rail this deployment supports. Supported: USD, USDC, FRNT."},
		{"every offender, once each", []string{"USD", "BRL", "EUR", "BRL"},
			"currency: Currency BRL, EUR is not settled by any payment rail this deployment supports. Supported: USD, USDC, FRNT."},
		{"a wrong spelling outranks an unsupported one", []string{"EUR", "usd"},
			`currency: Currency "usd" must be written as "USD". This service accepts one spelling on its own API — the one it emits.`},
		{"trimmed", []string{" EUR "},
			"currency: Currency EUR is not settled by any payment rail this deployment supports. Supported: USD, USDC, FRNT."},
	}
	for _, tc := range cases {
		err := c.CheckSupported(tc.currencies)
		if tc.want == "" {
			if err != nil {
				t.Errorf("%s: %v", tc.name, err)
			}
			continue
		}
		if text, kind := violation(t, err); text != tc.want || kind != BusinessRule {
			t.Errorf("%s:\n got %q (%s)\nwant %q", tc.name, text, kind, tc.want)
		}
	}
}

// S9.4-AC1: currencies from two peg groups, with Java's PeggedCurrencyMixPolicy message.
func TestCheckPegged(t *testing.T) {
	c := BundledCurrencies()
	cases := []struct {
		name       string
		currencies []string
		want       string
	}{
		{"one currency", []string{"USD", "USD"}, ""},
		{"one currency in two cases", []string{"usd", "USD"}, ""},
		{"a lone non-pegged currency", []string{"JPY"}, ""},
		{"one group", []string{"USD", "USDC", "FRNT"}, ""},
		{"two groups", []string{"USD", "BRL"},
			"currency: Currencies [USD, BRL] cannot be combined on the same request; currencies mixed on one request must all belong to the same pegged group, or the request must use a single currency"},
		{"a non-pegged currency mixed", []string{"usdc", "JPY", "USDC"},
			"currency: Currencies [usdc, JPY] cannot be combined on the same request; currencies mixed on one request must all belong to the same pegged group, or the request must use a single currency"},
	}
	for _, tc := range cases {
		err := c.CheckPegged(tc.currencies)
		if tc.want == "" {
			if err != nil {
				t.Errorf("%s: %v", tc.name, err)
			}
			continue
		}
		if text, _ := violation(t, err); text != tc.want {
			t.Errorf("%s:\n got %q\nwant %q", tc.name, text, tc.want)
		}
	}
}
