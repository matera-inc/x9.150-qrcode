/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Amounts carry at most 18 digits (ADR-0005).
 *
 * <p>Every amount the service accepts is built through {@link AmountVO}: the bill, each payment
 * method, the editable range, and a notification's amount and tip amount. Late fees and discounts
 * use the same bound. So the bound lives here once.
 */
class AmountVOTest {

    private static final long EIGHTEEN_NINES = 999_999_999_999_999_999L;
    private static final long NINETEEN_DIGITS = 1_000_000_000_000_000_000L;
    private static final String TOO_LONG = "monetary values should not exceed 18 digits (999999999999999999).";

    /**
     * <b>X9-AMT-110</b> — the largest 18-digit amount is accepted and kept to the unit.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5.1, §14.3 and §2.1 — the amount fields have a maximum
     * length of 18. Conformance.
     *
     * <p><b>Why:</b> The control for X9-AMT-111: without it, a bound one digit too tight would pass.
     */
    @Test
    void theLargest18DigitAmountIsAccepted() {
        assertEquals(EIGHTEEN_NINES, new AmountVO(EIGHTEEN_NINES).value());
        assertEquals(0L, new AmountVO(0L).value());
    }

    /**
     * <b>X9-AMT-111</b> — an amount of 19 digits is refused, though a 64-bit integer holds it.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5.1, §14.3, §14.4.1.1, §2.1 and §2.2 — maximum length 18.
     * Conformance. ADR-0005 recorded that Java accepted these; the owner decided on 2026-11-14 that
     * the ADR is right.
     *
     * <p><b>Why:</b> A payer built to the standard cannot hold the 19-digit amount this service would
     * otherwise put in a payload, so a QR Code that nobody can pay would be issued as valid.
     */
    @ParameterizedTest
    @ValueSource(longs = {NINETEEN_DIGITS, Long.MAX_VALUE})
    void a19DigitAmountIsRefused(long amount) {
        ValueObjectRuleException refused = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new AmountVO(amount));

        assertEquals(TOO_LONG, refused.getMessage());
    }

    /**
     * <b>X9-AMT-112</b> — a late fee or discount of 19 digits is refused, as every amount is.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5.3.2 — the adjustment amount these produce has a
     * maximum length of 18. Conformance.
     *
     * <p><b>Why:</b> The fixed fee and the per-day fee are the adjustment amounts the payload will
     * show; one that cannot be shown should not be accepted at creation.
     */
    @Test
    void a19DigitLateFeeOrDiscountIsRefused() {
        assertEquals(TOO_LONG, assertThrowsExactly(ValueObjectRuleException.class,
            () -> new LateFeesVO(NINETEEN_DIGITS, 0L, "fee")).getMessage());
        assertEquals(TOO_LONG, assertThrowsExactly(ValueObjectRuleException.class,
            () -> new LateFeesVO(0L, NINETEEN_DIGITS, "fee")).getMessage());
        assertEquals(TOO_LONG, assertThrowsExactly(ValueObjectRuleException.class,
            () -> new DiscountVO(1, NINETEEN_DIGITS, "discount")).getMessage());

        assertEquals(EIGHTEEN_NINES, new LateFeesVO(EIGHTEEN_NINES, EIGHTEEN_NINES, "fee").fixed());
    }

}
