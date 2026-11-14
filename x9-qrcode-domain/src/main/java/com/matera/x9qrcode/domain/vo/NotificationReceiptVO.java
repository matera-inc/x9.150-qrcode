/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.vo.enumerated.NotificationDeviationEnum;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import java.util.List;

import static java.util.Objects.isNull;

/**
 * How an accepted payer notification arrived: the QR Code's effective status at the instant it was
 * received, and the ways the payer departed from the protocol (ADR-0036).
 *
 * <p>Both travel on every event the notification produces ({@code receivedInStatus},
 * {@code deviations}), and the deviations are logged as a warning naming the payer.
 *
 * @param receivedInStatus the effective status when the notification arrived; never null
 * @param deviations       the codes, in a stable order; <b>empty when the payer followed the
 *                         rules</b> (the event then carries no {@code deviations} at all, never an
 *                         empty list)
 */
public record NotificationReceiptVO(QRCodeStatusEnum receivedInStatus, List<NotificationDeviationEnum> deviations) {

    public NotificationReceiptVO {
        deviations = isNull(deviations) ? List.of() : List.copyOf(deviations);
    }

    /** Whether the payer departed from the protocol at all. */
    public boolean hasDeviations() {
        return !deviations.isEmpty();
    }

}
