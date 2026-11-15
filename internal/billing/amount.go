// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import "math/big"

// MaxAmount is the largest magnitude an amount may have: 18 digits. ANSI X9.150-2026 gives every
// amount field a maximum length of 18 (§13.5.1, §13.5.3.2, §14.3, §14.4.1, §2.1, §2.2), one digit
// fewer than an int64 holds (ADR-0005).
const MaxAmount int64 = 999_999_999_999_999_999

// The messages of Java's AmountVO.
const (
	msgTooManyDigits = "monetary values should not exceed 18 digits (999999999999999999)."
	msgNegative      = "monetary values should not be negative."
)

var maxAmount = big.NewInt(MaxAmount)

// CheckAmount refuses what Java's AmountVO refuses: a negative amount, or one past 18 digits. It is
// the check for every amount the service takes.
func CheckAmount(amount int64) error {
	if amount < 0 {
		return invalid(msgNegative)
	}
	return CheckSignedAmount(amount)
}

// CheckSignedAmount refuses a magnitude past 18 digits, either side of zero: the bound of the
// signed adjustment amount (§13.5.3.2), which may be negative.
func CheckSignedAmount(amount int64) error {
	if amount > MaxAmount || amount < -MaxAmount {
		return invalid(msgTooManyDigits)
	}
	return nil
}

// fits18 reports whether a value computed without overflow fits 18 digits.
func fits18(v *big.Int) bool {
	return new(big.Int).Abs(v).Cmp(maxAmount) <= 0
}

// require18 returns v as an int64 once it is known to fit 18 digits, or Java's refusal.
func require18(v *big.Int) (int64, error) {
	if !fits18(v) {
		return 0, invalid(msgTooManyDigits)
	}
	return v.Int64(), nil
}

// prorated is a payment method's amount with the bill's adjustment carried to it by ratio, rounded
// half up: methodAmount × adjustedBill / bill. Currencies on one QR Code share a peg, not a scale,
// so the ratio, not the difference, is what carries across (BUG-001's fix). bill must be positive
// and the other two non-negative.
func prorated(methodAmount, bill int64, adjustedBill *big.Int) *big.Int {
	num := new(big.Int).Mul(big.NewInt(methodAmount), adjustedBill)
	den := big.NewInt(bill)
	// Half up on non-negative operands: floor((2·num + den) / (2·den)).
	num.Lsh(num, 1).Add(num, den)
	den.Lsh(den, 1)
	return num.Quo(num, den)
}
