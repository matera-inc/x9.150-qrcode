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
    private static final String PAYLOAD_IS_ALREADY_CANCELLED_OR_PAID =
        "payment payload with ID: %s is already cancelled or paid.";

    private final QRCodeRepository qrCodeRepository;
    private final QRCodeSignatureService qrCodeSignatureService;
    private final FormulaFactory formulaFactory;
    private final QRCodeLocationService qrCodeLocationService; 

    @Override
    public RetrieveQRCodePayloadOutput execute(RetrieveQRCodePayloadInput input) {
        LocationIdVO locationId = LocationIdVO.from(input.uuid());

        QRCodeEntity qrCodeEntity = retrieveQrCodeEntity(locationId);

        if (qrCodeEntity.isNotActiveOrInitiated()) {
            throw new BusinessRuleException(PAYLOAD_IS_ALREADY_CANCELLED_OR_PAID.formatted(locationId.valueAsString()));
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
            qrCodeEntity.getStatus().value(),
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
