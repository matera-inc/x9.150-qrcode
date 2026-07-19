/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.paymentnotification.mapper;

import com.matera.x9qrcode.app.dto.PaymentNotificationPaymentDTO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPaymentVO;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentNotificationPaymentMapper {

    public static PaymentNotificationPaymentVO map(PaymentNotificationPaymentDTO input) {
        return new PaymentNotificationPaymentVO(
            new AmountVO(input.amount()),
            isNull(input.tipAmount()) ? null : new AmountVO(input.tipAmount()),
            input.currency(),
            NetworkEnum.fromValue(input.network().value()),
            input.transactionId()
        );
    }

}
