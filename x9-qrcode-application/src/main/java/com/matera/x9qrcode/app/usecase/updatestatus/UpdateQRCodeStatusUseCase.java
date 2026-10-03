/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.updatestatus;

import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.PaymentDetailsVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.List;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@RequiredArgsConstructor
public class UpdateQRCodeStatusUseCase extends UseCase<UpdateQRCodeStatusInput, UpdateQRCodeStatusOutput> {

    private final QRCodeRepository qrCodeRepository;

    /**
     * How long a reservation holds before it stops counting. Configuration, not a constant, because
     * the right window depends on the deployment: a till wants seconds, a bank transfer longer.
     */
    private final Duration reservationTtl;

    @Override
    public UpdateQRCodeStatusOutput execute(UpdateQRCodeStatusInput updateQRCodeStatusInput) {
        QRCodeIdVO qrCodeIdVO = QRCodeIdVO.from(updateQRCodeStatusInput.id());

        QRCodeEntity qrCodeEntity = retrieveQRCodeEntity(qrCodeIdVO);

        // Before anything else, and before any transition is attempted: a refused precondition must
        // leave the QR Code exactly as it was, including any validation side effects.
        qrCodeEntity.requireEntityTag(updateQRCodeStatusInput.expectedEntityTag());

        String network = updateQRCodeStatusInput.paymentNetwork();

        if (nonNull(network)) {
            checkNetworkIsValidPaymentMethod(qrCodeEntity.getPaymentMethods(), network);
        }

        // Asking for the status it already has is a repeated call, not a transition. HTTP retries,
        // at-least-once queues and a human clicking twice all produce it, and every one of them
        // means "make sure it is X", which it already is.
        //
        // A true no-op: no event, no revision, no revisedAt. Something that emitted a second
        // payment.cleared would make a duplicated request indistinguishable from a second payment.
        //
        // PAYEE-FACING ONLY. A payer announcing twice is two payers reaching for one bill and is
        // still refused — that path is the notification endpoint, not this one.
        // Compared against the STORED status, not the effective one. A lapsed reservation asked to
        // go ACTIVE is a real transition — it writes the release down and clears the stamp — and
        // short-circuiting on the effective reading would leave that stamp behind.
        if (qrCodeEntity.getStatus().equals(QRCodeStatusEnum.fromValue(updateQRCodeStatusInput.status().value()))) {
            return new UpdateQRCodeStatusOutput(qrCodeIdVO.valueAsString(), qrCodeEntity.effectiveStatus().value());
        }

        switch (updateQRCodeStatusInput.status()) {
            case PAID -> qrCodeEntity.pay(buildPaymentDetails(updateQRCodeStatusInput));
            case PAYMENT_INITIATED -> qrCodeEntity.initiatePayment(buildPaymentDetails(updateQRCodeStatusInput), reservationTtl);
            case CANCELLED -> qrCodeEntity.cancel(buildPaymentDetails(updateQRCodeStatusInput));
            case ACTIVE -> qrCodeEntity.reactivate(buildPaymentDetails(updateQRCodeStatusInput));
            default -> throw new BusinessRuleException(
                "Status %s is not allowed.".formatted(updateQRCodeStatusInput.status().value()));
        }

        qrCodeRepository.save(qrCodeEntity);

        return new UpdateQRCodeStatusOutput(
            qrCodeIdVO.valueAsString(),
            qrCodeEntity.effectiveStatus().value()
        );
    }

    private QRCodeEntity retrieveQRCodeEntity(QRCodeIdVO qrCodeIdVO) {
        try {
            return qrCodeRepository.findById(qrCodeIdVO);
        } catch (BusinessRuleException e) {
            throw new BusinessRuleException(EXPIRED_QRCODE_ERROR_MESSAGE.formatted(qrCodeIdVO));
        }
    }

    private void checkNetworkIsValidPaymentMethod(List<PaymentMethodVO> paymentMethods, String network) {
        if (paymentMethods.stream().noneMatch(paymentMethod -> paymentMethod.networks().supports(network))) {
            throw new BusinessRuleException("Network %s is not a valid payment method.".formatted(network));
        }
    }


    private PaymentDetailsVO buildPaymentDetails(UpdateQRCodeStatusInput input) {
        if (isNull(input.endToEndId()) && isNull(input.paymentNetwork())) {
            return null;
        }

        return new PaymentDetailsVO(
            input.endToEndId(),
            input.paymentNetwork()
        );
    }

}
