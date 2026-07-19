/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;

import java.net.URI;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public record PaymentNotificationVO(
    NotificationKindEnum kind,
    URI endpoint,
    PaymentNotificationDataVO data
) {
    public PaymentNotificationVO {
        if (isNull(kind)) {
            throw new ValueObjectRuleException("Payment notification kind is required");
        }

        if (NotificationKindEnum.EXTERNAL.equals(kind) && isNull(endpoint)) {
            throw new ValueObjectRuleException("Payment notification endpoint is required when kind is EXTERNAL");
        }

        if (NotificationKindEnum.DEFAULT.equals(kind) && nonNull(endpoint)) {
            throw new ValueObjectRuleException("Payment notification endpoint should not be informed when kind is DEFAULT");
        }
    }
}
