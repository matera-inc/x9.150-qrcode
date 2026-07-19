/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.patchqrcode;

import com.matera.x9qrcode.app.dto.LocationDTO;
import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.app.service.QRCodeEMVService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.app.usecase.createqrcode.mapper.CreateQRCodeLocationMapper;
import com.matera.x9qrcode.app.usecase.patchqrcode.mapper.PatchQRCodeBillMapper;
import com.matera.x9qrcode.app.usecase.patchqrcode.mapper.PatchQRCodePaymentMethodsMapper;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.LocationIdVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class PatchQRCodeUseCase extends UseCase<PatchQRCodeInput, PatchQRCodeOutput> {

    private final QRCodeRepository qrCodeRepository;
    private final QRCodeEMVService qrCodeEMVService;
    private final QRCodeLocationService qrCodeLocationService;

    @Override
    public PatchQRCodeOutput execute(PatchQRCodeInput input) {
        QRCodeIdVO qrCodeIdVO = QRCodeIdVO.from(input.id());

        QRCodeEntity qrCodeEntity = qrCodeRepository.findById(qrCodeIdVO);

        if (qrCodeEntity.isNotActiveOrInitiated()) {
            throw new BusinessRuleException("QR code with id %s must be active to be updated.".formatted(qrCodeIdVO));
        }

        input.locationId().ifPresent(locationId -> treatLocationUpdate(locationId, qrCodeEntity));
        input.validUntil().ifPresent(qrCodeEntity::updateValidUntil);
        input.additionalInformationMap().ifPresent(qrCodeEntity::updateAdditionalInformation);
        input.unstructured().ifPresent(qrCodeEntity::updateUnstructured);
        input.billUpdateDTO().ifPresent(billUpdateDTO ->
            qrCodeEntity.updateBill(PatchQRCodeBillMapper.map(qrCodeEntity.getBill(), billUpdateDTO)));

        List<PaymentMethodVO> updatedPaymentMethods =
            PatchQRCodePaymentMethodsMapper.map(qrCodeEntity.getPaymentMethods(), input.paymentMethodUpdateDTOList());

        qrCodeEntity.updatePaymentMethods(updatedPaymentMethods);

        String qrCodeContent = qrCodeEMVService.generateQrCodeContent(qrCodeEntity);

        qrCodeEntity.updateQrCodeContent(qrCodeContent);
        qrCodeEntity.updateRevision();

        qrCodeRepository.save(qrCodeEntity);

        LocationDTO locationDTO = CreateQRCodeLocationMapper.map(
            qrCodeEntity.getLocationId(),
            qrCodeLocationService.generateLocation(qrCodeEntity.getLocationId(), false)
        );

        return new PatchQRCodeOutput(qrCodeIdVO.value(), qrCodeContent, locationDTO);
    }

    private Consumer<String> treatLocationUpdate(String locationId, QRCodeEntity patchQRCodeEntity) {
        qrCodeRepository.findOptionalByLocation(LocationIdVO.from(locationId)).ifPresent(qrCode -> {
            if (!Objects.equals(qrCode.getCreditor(), patchQRCodeEntity.getCreditor())) {
                throw new BusinessRuleException(
                    "Creditor information must be equal to original location whe updating location id");
            }

            qrCode.releaseLocation();

            qrCodeRepository.save(qrCode);
        });

        return patchQRCodeEntity::updateLocationId;
    }

}
