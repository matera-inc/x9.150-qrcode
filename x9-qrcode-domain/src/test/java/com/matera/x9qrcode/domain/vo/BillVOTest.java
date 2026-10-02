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

    /**
     * <b>X9-AMT-030</b> — a bill is created from its required values.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13 — description, paymentTiming and amountDue. Conformance.
     *
     * <p><b>Why:</b> The acceptance case for the bill.
     */
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

    /**
     * <b>X9-AMT-031</b> — a bill accepts its optional blocks.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.3, §13.4, §13.6 — order, invoice and tip are optional. Conformance.
     *
     * <p><b>Why:</b> An optional block that cannot actually be supplied is not optional. Three of the four tip
     * forms were unreachable for a whole release precisely because nothing exercised them.
     */
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

    /**
     * <b>X9-AMT-032</b> — a bill cannot exist without a description.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.1 — required in BillBase. Conformance.
     *
     * <p><b>Why:</b> The description is what the payer reads to recognise what they are paying for.
     */
    @Test
    void shouldThrowExceptionWhenDescriptionIsNull() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(null, BILL_FIXTURE.order(), BILL_FIXTURE.invoice(), BILL_FIXTURE.tip(), BILL_FIXTURE.amountDue(),
                BILL_FIXTURE.deferredPaymentTiming()));

        assertEquals("Bill description must not be null.", exception.getMessage());
    }

    /**
     * <b>X9-AMT-033</b> — a bill cannot exist without an amount due.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5 — required. Conformance.
     *
     * <p><b>Why:</b> A bill with no amount asks for nothing.
     */
    @Test
    void shouldThrowExceptionWhenAmountDueIsNull() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(BILL_FIXTURE.description(), BILL_FIXTURE.order(), BILL_FIXTURE.invoice(), BILL_FIXTURE.tip(), null,
                BILL_FIXTURE.deferredPaymentTiming()));

        assertEquals("Bill amount due must not be null.", exception.getMessage());
    }

    /**
     * <b>X9-AMT-034</b> — a bill cannot exist without a payment timing.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.2 — required, with a default of immediate. Conformance.
     *
     * <p><b>Why:</b> Deferred and immediate bills are treated differently downstream, so the value may be defaulted
     * at the edge but never left absent in the domain.
     */
    @Test
    void shouldThrowExceptionWhenPaymentTimingIsNull() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(BILL_FIXTURE.description(), BILL_FIXTURE.order(), BILL_FIXTURE.invoice(), BILL_FIXTURE.tip(),
                BILL_FIXTURE.amountDue(), null));

        assertEquals("Bill payment timing must not be null.", exception.getMessage());
    }

    /**
     * <b>X9-AMT-035</b> — a deferred bill must carry an invoice.
     *
     * <p><b>Source:</b> Ours. The standard makes invoice optional; requiring it for DEFERRED is this implementation's
     * rule, because the due date lives there.
     *
     * <p><b>Why:</b> Deferred means "payable later", and later is defined by invoice.dueDate. Without it, a
     * deferred bill has no deadline — and the late-fee and discount formulas have nothing to
     * measure from.
     */
    @Test
    void shouldThrowExceptionWhenInvoiceIsNullAndPaymentTimingIsDeferred() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(BILL_FIXTURE.description(), BILL_FIXTURE.order(), null, BILL_FIXTURE.tip(),
                BILL_FIXTURE.amountDueWithoutAdjustment(), PaymentTimingEnum.DEFERRED));

        assertEquals("Bill invoice must be informed when DEFERRED payment timing.", exception.getMessage());
    }

    /**
     * <b>X9-AMT-036</b> — an adjusted bill must carry an invoice.
     *
     * <p><b>Source:</b> Ours, for the same reason as AMT-035.
     *
     * <p><b>Why:</b> Discounts and late fees are computed relative to the due date. An adjustment with no date to
     * measure against would silently resolve to a constant, which is not what the biller asked for.
     */
    @Test
    void shouldThrowExceptionWhenInvoiceIsNullAndAmountDueHasAdjustment() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(BILL_FIXTURE.description(), BILL_FIXTURE.order(), null, BILL_FIXTURE.tip(), BILL_FIXTURE.amountDue(),
                BILL_FIXTURE.deferredPaymentTiming()));

        assertEquals("Bill amount due adjustment can only be informed when invoice is not null.", exception.getMessage());
    }

    /**
     * <b>X9-AMT-037</b> — a discount cannot equal or exceed the amount due.
     *
     * <p><b>Source:</b> Ours. The standard does not bound a discount.
     *
     * <p><b>Why:</b> A discount of the whole bill produces an amount due of zero or less — a QR Code asking for
     * nothing, or for a negative sum. Caught where the bill is built rather than at payment time,
     * because by then the QR Code has been printed.
     */
    @Test
    void shouldThrowExceptionWhenAdjustmentDiscountIsGreaterThanOrEqualToAmountDue() {
        Exception exception = assertThrowsExactly(ValueObjectRuleException.class,
            () -> new BillVO(BILL_FIXTURE.description(), BILL_FIXTURE.order(), BILL_FIXTURE.invoice(), BILL_FIXTURE.tip(),
                BILL_FIXTURE.amountDueWithHighAdjustment(), BILL_FIXTURE.deferredPaymentTiming()));

        assertEquals("Bill discount at index 0. Must not be greater than or equal to amount due.", exception.getMessage());
    }

}