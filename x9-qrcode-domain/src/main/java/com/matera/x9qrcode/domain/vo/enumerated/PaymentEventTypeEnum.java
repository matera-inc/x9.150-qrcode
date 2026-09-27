/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo.enumerated;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

/**
 * The public event contract's type field. Additive within a major version: a consumer must ignore a
 * type it does not recognise rather than fail on it.
 */
public enum PaymentEventTypeEnum {

    /** A payment has been initiated against the QR Code; it is out of circulation. */
    PAYMENT_INITIATED("payment.initiated"),

    /**
     * A payer reported an on-chain transaction. Note this does NOT mean the QR Code is paid: X9.150
     * never touches money and cannot observe settlement, so the QR Code stays PAYMENT_INITIATED
     * until whatever system receives the funds says otherwise.
     */
    PAYMENT_SENT("payment.sent"),

    /** The payer reported the payment will not proceed. */
    PAYMENT_FAILED("payment.failed"),

    /** The QR Code is paid. Emitted only when a system that observed the funds says so. */
    PAYMENT_CLEARED("payment.cleared"),

    /** The QR Code was cancelled by the biller. */
    PAYMENT_CANCELLED("payment.cancelled");

    private final String value;

    PaymentEventTypeEnum(String value) {
        this.value = value;
    }

    public static PaymentEventTypeEnum fromValue(String value) {
        for (PaymentEventTypeEnum type : PaymentEventTypeEnum.values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }

        throw new ValueObjectRuleException("Unexpected value '" + value + "'");
    }

    public String value() {
        return value;
    }

}
