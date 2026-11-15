// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"math"
	"math/big"
	"testing"
)

// S9.4-AC2 (ADR-0005): every amount is at most 18 nines and not negative; the signed adjustment
// amount is bounded on both sides. Java's X9-AMT-110 to -112.
func TestCheckAmount(t *testing.T) {
	cases := []struct {
		amount       int64
		amountErr    string
		signedAmount string
	}{
		{0, "", ""},
		{1, "", ""},
		{MaxAmount, "", ""},
		{MaxAmount + 1, msgTooManyDigits, msgTooManyDigits},
		{math.MaxInt64, msgTooManyDigits, msgTooManyDigits},
		{-1, msgNegative, ""},
		{-MaxAmount, msgNegative, ""},
		{-MaxAmount - 1, msgNegative, msgTooManyDigits},
		{math.MinInt64, msgNegative, msgTooManyDigits},
	}
	text := func(err error) string {
		if err == nil {
			return ""
		}
		return err.Error()
	}
	for _, c := range cases {
		if got := text(CheckAmount(c.amount)); got != c.amountErr {
			t.Errorf("CheckAmount(%d) = %q, want %q", c.amount, got, c.amountErr)
		}
		if got := text(CheckSignedAmount(c.amount)); got != c.signedAmount {
			t.Errorf("CheckSignedAmount(%d) = %q, want %q", c.amount, got, c.signedAmount)
		}
	}
	if msgTooManyDigits != "monetary values should not exceed 18 digits (999999999999999999)." {
		t.Errorf("the message is not Java's: %q", msgTooManyDigits)
	}
}

func TestRequire18(t *testing.T) {
	big18 := big.NewInt(MaxAmount)
	if v, err := require18(big18); err != nil || v != MaxAmount {
		t.Errorf("18 nines: %d, %v", v, err)
	}
	if v, err := require18(new(big.Int).Neg(big18)); err != nil || v != -MaxAmount {
		t.Errorf("minus 18 nines: %d, %v", v, err)
	}
	past := new(big.Int).Add(big18, big.NewInt(1))
	if _, err := require18(past); err == nil {
		t.Error("10^18 fits")
	}
	if _, err := require18(new(big.Int).Mul(past, past)); err == nil {
		t.Error("10^36 fits")
	}
}
