/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import lombok.EqualsAndHashCode;

import java.math.BigInteger;

import static java.util.Objects.isNull;

public class AmountVO extends ValueObject<Long> {

    /**
     * The largest magnitude an amount may have: 18 digits. ANSI X9.150-2026 gives every amount field
     * a maximum length of 18 (§13.5.1, §13.5.3.2, §14.3, §14.4.1, §2.1, §2.2), one digit fewer than
     * a 64-bit integer holds. See ADR-0005.
     */
    public static final long MAX_AMOUNT = 999_999_999_999_999_999L;

    private static final BigInteger MAX = BigInteger.valueOf(MAX_AMOUNT);

    static final String TOO_MANY_DIGITS = "monetary values should not exceed 18 digits (" + MAX_AMOUNT + ").";

    public AmountVO(Long value) {
        if (isNull(value)) {
            throw new ValueObjectRuleException("monetary values should not be null.");
        }

        if (value < 0) {
            throw new ValueObjectRuleException("monetary values should not be negative.");
        }

        requireAtMost18Digits(value);

        this.value = value;
    }

    /**
     * Refuses a magnitude of more than 18 digits. Signed, because the adjustment amount (§13.5.3.2)
     * may be negative: the bound is on the digits, not on the sign.
     */
    public static long requireAtMost18Digits(long value) {
        if (value > MAX_AMOUNT || value < -MAX_AMOUNT) {
            throw new ValueObjectRuleException(TOO_MANY_DIGITS);
        }

        return value;
    }

    /**
     * The same bound on a value computed without overflow (a late fee, an amount with its fee),
     * returned as a {@code long} only once it is known to fit 18 digits.
     */
    public static long requireAtMost18Digits(BigInteger value) {
        if (value.abs().compareTo(MAX) > 0) {
            throw new ValueObjectRuleException(TOO_MANY_DIGITS);
        }

        return value.longValueExact();
    }

    /** Whether a value computed without overflow fits 18 digits. */
    public static boolean fitsEighteenDigits(BigInteger value) {
        return value.abs().compareTo(MAX) <= 0;
    }

}
