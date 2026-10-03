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
import com.matera.x9qrcode.domain.service.PaymentNotificationAcceptancePolicy;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.ExpectedDateVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPayerVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import lombok.RequiredArgsConstructor;

import java.time.Duration;

import static java.util.Objects.isNull;

@RequiredArgsConstructor
public class PaymentNotificationQRCodeUseCase extends UseCase<PaymentNotificationQRCodeInput, Boolean> {

    private final QRCodeRepository qrCodeRepository;
    private final PaymentNotificationAcceptancePolicy acceptancePolicy;

    /**
     * How long a reservation holds before it stops counting — {@code x9.reservation.ttl-seconds}.
     *
     * <p>Declared LAST on purpose: {@code @RequiredArgsConstructor} orders parameters by field
     * declaration, so inserting a field mid-list silently reorders the constructor and any caller
     * passing arguments positionally starts passing the wrong ones.
     */
    private final Duration reservationTtl;

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

        NotificationIntent intent = resolveIntent(notificationDataDTO, paymentNotificationDataVO);

        // A pre-funds notification is a request for permission, so it must earn its OK: correct
        // amount, a currency and destination this QR Code actually published, nothing expired. A
        // post-commit notification reports money that has already moved and is only recorded —
        // refusing it would be a lie about reality, and there is nothing left to permit.
        if (NotificationIntent.INITIATE.equals(intent)) {
            acceptancePolicy.accept(qrCodeEntity, paymentNotificationDataVO, DateTimeUtils.nowUTC());
        }

        switch (intent) {
            case INITIATE -> qrCodeEntity.notifyPayment(paymentNotificationDataVO, QRCodeStatusEnum.PAYMENT_INITIATED, reservationTtl);
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
        // A network this service does not interpret cannot reach a QR Code any more — it is refused
        // at creation (ADR-0012) — but a notification can still name one, and it takes the generic
        // on-chain path rather than falling through in silence.
        if (!paymentNotificationDataVO.payment().isInterpretedRail()) {
            return onChainIntent(notificationDataDTO, paymentNotificationDataVO);
        }

        return switch (paymentNotificationDataVO.payment().interpretedRail().orElseThrow()) {
            // The QR Code id travels inside the ISO 20022 message, so the payee reconciles from the
            // message itself; a notification on these rails is a courtesy and changes no status.
            case FEDNOW, RTP -> NotificationIntent.RECORD;
            case ACH -> NotificationIntent.INITIATE;
            // Solana is the one interpreted rail with a public ledger, so it is the one that can
            // distinguish before-the-funds-move from after. That distinction is the whole two-phase
            // protocol, and it has had no trigger since the blockchains were removed.
            case SOLANA -> onChainIntent(notificationDataDTO, paymentNotificationDataVO);
        };
    }

    /**
     * Pre-commit or post-commit, for a rail that settles on a public ledger.
     *
     * <p>The phase is carried by {@code blockchain.action} because ANSI X9.150 has no phase marker —
     * the committee rejected one (ADR-0004) — so the only signal is whether a transaction exists yet.
     */
    private NotificationIntent onChainIntent(PaymentNotificationDataDTO notificationDataDTO,
                                             PaymentNotificationDataVO paymentNotificationDataVO) {
        if (isNull(paymentNotificationDataVO.blockchain())) {
            throw new BusinessRuleException(
                "Blockchain data is required for a notification on the %s network."
                    .formatted(paymentNotificationDataVO.payment().network()));
        }

        return switch (notificationDataDTO.blockchain().action()) {
            // Pre-commit: nothing has moved on-chain yet, so this is the notification that takes the
            // QR Code out of circulation.
            case PAYMENT_INITIATED -> NotificationIntent.INITIATE;
            // Post-commit. The transaction is on-chain, but X9.150 never touches money and cannot
            // observe settlement, so the QR Code STAYS PAYMENT_INITIATED. Whatever system receives
            // the funds matches the transaction and calls the status-update API to mark it PAID.
            case SENT, NOT_SENT -> NotificationIntent.RECORD;
        };
    }

    private enum NotificationIntent {
        /** Take the QR Code out of circulation: ACTIVE -> PAYMENT_INITIATED. */
        INITIATE,
        /** Record the notification and bump the revision, leaving the status untouched. */
        RECORD
    }

}
