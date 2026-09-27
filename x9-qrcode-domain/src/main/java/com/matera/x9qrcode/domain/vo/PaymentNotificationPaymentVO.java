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

    /**
     * The rail of this name, if this service interprets one. Empty for every other network.
     *
     * <p>"Interpreted" rather than "standard", because the two stopped being the same thing when
     * Solana was added: its fields come from the Solana Foundation's published embedding, not from
     * ANSI X9.150, which defines only where a network object hangs. What the name really asks is
     * "do we know the shape of this rail?", and that is the question every caller needs answered.
     */
    public Optional<NetworkEnum> interpretedRail() {
        return NetworkEnum.find(network);
    }

    /** Whether this is a rail ANSI X9.150 itself defines, as opposed to one carried by name. */
    /**
     * Whether this rail settles through a bank rather than a public ledger.
     *
     * <p>Kept distinct from {@link #isInterpretedRail()} because the two were the same thing until
     * Solana arrived, and code that conflated them silently stopped checking destinations. A bank
     * notification names no destination account — it is in the payment message, not here — while an
     * on-chain one names exactly the address the funds went to, and that address must be one this
     * QR Code published.
     */
    public boolean isBankRail() {
        return interpretedRail().filter(rail -> !rail.isBlockchain()).isPresent();
    }

    public boolean isInterpretedRail() {
        return interpretedRail().isPresent();
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
