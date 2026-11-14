/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.vo.enumerated.ActionEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationPhaseEnum;
import org.apache.commons.lang3.StringUtils;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public record PaymentNotificationDataVO(
    PaymentNotificationPaymentVO payment,
    PaymentNotificationPayerVO payer,
    ExpectedDateVO expectedDate,
    BlockchainVO blockchain
) {

    public PaymentNotificationDataVO {
        if (isNull(payment)) {
            throw new ValueObjectRuleException("PaymentNotification Payment must not be null");
        }

        if (payment.interpretedRail().filter(NetworkEnum.ACH::equals).isPresent() && isNull(expectedDate)) {
            throw new ValueObjectRuleException("PaymentNotification ExpectedDate must not be null when network is ACH");
        }
    }

    /**
     * Pre-commit or post-commit, decided from the evidence on every rail (ADR-0004, ADR-0036).
     *
     * <p>A transaction id exists only once the transaction is committed, so {@code transactionId}
     * present means post-commit, Solana included. {@code blockchain.action} does not decide the
     * phase: a {@code PAYMENT_INITIATED} carrying a hash is a post-commit, because the money has
     * moved whatever the payer called its message. The one thing the action decides on its own is
     * {@code NOT_SENT}, the payer giving the QR Code back, which by its nature has no transaction.
     */
    public NotificationPhaseEnum phase() {
        if (nonNull(blockchain) && ActionEnum.NOT_SENT.equals(blockchain.action())) {
            return NotificationPhaseEnum.RELEASE;
        }

        return hasTransactionId() ? NotificationPhaseEnum.POST_COMMIT : NotificationPhaseEnum.PRE_COMMIT;
    }

    /** Whether the payer reported a transaction id (End-to-End ID, Trace Number, txHash). */
    public boolean hasTransactionId() {
        return StringUtils.isNotBlank(payment.transactionId());
    }

    /**
     * Whether the message says "may I pay?" ({@code blockchain.action = PAYMENT_INITIATED}) while
     * carrying a hash that shows the money has already moved. Bad payer behaviour (ADR-0036).
     */
    public boolean actionContradictsHash() {
        return nonNull(blockchain) && ActionEnum.PAYMENT_INITIATED.equals(blockchain.action()) && hasTransactionId();
    }

}
