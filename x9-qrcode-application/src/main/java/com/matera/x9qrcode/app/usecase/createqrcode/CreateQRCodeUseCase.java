/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.createqrcode;

import com.matera.x9qrcode.app.dto.LocationDTO;
import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.app.service.QRCodeEMVService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.app.usecase.createqrcode.mapper.CreateQRCodeBillMapper;
import com.matera.x9qrcode.app.usecase.createqrcode.mapper.CreateQRCodeCreditorMapper;
import com.matera.x9qrcode.app.usecase.createqrcode.mapper.CreateQRCodeLocationMapper;
import com.matera.x9qrcode.app.usecase.createqrcode.mapper.CreateQRCodePaymentMethodMapper;
import com.matera.x9qrcode.app.usecase.createqrcode.mapper.CreateQRCodePaymentNotificationMapper;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.generator.IdGenerator;
import com.matera.x9qrcode.domain.service.CurrencyMixPolicy;
import com.matera.x9qrcode.domain.vo.CreditorVO;
import com.matera.x9qrcode.domain.vo.LocationIdVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@RequiredArgsConstructor
public class CreateQRCodeUseCase extends UseCase<CreateQRCodeInput, CreateQRCodeOutput> {

    private final QRCodeRepository qrCodeRepository;
    private final QRCodeEMVService qrCodeEMVService;
    private final QRCodeLocationService qrCodeLocationService;
    private final IdGenerator<UUID> idGenerator;
    private final CurrencyMixPolicy currencyMixPolicy;

    @Override
    public CreateQRCodeOutput execute(CreateQRCodeInput input) {
        treatLocationInfo(input);

        QRCodeEntity qrCodeEntity = QRCodeEntity.create(
            idGenerator,
            input.locationId(),
            input.validUntil(),
            CreateQRCodeCreditorMapper.map(input.creditorDTO()),
            CreateQRCodeBillMapper.map(input.billDTO()),
            new UnstructuredVO(input.unstructured()),
            input.additionalInformation(),
            CreateQRCodePaymentNotificationMapper.map(input.paymentNotification()),
            CreateQRCodePaymentMethodMapper.map(input.paymentMethods())
        );

        currencyMixPolicy.validate(collectCurrencies(qrCodeEntity));

        String qrCodeContent = qrCodeEMVService.generateQrCodeContent(qrCodeEntity);

        qrCodeEntity.updateQrCodeContent(qrCodeContent);

        qrCodeRepository.save(qrCodeEntity);

        LocationDTO locationDTO = CreateQRCodeLocationMapper.map(
            qrCodeEntity.getLocationId(),
            qrCodeLocationService.generateLocation(qrCodeEntity.getLocationId(), false)
        );

        return new CreateQRCodeOutput(
            qrCodeEntity.getId().value(),
            qrCodeContent,
            locationDTO
        );
    }

    private void treatLocationInfo(final CreateQRCodeInput input) {
        if (isNull(input.locationId())) {
            return;
        }

        LocationIdVO locationId = LocationIdVO.from(input.locationId());
        qrCodeRepository.findOptionalByLocation(locationId).ifPresent(qrCode -> {
            CreditorVO newCreditor = CreateQRCodeCreditorMapper.map(input.creditorDTO());
            if (!Objects.equals(qrCode.getCreditor(), newCreditor)) {
                throw new BusinessRuleException("Creditor information must not change when locationId is informed");
            }

            qrCode.releaseLocation();

            qrCodeRepository.save(qrCode);
        });
    }

    private static List<String> collectCurrencies(final QRCodeEntity qrCodeEntity) {
        List<String> currencies = new ArrayList<>();

        currencies.add(qrCodeEntity.getBill().amountDue().currencyAmount().currency());

        List<PaymentMethodVO> paymentMethods = qrCodeEntity.getPaymentMethods();
        if (nonNull(paymentMethods)) {
            paymentMethods.forEach(paymentMethod -> currencies.add(paymentMethod.currency()));
        }

        return currencies;
    }

}
