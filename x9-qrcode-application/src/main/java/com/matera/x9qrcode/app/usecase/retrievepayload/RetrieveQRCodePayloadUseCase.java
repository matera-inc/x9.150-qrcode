/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrievepayload;

import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.app.usecase.retrievepayload.mapper.RetrieveQRCodePayloadBillMapper;
import com.matera.x9qrcode.app.usecase.retrievepayload.mapper.RetrieveQRCodePayloadCreditorMapper;
import com.matera.x9qrcode.app.usecase.retrievepayload.mapper.RetrieveQRCodePayloadFormulaResultMapper;
import com.matera.x9qrcode.app.usecase.retrievepayload.mapper.RetrieveQRCodePayloadPaymentMethodMapper;
import com.matera.x9qrcode.domain.dto.FormulaResultDTO;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.service.FormulaService;
import com.matera.x9qrcode.domain.service.factory.FormulaFactory;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.LocationIdVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;

import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

@RequiredArgsConstructor
public class RetrieveQRCodePayloadUseCase extends UseCase<RetrieveQRCodePayloadInput, RetrieveQRCodePayloadOutput> {

    private static final String EXPIRED_PAYLOAD_ERROR_MESSAGE = "payment payload with ID: %s is expired.";
    private static final String PAYLOAD_IS_ALREADY_PAID =
        "payment payload with ID: %s has already been paid.";
    private static final String PAYLOAD_WAS_CANCELLED =
        "payment payload with ID: %s was cancelled by the biller.";
    private static final String PAYLOAD_IS_NOT_PAYABLE =
        "payment payload with ID: %s is not open for payment.";

    private final QRCodeRepository qrCodeRepository;
    private final QRCodeSignatureService qrCodeSignatureService;
    private final FormulaFactory formulaFactory;
    private final QRCodeLocationService qrCodeLocationService; 

    @Override
    public RetrieveQRCodePayloadOutput execute(RetrieveQRCodePayloadInput input) {
        LocationIdVO locationId = LocationIdVO.from(input.uuid());

        QRCodeEntity qrCodeEntity = retrieveQrCodeEntity(locationId);

        // The binding the signature exists to carry: the content the payer submitted must be the
        // content this QR Code was issued with. Everything the signature service could check was
        // caller-supplied on both sides; this is the first comparison against what we stored.
        qrCodeEntity.requireIssuedQrCodeContent(input.submittedQrCodeContent());

        if (qrCodeEntity.isNotActiveOrInitiated()) {
            throw new BusinessRuleException(notPayableReason(qrCodeEntity, locationId));
        }

        // Checked explicitly rather than left to the document disappearing. EXPIRED_PAYLOAD below
        // is only reached once MongoDB's TTL index removes the row, which happens long after
        // validUntil — so a lapsed QR Code went on serving its payload, and a payer scanning a
        // week-old code was handed bank details and an amount as if they were still on offer.
        //
        // This endpoint is PAYER-facing, so it is strict: expired, paid and cancelled are all
        // refused. The payee's own endpoints are deliberately not gated this way — ADR-0020.
        if (qrCodeEntity.isExpired()) {
            throw new BusinessRuleException(EXPIRED_PAYLOAD_ERROR_MESSAGE.formatted(locationId.valueAsString()));
        }

        FormulaResultDTO formulaResult = null;

        BillVO bill = qrCodeEntity.getBill();

        if (nonNull(bill.amountDue().adjustment())) {
            FormulaService formulaService =
                formulaFactory.createFormula(bill.amountDue().adjustment().formula());

            formulaResult = formulaService.calculate(
                input.getZonedDateForPayment(),
                bill.invoice().dueDate(),
                bill.amountDue().currencyAmount().amount(),
                bill.amountDue().currencyAmount().currency(),
                bill.amountDue().adjustment().parameters()
            );
        }

        String qrCodeContent = Base64.getUrlEncoder().withoutPadding().encodeToString(
            qrCodeEntity.getQrcodeContent().value().getBytes(StandardCharsets.UTF_8)
        );

        return new RetrieveQRCodePayloadOutput(
            qrCodeEntity.getId().value(),
            qrCodeEntity.getLocationId().value(),
            qrCodeEntity.getRevision(),
            qrCodeContent,
            qrCodeEntity.getCreatedAt(),
            qrCodeEntity.getRevisedAt(),
            DateTimeUtils.nowUTC(),
            qrCodeEntity.getValidUntil(),
            // effectiveStatus: a lapsed reservation reads as ACTIVE, so a caller is told what is
            // true now rather than what was last reported. See QRCodeEntity.effectiveStatus.
            qrCodeEntity.effectiveStatus().value(),
            RetrieveQRCodePayloadCreditorMapper.map(qrCodeEntity.getCreditor()),
            RetrieveQRCodePayloadBillMapper.map(bill, formulaResult),
            qrCodeEntity.getUnstructured().value(),
            qrCodeEntity.getAdditionalInformation(),
            getPaymentNotificationUri(qrCodeEntity),
            RetrieveQRCodePayloadPaymentMethodMapper.map(
                getValidPaymentMethods(qrCodeEntity, input.getZonedDateForPayment()), formulaResult),
            RetrieveQRCodePayloadFormulaResultMapper.map(formulaResult)
        );
    }
    
    private URI getPaymentNotificationUri(QRCodeEntity qrCodeEntity) {
        if (nonNull(qrCodeEntity.getPaymentNotification())) {
            if (NotificationKindEnum.EXTERNAL.equals(qrCodeEntity.getPaymentNotification().kind())) {
                return qrCodeEntity.getPaymentNotification().endpoint();
            }
            return qrCodeLocationService.retrievePaymentNotificationEndpoint();
        }
        return null;
    }

    /**
     * Which of the two it is, said out loud.
     *
     * <p>This used to answer "is already cancelled or paid" for both, and that single word "or" was
     * the whole problem: they call for opposite things from the person holding the phone. Already
     * paid means stop, somebody has settled this bill. Cancelled means the biller withdrew it, and
     * the payer should go back and ask. A consumer cannot route on a disjunction.
     *
     * <p>Naming the status to an unauthenticated caller is safe here because of where this sits:
     * {@code requireIssuedQrCodeContent} has already run, so only somebody holding the QR Code as
     * issued gets this far — and what they are being told is the state of the bill in their own
     * hand.
     */
    private String notPayableReason(QRCodeEntity qrCodeEntity, LocationIdVO locationId) {
        String id = locationId.valueAsString();

        return switch (qrCodeEntity.effectiveStatus()) {
            case PAID -> PAYLOAD_IS_ALREADY_PAID.formatted(id);
            case CANCELLED -> PAYLOAD_WAS_CANCELLED.formatted(id);
            default -> PAYLOAD_IS_NOT_PAYABLE.formatted(id);
        };
    }

    private QRCodeEntity retrieveQrCodeEntity(LocationIdVO locationId) {
        try {
            return qrCodeRepository.findByLocationId(locationId);
        } catch (BusinessRuleException e) {
            throw new BusinessRuleException(EXPIRED_PAYLOAD_ERROR_MESSAGE.formatted(locationId.valueAsString()));
        }
    }

    private List<PaymentMethodVO> getValidPaymentMethods(QRCodeEntity qrCodeEntity, OffsetDateTime dateForPayment) {
        List<PaymentMethodVO> paymentMethods =
            qrCodeEntity.getPaymentMethods().stream().filter(pm -> !dateForPayment.isAfter(pm.validUntil())).collect(Collectors.toList());

        if (paymentMethods.isEmpty()) {
            return null;
        }

        return paymentMethods;
    }
}
