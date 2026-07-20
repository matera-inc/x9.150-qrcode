/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.paymentnotification;

import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.ActionEnumDTO;
import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.app.usecase.paymentnotification.mapper.PaymentNotificationBlockchainMapper;
import com.matera.x9qrcode.app.usecase.paymentnotification.mapper.PaymentNotificationPaymentMapper;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.ExpectedDateVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPayerVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import lombok.RequiredArgsConstructor;

import static java.util.Objects.isNull;

@RequiredArgsConstructor
public class PaymentNotificationQRCodeUseCase extends UseCase<PaymentNotificationQRCodeInput, Boolean> {

    private final QRCodeRepository qrCodeRepository;

    public Boolean execute(PaymentNotificationQRCodeInput paymentNotificationQRCodeInput) {
        PaymentNotificationDataDTO notificationDataDTO = paymentNotificationQRCodeInput.paymentNotificationData();

        QRCodeIdVO qrCodeId = QRCodeIdVO.from(notificationDataDTO.qrCodeId());

        QRCodeEntity qrCodeEntity = qrCodeRepository.findById(qrCodeId);

        processPaymentNotification(qrCodeEntity, notificationDataDTO);

        qrCodeRepository.save(qrCodeEntity);

        return true;
    }

    private void processPaymentNotification(QRCodeEntity qrCodeEntity, PaymentNotificationDataDTO notificationDataDTO) {
        PaymentNotificationDataVO paymentNotificationDataVO = new PaymentNotificationDataVO(
            PaymentNotificationPaymentMapper.map(notificationDataDTO.payment()),
            isNull(notificationDataDTO.payer()) ? null : new PaymentNotificationPayerVO(notificationDataDTO.payer().info()),
            isNull(notificationDataDTO.expectedDate()) ? null : new ExpectedDateVO(notificationDataDTO.expectedDate()),
            PaymentNotificationBlockchainMapper.map(notificationDataDTO.blockchain())
        );

        switch (notificationDataDTO.payment().network()) {
            case FEDNOW, RTP -> qrCodeEntity.notifyPayment(paymentNotificationDataVO);
            case ACH -> qrCodeEntity.notifyPayment(paymentNotificationDataVO, QRCodeStatusEnum.PAYMENT_INITIATED);
            case SOLANA, POLYGON, ETHEREUM, BITCOIN -> {
                if (isNull(paymentNotificationDataVO.blockchain())) {
                    throw new BusinessRuleException("Blockchain action is required for crypto payments.");
                }

                if (ActionEnumDTO.PAYMENT_INITIATED.equals(notificationDataDTO.blockchain().action())) {
                    qrCodeEntity.notifyPayment(paymentNotificationDataVO, QRCodeStatusEnum.PAYMENT_INITIATED);
                } else {
                    qrCodeEntity.notifyPayment(paymentNotificationDataVO);
                }
            }
        }
    }

}
