/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.paymentnotification;

import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.app.exception.QRCodeConcurrentModificationException;
import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.app.usecase.paymentnotification.mapper.PaymentNotificationBlockchainMapper;
import com.matera.x9qrcode.app.usecase.paymentnotification.mapper.PaymentNotificationPaymentMapper;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.service.PaymentNotificationAcceptancePolicy;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.ExpectedDateVO;
import com.matera.x9qrcode.domain.vo.NotificationReceiptVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPayerVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;
import com.matera.x9qrcode.domain.vo.ReservationHolderVO;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationDeviationEnum;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

import static java.util.Objects.isNull;

/**
 * A payer's payment notification, applied by ADR-0036's "Every case, by message and status" matrix.
 *
 * <ul>
 *   <li><b>Pre-commit</b> (no {@code transactionId}), on every rail: "may I pay this?". Judged at
 *       the payee's clock; an accepted one takes the reservation, which is what stops two payers
 *       paying the same QR Code.</li>
 *   <li><b>Post-commit</b> ({@code transactionId} present), on every rail: the money has moved.
 *       Never refused for status reasons, never judged on amount or time; every hash is
 *       registered.</li>
 *   <li><b>{@code NOT_SENT}</b>: the holder gives the QR Code back.</li>
 * </ul>
 *
 * <p><b>Only the payment system marks a QR Code PAID.</b> Nothing here ever does.
 */
@Slf4j
@RequiredArgsConstructor
public class PaymentNotificationQRCodeUseCase extends UseCase<PaymentNotificationQRCodeInput, Boolean> {

    /**
     * How many times a notification is decided again after losing a save race. A race resolves in
     * one re-read (the loser then sees the winner's reservation), so this is generous.
     */
    static final int MAX_ATTEMPTS = 5;

    private final QRCodeRepository qrCodeRepository;
    private final PaymentNotificationAcceptancePolicy acceptancePolicy;

    /**
     * How long a pre-commit's reservation holds before it stops counting —
     * {@code x9.reservation.ttl-seconds}.
     *
     * <p>Declared LAST on purpose: {@code @RequiredArgsConstructor} orders parameters by field
     * declaration, so inserting a field mid-list silently reorders the constructor and any caller
     * passing arguments positionally starts passing the wrong ones.
     */
    private final Duration reservationTtl;

    public Boolean execute(PaymentNotificationQRCodeInput paymentNotificationQRCodeInput) {
        PaymentNotificationDataDTO notificationDataDTO = paymentNotificationQRCodeInput.paymentNotificationData();

        QRCodeIdVO qrCodeId = QRCodeIdVO.from(notificationDataDTO.qrCodeId());

        // Two payers reaching for one QR Code at the same instant both read it ACTIVE; only one save
        // can win. The loser re-reads and is decided again against what the winner wrote, so it
        // gets the answer the matrix gives it (409 for a pre-commit, a registration for a
        // post-commit) instead of a 500 (S2.4-AC2).
        for (int attempt = 1; ; attempt++) {
            QRCodeEntity qrCodeEntity = qrCodeRepository.findById(qrCodeId);

            try {
                processPaymentNotification(qrCodeEntity, notificationDataDTO,
                    paymentNotificationQRCodeInput.signerSubject());

                qrCodeRepository.save(qrCodeEntity);

                return true;
            } catch (QRCodeConcurrentModificationException raceLost) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw raceLost;
                }
            }
        }
    }

    private void processPaymentNotification(QRCodeEntity qrCodeEntity, PaymentNotificationDataDTO notificationDataDTO,
                                            String signerSubject) {
        PaymentNotificationDataVO data = new PaymentNotificationDataVO(
            PaymentNotificationPaymentMapper.map(notificationDataDTO.payment()),
            isNull(notificationDataDTO.payer()) ? null : new PaymentNotificationPayerVO(notificationDataDTO.payer().info()),
            isNull(notificationDataDTO.expectedDate()) ? null : new ExpectedDateVO(notificationDataDTO.expectedDate()),
            PaymentNotificationBlockchainMapper.map(notificationDataDTO.blockchain())
        );

        // Who sent this: what they said about themselves, paired with who the certificate says they
        // are. Without payer.info nobody can be shown to be the same payer (ADR-0036).
        ReservationHolderVO sender = ReservationHolderVO.of(
            isNull(notificationDataDTO.payer()) ? null : notificationDataDTO.payer().info(),
            signerSubject);

        if (!data.payment().isBankRail() && isNull(data.blockchain())) {
            throw new BusinessRuleException(
                "Blockchain data is required for a notification on the %s network."
                    .formatted(data.payment().network()));
        }

        // The phase comes from the evidence, on every rail: transactionId present means the money
        // has moved (ADR-0004, ADR-0036). blockchain.action decides only NOT_SENT.
        switch (data.phase()) {
            case PRE_COMMIT -> preCommit(qrCodeEntity, data, sender);
            case POST_COMMIT -> warnOnDeviations(qrCodeEntity, sender, qrCodeEntity.registerPostCommit(data, sender));
            case RELEASE -> qrCodeEntity.releaseByPayer(data, sender);
        }
    }

    /**
     * A request for permission, so it must earn its yes: the correct amount, a currency and
     * destination this QR Code published, nothing expired, all at the payee's clock.
     *
     * <p>The status is decided before the gate, so a second payer is told 409, not a 400 about its
     * amount. The holder's own retry is judged again.
     */
    private void preCommit(QRCodeEntity qrCodeEntity, PaymentNotificationDataVO data, ReservationHolderVO sender) {
        qrCodeEntity.admitPreCommit(data, sender);

        acceptancePolicy.accept(qrCodeEntity, data, DateTimeUtils.nowUTC());

        qrCodeEntity.acceptPreCommit(data, sender, reservationTtl);
    }

    /**
     * Name the payer when it broke the protocol. The hash is registered either way; the warning and
     * the event's {@code deviations} are how the payee counts misbehaviour per payer and takes the
     * numbers back to it.
     */
    private static void warnOnDeviations(QRCodeEntity qrCodeEntity, ReservationHolderVO sender,
                                         NotificationReceiptVO receipt) {
        if (!receipt.hasDeviations()) {
            return;
        }

        if (receipt.deviations().contains(NotificationDeviationEnum.ACTION_CONTRADICTS_HASH)) {
            // !!! BAD PAYER BEHAVIOUR !!!
            // blockchain.action = PAYMENT_INITIATED says "may I pay?", yet the message carries a
            // transaction hash: the money has ALREADY moved. Either the payer skipped the question
            // or it labelled its report wrongly; a payer that follows the protocol never sends this.
            //
            // It is still a post-commit and its hash is still registered, on purpose. If the funds
            // arrive in the right amount and the hash matches, the payment system has everything it
            // needs to mark the QR Code paid. Refusing the report would throw away the one piece of
            // evidence that links those funds to this QR Code (ADR-0036).
            log.warn("Payer sent PAYMENT_INITIATED carrying a transaction hash (bad payer behaviour); "
                    + "registered as a post-commit. qrCodeId={} payerInfo={} signerSubject={} "
                    + "receivedInStatus={} deviations={}",
                qrCodeEntity.getId().value(), sender.payerInfo(), sender.signerSubject(),
                receipt.receivedInStatus(), receipt.deviations());
            return;
        }

        log.warn("Post-commit registered from a payer that broke the protocol. qrCodeId={} payerInfo={} "
                + "signerSubject={} receivedInStatus={} deviations={}",
            qrCodeEntity.getId().value(), sender.payerInfo(), sender.signerSubject(),
            receipt.receivedInStatus(), receipt.deviations());
    }

}
