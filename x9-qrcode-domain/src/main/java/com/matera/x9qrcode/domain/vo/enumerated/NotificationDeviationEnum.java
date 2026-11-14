/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo.enumerated;

/**
 * How a payer departed from the protocol when it sent a notification that was still accepted.
 *
 * <p><b>A closed list owned by ADR-0036.</b> A new code is added there first, never invented at a
 * call site. The codes travel on the events of the notification ({@code deviations}) and in the
 * warning logged for it, so a payee can count misbehaviour per payer and take the numbers back to
 * that payer.
 */
public enum NotificationDeviationEnum {

    /**
     * A post-commit reached a QR Code that was not {@code PAYMENT_INITIATED} held by this payer:
     * it was {@code ACTIVE} (the payer skipped the pre-commit, or paid after its reservation
     * lapsed), held by somebody else, {@code PAID} or {@code CANCELLED}. The event's
     * {@code receivedInStatus} says which.
     */
    RECEIVED_IN_WRONG_STATUS,

    /**
     * {@code blockchain.action = PAYMENT_INITIATED}, yet the message carries a transaction hash: it
     * asks "may I pay?" while showing the money has already moved.
     */
    ACTION_CONTRADICTS_HASH

}
