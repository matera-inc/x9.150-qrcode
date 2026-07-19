/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

class BillVOTest extends AbstractTest {

    @Test
    void shouldSuccessfullyCreateABillWithAllRequiredValues() {
        DescriptionVO description = BILL_FIXTURE.description();
        OrderVO order = BILL_FIXTURE.order();
        InvoiceVO invoice = BILL_FIXTURE.invoice();
        TipVO tip = BILL_FIXTURE.tip();
        AmountDueVO amountDue = BILL_FIXTURE.amountDue();

        BillVO bill = assertDoesNotThrow(() -> new BillVO(description, order, invoice, tip, amountDue, BILL_FIXTURE.deferredPaymentTiming()));

        assertNotNull(bill);
        assertEquals(description, bill.description());
        assertEquals(order, bill.order());
        assertEquals(invoice, bill.invoice());
        assertEquals(tip, bill.tip());
        assertEquals(amountDue, bill.amountDue());
    }

    @Test
    void shouldSuccessfullyCreateABillWithNonRequiredValues() {
        DescriptionVO description = BILL_FIXTURE.description();
        AmountDueVO amountDue = BILL_FIXTURE.amountDueWithoutAdjustment();

        BillVO bill = assertDoesNotThrow(() -> new BillVO(description, null, null, null, amountDue, BILL_FIXTURE.immediatePaymentTiming()));

        assertNotNull(bill);
        assertEquals(description, bill.description());
        assertNull(bill.order());
        assertNull(bill.invoice());
        assertNull(bill.tip());
        assertEquals(amountDue, bill.amountDue());
    }

    @Test
    void shouldThrowExceptionWhenDescriptionIsNull() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(null, BILL_FIXTURE.order(), BILL_FIXTURE.invoice(), BILL_FIXTURE.tip(), BILL_FIXTURE.amountDue(),
                BILL_FIXTURE.deferredPaymentTiming()));

        assertEquals("Bill description must not be null.", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenAmountDueIsNull() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(BILL_FIXTURE.description(), BILL_FIXTURE.order(), BILL_FIXTURE.invoice(), BILL_FIXTURE.tip(), null,
                BILL_FIXTURE.deferredPaymentTiming()));

        assertEquals("Bill amount due must not be null.", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenPaymentTimingIsNull() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(BILL_FIXTURE.description(), BILL_FIXTURE.order(), BILL_FIXTURE.invoice(), BILL_FIXTURE.tip(),
                BILL_FIXTURE.amountDue(), null));

        assertEquals("Bill payment timing must not be null.", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenInvoiceIsNullAndPaymentTimingIsDeferred() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(BILL_FIXTURE.description(), BILL_FIXTURE.order(), null, BILL_FIXTURE.tip(),
                BILL_FIXTURE.amountDueWithoutAdjustment(), PaymentTimingEnum.DEFERRED));

        assertEquals("Bill invoice must be informed when DEFERRED payment timing.", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenInvoiceIsNullAndAmountDueHasAdjustment() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(BILL_FIXTURE.description(), BILL_FIXTURE.order(), null, BILL_FIXTURE.tip(), BILL_FIXTURE.amountDue(),
                BILL_FIXTURE.deferredPaymentTiming()));

        assertEquals("Bill amount due adjustment can only be informed when invoice is not null.", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenAdjustmentDiscountIsGreaterThanOrEqualToAmountDue() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(BILL_FIXTURE.description(), BILL_FIXTURE.order(), BILL_FIXTURE.invoice(), BILL_FIXTURE.tip(),
                BILL_FIXTURE.amountDueWithHighAdjustment(), BILL_FIXTURE.deferredPaymentTiming()));

        assertEquals("Bill discount at index 0. Must not be greater than or equal to amount due.", exception.getMessage());
    }

}