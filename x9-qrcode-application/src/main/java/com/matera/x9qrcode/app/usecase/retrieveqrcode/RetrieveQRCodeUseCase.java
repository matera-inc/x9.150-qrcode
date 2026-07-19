/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrieveqrcode;

import com.matera.x9qrcode.app.dto.LocationDTO;
import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.app.service.QRCodeEMVService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.app.usecase.retrieveqrcode.mapper.RetrieveQRCodeBillMapper;
import com.matera.x9qrcode.app.usecase.retrieveqrcode.mapper.RetrieveQRCodeCreditorMapper;
import com.matera.x9qrcode.app.usecase.retrieveqrcode.mapper.RetrieveQRCodeLocationMapper;
import com.matera.x9qrcode.app.usecase.retrieveqrcode.mapper.RetrieveQRCodePaymentDetailsMapper;
import com.matera.x9qrcode.app.usecase.retrieveqrcode.mapper.RetrieveQRCodePaymentMethodMapper;
import com.matera.x9qrcode.app.usecase.retrieveqrcode.mapper.RetrieveQRCodePaymentNotificationMapper;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;

import lombok.RequiredArgsConstructor;

import static java.util.Objects.nonNull;

@RequiredArgsConstructor
public class RetrieveQRCodeUseCase extends UseCase<RetrieveQRCodeInput, RetrieveQRCodeOutput> {

    private static final String EXPIRED_PAYLOAD_ERROR_MESSAGE = "payment payload with ID: %s is expired.";

    private final QRCodeRepository qrCodeRepository;
    private final QRCodeEMVService qrCodeEMVService;
    private final QRCodeLocationService qrCodeLocationService;

    @Override
    public RetrieveQRCodeOutput execute(RetrieveQRCodeInput input) {
        QRCodeIdVO qrCodeIdVO = QRCodeIdVO.from(input.id());

        QRCodeEntity qrCodeEntity = retrieveQrCodeEntity(qrCodeIdVO, input.revision());

        LocationDTO locationDTO = RetrieveQRCodeLocationMapper.map(
            qrCodeEntity.getLocationId(), qrCodeLocationService.generateLocation(qrCodeEntity.getLocationId(), false));

        return new RetrieveQRCodeOutput(
            qrCodeIdVO.value(),
            locationDTO,
            qrCodeEntity.getRevision(),
            qrCodeEntity.getCreatedAt(),
            qrCodeEntity.getRevisedAt(),
            DateTimeUtils.nowUTC(),
            qrCodeEntity.getValidUntil(),
            qrCodeEntity.getStatus().value(),
            RetrieveQRCodeCreditorMapper.map(qrCodeEntity.getCreditor()),
            RetrieveQRCodeBillMapper.map(qrCodeEntity.getBill()),
            qrCodeEntity.getUnstructured().value(),
            qrCodeEntity.getAdditionalInformation(),
            RetrieveQRCodePaymentNotificationMapper.map(qrCodeEntity.getPaymentNotification()),
            RetrieveQRCodePaymentMethodMapper.map(qrCodeEntity.getPaymentMethods()),
            RetrieveQRCodePaymentDetailsMapper.map(qrCodeEntity.getPaymentDetails()),

            qrCodeEMVService.generateQrCodeContent(qrCodeEntity)
        );
    }

    private QRCodeEntity retrieveQrCodeEntity(QRCodeIdVO qrCodeIdVO, Integer revision) {
        try {
            if (nonNull(revision)) {
                validateRevision(revision);

                //TODO: History feature is not implemented yet, will be added in the QR Code Update Use Case.

                return qrCodeRepository.findByIdAndRevision(qrCodeIdVO, revision);
            } else {
                return qrCodeRepository.findById(qrCodeIdVO);
            }
        } catch (BusinessRuleException e) {
            throw new BusinessRuleException(EXPIRED_PAYLOAD_ERROR_MESSAGE.formatted(qrCodeIdVO));
        }
    }

    private void validateRevision(Integer revision) {
        if (revision < 0) {
            throw new BusinessRuleException("Revision must be equal or greater than zero.");
        }
    }

}
