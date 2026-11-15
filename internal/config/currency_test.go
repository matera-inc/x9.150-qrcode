// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package config

import (
	"strings"
	"testing"
)

// S9.4-AC4: the currency files' locations are Java's keys under ADR-0031's naming; unset, the
// bundled files are used.
func TestCurrencyLocations(t *testing.T) {
	cfg, err := Load("", complete())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Currency != (Currency{}) {
		t.Errorf("default %+v, want the bundled files (empty locations)", cfg.Currency)
	}

	supported := writeFile(t, "supported.json", `{"_comment": "x", "supported": ["USD"]}`)
	pegged := writeFile(t, "pegged.json", `{"groups": [["USD", "USDC"]]}`)
	cfg, err = Load("", append(complete(),
		"X9_CURRENCY_SUPPORTEDCURRENCIESLOCATION=file:"+supported,
		"X9_CURRENCY_PEGGEDCURRENCIESLOCATION="+pegged))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Currency.SupportedCurrenciesLocation != supported || cfg.Currency.PeggedCurrenciesLocation != pegged {
		t.Errorf("got %+v; want the paths, file: dropped", cfg.Currency)
	}

	file := writeFile(t, "x9.yaml", "currency:\n  supported-currencies-location: "+supported+"\n")
	if cfg, err = Load(file, complete()); err != nil || cfg.Currency.SupportedCurrenciesLocation != supported {
		t.Errorf("from the file: %+v, %v", cfg.Currency, err)
	}
}

// S9.4-AC4: a location Go cannot read, or a file that is not the strict JSON Java reads, stops the
// service at start, naming the key.
func TestCurrencyLocationsAreCheckedAtStart(t *testing.T) {
	cases := []struct {
		name string
		env  string
		want string
	}{
		{"a classpath: location", "X9_CURRENCY_SUPPORTEDCURRENCIESLOCATION=classpath:supported-currencies.json",
			"currency.supported-currencies-location (X9_CURRENCY_SUPPORTEDCURRENCIESLOCATION): must be a path"},
		{"a missing file", "X9_CURRENCY_PEGGEDCURRENCIESLOCATION=/nonexistent/pegged.json",
			"currency: loading the pegged currency groups"},
		{"a broken file", "X9_CURRENCY_SUPPORTEDCURRENCIESLOCATION=" + writeFile(t, "bad.json", "{\"supported\": [\"USD\",]}"),
			"currency: loading the supported currencies from"},
	}
	for _, c := range cases {
		_, err := Load("", append(complete(), c.env))
		if err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: %v, want an error containing %q", c.name, err, c.want)
		}
	}
}
