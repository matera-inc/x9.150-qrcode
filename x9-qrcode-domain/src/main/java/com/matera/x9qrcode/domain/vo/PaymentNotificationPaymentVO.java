/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.isNull;

/**
 * The payment a notification reports.
 *
 * <p>{@code network} is a NAME, not an enum. ANSI X9.150-2026 §2.4 lists FedNow, RTP and ACH but
 * states the notification "**MAY** also carry a network not listed above for early adopters", so the
 * set is open by design. Typing it as an enum would reject payments the standard permits — which is
 * exactly what a closed set did to Base, XRP and Arc.
 */
public record PaymentNotificationPaymentVO(
    AmountVO amount,
    AmountVO tipAmount,
    String currency,
    String network,
    String transactionId
) {

    private static final List<NetworkEnum> NETWORKS_REQUIRING_TRANSACTION_ID = List.of(
        NetworkEnum.RTP,
        NetworkEnum.FEDNOW
    );

    /** The standard's rail of this name, if it is one. Empty for every other network. */
    public Optional<NetworkEnum> standardRail() {
        return NetworkEnum.find(network);
    }

    /** Whether this is a rail ANSI X9.150 itself defines, as opposed to one carried by name. */
    public boolean isStandardRail() {
        return standardRail().isPresent();
    }

    public PaymentNotificationPaymentVO {
        if (isNull(amount)) {
            throw new ValueObjectRuleException("Payment Notification amount must not be null.");
        }

        if (NetworkEnum.find(network).filter(NETWORKS_REQUIRING_TRANSACTION_ID::contains).isPresent()
            && isNull(transactionId)) {
            throw new ValueObjectRuleException(
                "Payment Notification transactionId must not be null when network is RTP or FedNow.");
        }

        if (isNull(currency)) {
            throw new ValueObjectRuleException("Payment Notification currency must not be null.");
        }

        if (isNull(network) || network.isBlank()) {
            throw new ValueObjectRuleException("Payment Notification network must not be null.");
        }
    }
}
