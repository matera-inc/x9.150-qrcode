/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.paymentnotification;

import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;
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
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;
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

        switch (resolveIntent(notificationDataDTO, paymentNotificationDataVO)) {
            case INITIATE -> qrCodeEntity.notifyPayment(paymentNotificationDataVO, QRCodeStatusEnum.PAYMENT_INITIATED);
            case RECORD -> qrCodeEntity.notifyPayment(paymentNotificationDataVO);
        }
    }

    /**
     * What a notification asks us to do, decided exhaustively over {@link NetworkEnum}.
     *
     * <p>Both switches below are switch EXPRESSIONS, so a rail added to the enum without being
     * classified here fails to compile. That is deliberate: the previous switch statement listed
     * only four blockchains and had no default, so a Base, XRP or Arc notification fell straight
     * through — never validated, never applied, and answered 200 OK for a payment it ignored.
     */
    private NotificationIntent resolveIntent(PaymentNotificationDataDTO notificationDataDTO,
                                             PaymentNotificationDataVO paymentNotificationDataVO) {
        // The VO carries the DOMAIN enum; the DTO carries the wire enum. Classification lives on the
        // domain enum, so resolve from the mapped VO.
        NetworkEnum network = paymentNotificationDataVO.payment().network();

        if (network.isBlockchain()) {
            if (isNull(paymentNotificationDataVO.blockchain())) {
                throw new BusinessRuleException("Blockchain action is required for crypto payments.");
            }

            return switch (notificationDataDTO.blockchain().action()) {
                // Pre-commit: nothing has moved on-chain yet, so this is the notification that takes
                // the QR Code out of circulation.
                case PAYMENT_INITIATED -> NotificationIntent.INITIATE;
                // Post-commit. The transaction is on-chain, but X9.150 never touches money and cannot
                // observe settlement, so the QR Code STAYS PAYMENT_INITIATED. Whatever system receives
                // the funds matches the transaction and calls the status-update API to mark it PAID.
                case SENT, NOT_SENT -> NotificationIntent.RECORD;
            };
        }

        return switch (network) {
            // The QR Code id travels inside the ISO 20022 message, so the payee reconciles from the
            // message itself; a notification on these rails is a courtesy and changes no status.
            case FEDNOW, RTP -> NotificationIntent.RECORD;
            case ACH -> NotificationIntent.INITIATE;
            case SOLANA -> throw new IllegalStateException("Blockchain rails are handled above: " + network);
        };
    }

    private enum NotificationIntent {
        /** Take the QR Code out of circulation: ACTIVE -> PAYMENT_INITIATED. */
        INITIATE,
        /** Record the notification and bump the revision, leaving the status untouched. */
        RECORD
    }

}
