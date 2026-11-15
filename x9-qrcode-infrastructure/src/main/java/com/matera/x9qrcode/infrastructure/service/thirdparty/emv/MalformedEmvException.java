/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.emv;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;

/**
 * An EMV string that is not well-formed merchant-presented TLV: a bad or missing CRC, a zero,
 * non-numeric or overrunning length, a duplicate tag, a truncated field. Refused, never tolerated
 * (ADR-0027, EMV). A business rule, so the decoder answers it with a 400 naming {@code qrCode}.
 */
public class MalformedEmvException extends BusinessRuleException {

    private final String reason;

    public MalformedEmvException(String reason) {
        super("qrCode", "The QR Code is malformed: %s.".formatted(reason));
        this.reason = reason;
    }

    /** What is wrong, without the field or the sentence around it. */
    public String reason() {
        return reason;
    }

}
