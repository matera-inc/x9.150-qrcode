// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"bytes"
	_ "embed" // the bundled currency files
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"slices"
	"strings"
)

// The bundled files: the Java build's, byte for byte (a test compares them).
var (
	//go:embed supported-currencies.json
	bundledSupported []byte
	//go:embed pegged-currencies.json
	bundledPegged []byte
)

// Currencies is the deployment's currency configuration (INTERPRETATION I-3): which currencies it
// accepts at all (supported-currencies.json) and which may appear together on one request
// (pegged-currencies.json). The zero value accepts every currency alone and no mix.
type Currencies struct {
	// supported maps a lower-cased code to the one spelling this deployment accepts and emits.
	supported map[string]string
	// spellings are supported's values, in the file's order, for the refusal's message.
	spellings []string
	// groups are the peg groups, upper-cased.
	groups []map[string]bool
}

// supportedFile and peggedFile are the files' shapes. Any other key, _comment among them, is
// ignored, as Java's loader ignores it.
type supportedFile struct {
	Supported []string `json:"supported"`
}

type peggedFile struct {
	Groups [][]string `json:"groups"`
}

// LoadCurrencies reads the two files: the bundled ones for an empty location, else the file at
// that path. These are the locations Java's x9.currency.supported-currencies-location and
// x9.currency.pegged-currencies-location name. Each is strict JSON: an object holding its array
// and, optionally, a _comment the loader ignores. A missing or null array leaves its list empty: an
// empty allow list disables the supported-currency check; no groups means no two currencies mix.
func LoadCurrencies(supportedLocation, peggedLocation string) (*Currencies, error) {
	supportedJSON, err := readLocation(supportedLocation, bundledSupported)
	if err != nil {
		return nil, fmt.Errorf("loading the supported currencies: %w", err)
	}
	peggedJSON, err := readLocation(peggedLocation, bundledPegged)
	if err != nil {
		return nil, fmt.Errorf("loading the pegged currency groups: %w", err)
	}
	var s supportedFile
	if err := decodeStrict(supportedJSON, &s); err != nil {
		return nil, fmt.Errorf("loading the supported currencies from %s: %w", describe(supportedLocation), err)
	}
	var p peggedFile
	if err := decodeStrict(peggedJSON, &p); err != nil {
		return nil, fmt.Errorf("loading the pegged currency groups from %s: %w", describe(peggedLocation), err)
	}
	return NewCurrencies(s.Supported, p.Groups), nil
}

// BundledCurrencies is the configuration shipped with the service: USD, USDC and FRNT, all in the
// dollar peg group.
func BundledCurrencies() *Currencies {
	c, err := LoadCurrencies("", "")
	if err != nil {
		// The bundled files are part of the binary and a test parses them.
		panic(err)
	}
	return c
}

func readLocation(location string, bundled []byte) ([]byte, error) {
	if location == "" {
		return bundled, nil
	}
	data, err := os.ReadFile(location) //nolint:gosec // G304: the path is the operator's own configuration.
	if err != nil {
		return nil, err
	}
	return data, nil
}

func describe(location string) string {
	if location == "" {
		return "the bundled file"
	}
	return location
}

// decodeStrict decodes one JSON value and refuses anything after it.
func decodeStrict(data []byte, v any) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	if err := dec.Decode(v); err != nil {
		return err
	}
	if dec.More() {
		return errors.New("unexpected data after the JSON object")
	}
	return nil
}

// NewCurrencies builds the configuration from its two lists, normalised as Java normalises them:
// blank entries dropped, codes trimmed; the first of two spellings of one code is the one
// accepted; peg groups compared in upper case, empty groups dropped.
func NewCurrencies(supported []string, groups [][]string) *Currencies {
	c := &Currencies{supported: map[string]string{}}
	for _, code := range supported {
		if isBlank(code) {
			continue
		}
		code = javaTrim(code)
		key := strings.ToLower(code)
		if _, seen := c.supported[key]; seen {
			continue
		}
		c.supported[key] = code
		c.spellings = append(c.spellings, code)
	}
	for _, group := range groups {
		g := map[string]bool{}
		for _, code := range group {
			if !isBlank(code) {
				g[strings.ToUpper(javaTrim(code))] = true
			}
		}
		if len(g) > 0 {
			c.groups = append(c.groups, g)
		}
	}
	return c
}

// Supported is the allow list, in the file's order. Empty means every currency is accepted.
func (c *Currencies) Supported() []string {
	return append([]string(nil), c.spellings...)
}

// CheckSupported refuses a currency this deployment does not settle, or one written in another
// case than the allow list's (I-1, I-3), as Java's AllowListSupportedCurrencyPolicy does: blank
// entries are skipped; a wrong spelling is refused as soon as it is met; otherwise every unsupported
// currency is named, once each, in the order met. An empty allow list accepts everything.
func (c *Currencies) CheckSupported(currencies []string) error {
	if len(c.supported) == 0 {
		return nil
	}
	var unsupported []string
	for _, code := range currencies {
		if isBlank(code) {
			continue
		}
		sent := javaTrim(code)
		expected, ok := c.supported[strings.ToLower(sent)]
		if !ok {
			if !slices.Contains(unsupported, sent) {
				unsupported = append(unsupported, sent)
			}
			continue
		}
		if expected != sent {
			return rule("currency", fmt.Sprintf(
				"Currency \"%s\" must be written as \"%s\". This service accepts one spelling on its own API — the one it emits.",
				sent, expected))
		}
	}
	if len(unsupported) == 0 {
		return nil
	}
	return rule("currency", fmt.Sprintf(
		"Currency %s is not settled by any payment rail this deployment supports. Supported: %s.",
		strings.Join(unsupported, ", "), strings.Join(c.spellings, ", ")))
}

// CheckPegged refuses currencies that may not appear together, as Java's PeggedCurrencyMixPolicy
// does: compared in upper case, one distinct currency is always acceptable, and two or more must
// all belong to one peg group.
func (c *Currencies) CheckPegged(currencies []string) error {
	var distinct, originals []string
	for _, code := range currencies {
		if isBlank(code) {
			continue
		}
		trimmed := javaTrim(code)
		upper := strings.ToUpper(trimmed)
		if !slices.Contains(distinct, upper) {
			distinct = append(distinct, upper)
			originals = append(originals, trimmed)
		}
	}
	if len(distinct) <= 1 {
		return nil
	}
	for _, g := range c.groups {
		all := true
		for _, code := range distinct {
			if !g[code] {
				all = false
				break
			}
		}
		if all {
			return nil
		}
	}
	return rule("currency", fmt.Sprintf(
		"Currencies [%s] cannot be combined on the same request; currencies mixed on one request must all belong to the same pegged group, or the request must use a single currency",
		strings.Join(originals, ", ")))
}

// javaTrim is Java's String.trim: every leading and trailing character up to U+0020.
func javaTrim(s string) string {
	return strings.TrimFunc(s, func(r rune) bool { return r <= ' ' })
}

// isBlank is Java's String.isBlank.
func isBlank(s string) bool {
	return strings.TrimSpace(s) == ""
}
