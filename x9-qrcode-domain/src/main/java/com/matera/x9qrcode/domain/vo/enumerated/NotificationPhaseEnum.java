/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo.enumerated;

/**
 * The moment a payment notification was sent at, decided from the evidence (ADR-0004, ADR-0036).
 *
 * <p>A transaction id exists only once the transaction is committed, so its presence is what makes
 * a notification a post-commit, on every rail. {@code blockchain.action} only corroborates, with
 * one exception: {@code NOT_SENT}, the payer giving a reserved QR Code back, which by its nature
 * carries no transaction id.
 */
public enum NotificationPhaseEnum {

    /** No {@code transactionId}: "may I pay this?". Judged, and an accepted one takes the reservation. */
    PRE_COMMIT,

    /** {@code transactionId} present: the money has moved. Recorded, never judged again. */
    POST_COMMIT,

    /** {@code blockchain.action = NOT_SENT}: the holder gives the QR Code back. */
    RELEASE

}
