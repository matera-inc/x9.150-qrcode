/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.exception;

import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import lombok.Getter;

/**
 * A conditional request whose precondition no longer holds: the QR Code changed after the caller
 * read it.
 *
 * <p>Distinct from {@link QRCodeStatusConflictException}, and the distinction matters to a caller.
 * A status conflict says <em>this transition is not legal from where the QR Code is</em>. This says
 * <em>the transition may well be legal, but you decided on a reading that is now stale</em> — a
 * cancel is permitted from both ACTIVE and PAYMENT_INITIATED, so a caller who means "cancel only if
 * still ACTIVE" gets no protection from legality alone.
 *
 * <p>Carries both the status and the revision actually found, so the caller can decide without a
 * second request: PAYMENT_INITIATED means someone is paying and the cancel should be abandoned; PAID
 * means settle rather than cancel.
 */
@Getter
public class QRCodePreconditionFailedException extends RuntimeException {

    private final QRCodeStatusEnum currentStatus;
    private final Integer currentRevision;

    public QRCodePreconditionFailedException(QRCodeStatusEnum currentStatus,
                                             Integer currentRevision,
                                             String message) {
        super(message);
        this.currentStatus = currentStatus;
        this.currentRevision = currentRevision;
    }

}
