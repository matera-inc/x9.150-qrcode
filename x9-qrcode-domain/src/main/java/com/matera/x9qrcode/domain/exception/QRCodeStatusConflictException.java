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
 * Thrown when a transition is refused because the QR Code is no longer in the status the
 * transition requires — it is already being paid, already paid, or cancelled.
 *
 * <p>Distinct from a plain {@link BusinessRuleException} because the request was well formed: the
 * QR simply moved on. Callers need to tell "your request is wrong" (400) from "you lost the race"
 * (409), so this maps to HTTP 409 and carries the status that was actually found.
 */
@Getter
public class QRCodeStatusConflictException extends BusinessRuleException {

    private final QRCodeStatusEnum currentStatus;

    public QRCodeStatusConflictException(QRCodeStatusEnum currentStatus, String message) {
        super(message);
        this.currentStatus = currentStatus;
    }

}
