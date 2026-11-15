/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationDeviationEnum;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.isNull;

/**
 * Whether a payer's notification names a rail or a currency this QR Code does not offer
 * (ADR-0036, amended 2026-11-15 by the owner).
 *
 * <ul>
 *   <li>A <b>pre-commit</b> doing so is refused with a 400 naming them, and nothing is recorded.</li>
 *   <li>A <b>post-commit</b> doing so is an error too, but the money may have moved: the call is
 *       recorded "just in case" (its hash published on a {@code payment.sent} carrying
 *       {@code NOT_OFFERED_RAIL} and/or {@code NOT_OFFERED_CURRENCY}), the QR Code is left exactly
 *       as it was, and the payer gets the same 400.</li>
 * </ul>
 *
 * <p><b>Each rail on its own.</b> A QR Code that publishes only FedNow does not offer RTP: the
 * former FedNow/RTP grouping ("instant payments") is gone. The rail is matched as
 * {@link com.matera.x9qrcode.domain.vo.NetworksVO#supports(String)} matches it, and the currency
 * case-insensitively, as the acceptance gate always has: both are a third-party payer's spelling.
 *
 * <p><b>The rail and the currency on one payment method.</b> A rail and a currency that are each
 * offered, but not on the same payment method, are not offered either (owner decision,
 * 2026-11-15): USD on Solana, when the QR Code offers USD on FedNow and USDC on Solana. That earns
 * {@code NOT_OFFERED_CURRENCY}, because the rail is offered and the currency is not offered on it,
 * and the refusal says so: "This QR Code does not offer the USD currency on the Solana rail."
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NotOfferedPolicy {

    /**
     * @return {@code NOT_OFFERED_RAIL} then {@code NOT_OFFERED_CURRENCY}, each only when true;
     *         {@code NOT_OFFERED_CURRENCY} alone when both are offered but no one payment method
     *         offers the currency on the rail; empty when one payment method offers both
     */
    public static List<NotificationDeviationEnum> notOffered(List<PaymentMethodVO> methods,
                                                             PaymentNotificationDataVO notification) {
        String network = notification.payment().network();
        String currency = notification.payment().currency();
        List<PaymentMethodVO> offered = isNull(methods) ? List.of() : methods;

        List<NotificationDeviationEnum> notOffered = new ArrayList<>(2);

        if (offered.stream().noneMatch(m -> m.networks().supports(network))) {
            notOffered.add(NotificationDeviationEnum.NOT_OFFERED_RAIL);
        }

        if (offered.stream().noneMatch(m -> m.currency().equalsIgnoreCase(currency))) {
            notOffered.add(NotificationDeviationEnum.NOT_OFFERED_CURRENCY);
        }

        if (notOffered.isEmpty() && !offeredTogether(offered, network, currency)) {
            notOffered.add(NotificationDeviationEnum.NOT_OFFERED_CURRENCY);
        }

        return List.copyOf(notOffered);
    }

    /**
     * The refusal the payer receives, the same words for a pre-commit and a post-commit.
     *
     * @param methods    the QR Code's payment methods, the ones {@link #notOffered} was asked about
     * @param notOffered the codes {@link #notOffered} returned; must not be empty
     */
    public static BusinessRuleException refusal(List<PaymentMethodVO> methods,
                                                List<NotificationDeviationEnum> notOffered,
                                                PaymentNotificationDataVO notification) {
        String network = notification.payment().network();
        String currency = notification.payment().currency();
        List<PaymentMethodVO> offered = isNull(methods) ? List.of() : methods;

        boolean rail = notOffered.contains(NotificationDeviationEnum.NOT_OFFERED_RAIL);
        boolean money = notOffered.contains(NotificationDeviationEnum.NOT_OFFERED_CURRENCY);

        if (rail && money) {
            return new BusinessRuleException("paymentNotification.data.payment",
                "This QR Code does not offer the %s rail nor the %s currency.".formatted(network, currency));
        }

        if (rail) {
            return new BusinessRuleException("paymentNotification.data.payment.network",
                "This QR Code does not offer the %s rail.".formatted(network));
        }

        if (offered.stream().anyMatch(m -> m.currency().equalsIgnoreCase(currency))) {
            // The currency is offered, only not on this rail.
            return new BusinessRuleException("paymentNotification.data.payment.currency",
                "This QR Code does not offer the %s currency on the %s rail.".formatted(currency, network));
        }

        return new BusinessRuleException("paymentNotification.data.payment.currency",
            "This QR Code does not offer the %s currency.".formatted(currency));
    }

    private static boolean offeredTogether(List<PaymentMethodVO> methods, String network, String currency) {
        return methods.stream()
            .anyMatch(m -> m.networks().supports(network) && m.currency().equalsIgnoreCase(currency));
    }

}
