/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A bank address and a late fee are checked as written: the account number itself, and each fee
 * by its own field (owner decisions of 2026-11-15 on the Go rewrite's Story 9.4).
 */
class CheckedAsWrittenTest {

    private static final String ROUTING = "021000021";
    private static final String LATE_FEES = "bill.amountDue.adjustments.parameters.lateFees";

    /**
     * <b>X9-RAIL-082</b> — the bank address checks its account number, not its routing number
     * twice: anything that is not 4 to 17 digits or letters is refused.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 Table 2, Account Number (Bank): 4 to 17 characters, digits
     * and/or letters. INTERPRETATION I-6. Conformance.
     *
     * <p><b>Why:</b> The value object matched the account pattern against the routing number, which
     * always passes, so only the HTTP contract stood between a malformed account and a printed QR
     * Code. A path that builds the value object without the contract (a restore, a future endpoint)
     * would have carried it.
     */
    @ParameterizedTest(name = "account {0} is refused")
    @ValueSource(strings = {"123", "123456789012345678", "1234-5678", "1234,5678", "1234 5678"})
    void anAccountNumberThatIsNotFourToSeventeenDigitsOrLettersIsRefused(String account) {
        ValueObjectRuleException refused = assertThrows(ValueObjectRuleException.class,
            () -> new BankPaymentAddressVO(ROUTING, account));

        assertEquals("AccountNumber is invalid.", refused.getMessage());
    }

    /**
     * <b>X9-RAIL-083</b> — an account number of 4 to 17 digits or letters is accepted, the
     * standard's own {@code ACME00112233445} among them.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 Table 2 and its Annex A example. INTERPRETATION I-6.
     * Conformance.
     *
     * <p><b>Why:</b> The control for X9-RAIL-082: checking the right field must not refuse the
     * alphanumeric accounts the old digits-only pattern refused.
     */
    @ParameterizedTest(name = "account {0} is accepted")
    @ValueSource(strings = {"1234", "12345678901234567", "ACME00112233445", "acme0011"})
    void anAccountNumberOfFourToSeventeenDigitsOrLettersIsAccepted(String account) {
        assertDoesNotThrow(() -> new BankPaymentAddressVO(ROUTING, account));
    }

    /**
     * <b>X9-AMT-127</b> — a negative fixed late fee is refused naming
     * {@code bill.amountDue.adjustments.parameters.lateFees.fixed}: "must be greater than or equal
     * to 0".
     *
     * <p><b>Source:</b> Ours. ANSI X9.150-2026 §13.5.3 gives the fee parameters as amounts in minor
     * units; refusing a negative one, and naming it, is ours.
     *
     * <p><b>Why:</b> The fixed fee was refused without a field as a malformed value, the per-day fee
     * as a business rule without a field, and both said "greater than 0" while accepting 0. One
     * rule, one kind of refusal, the field named and the message true.
     */
    @Test
    void aNegativeFixedFeeIsRefusedNamingItsField() {
        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
            () -> new LateFeesVO(-1L, 0L, "late"));

        assertEquals(LATE_FEES + ".fixed", refused.field());
        assertEquals("must be greater than or equal to 0", refused.getMessage());
    }

    /**
     * <b>X9-AMT-128</b> — a negative per-day late fee is refused naming
     * {@code bill.amountDue.adjustments.parameters.lateFees.perDay}, the same way; zero fees are
     * accepted.
     *
     * <p><b>Source:</b> Ours, as X9-AMT-127.
     *
     * <p><b>Why:</b> As X9-AMT-127; and the zero case pins that "greater than or equal to 0" is what
     * the code does.
     */
    @Test
    void aNegativePerDayFeeIsRefusedNamingItsFieldAndZeroFeesAreAccepted() {
        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
            () -> new LateFeesVO(0L, -1L, "late"));

        assertEquals(LATE_FEES + ".perDay", refused.field());
        assertEquals("must be greater than or equal to 0", refused.getMessage());
        assertDoesNotThrow(() -> new LateFeesVO(0L, 0L, "late"));
    }

}
