/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.emv;

import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.app.service.QRCodeEMVService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.vo.AddressVO;
import com.matera.x9qrcode.domain.vo.CreditorVO;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;
import com.matera.x9qrcode.infrastructure.service.thirdparty.emv.mapper.QRCodeEMVDataMapper;

import com.emv.qrcode.core.exception.PresentedModeException;
import com.emv.qrcode.core.model.TLV;
import com.emv.qrcode.decoder.mpm.DecoderMpm;
import com.emv.qrcode.model.mpm.MerchantAccountInformation;
import com.emv.qrcode.model.mpm.MerchantAccountInformationReservedAdditional;
import com.emv.qrcode.model.mpm.MerchantAccountInformationTemplate;
import com.emv.qrcode.model.mpm.MerchantPresentedMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
public class MateraAdoptQRCodeEMVService implements QRCodeEMVService {

    private final X9Properties x9Properties;
    private final QRCodeLocationService qrCodeLocationService;

    @Override
    public String generateQrCodeContent(QRCodeEntity qrCodeEntity) throws ServiceException {
        CreditorVO creditor = qrCodeEntity.getCreditor();
        AddressVO creditorAddress = creditor.address();

        try {
            MerchantPresentedMode merchantPresentedMode =
                QRCodeEMVDataMapper.map(
                    x9Properties.getEmv().getPointInitiationMethodValue(),
                    x9Properties.getEmv().getPayloadFormatIndicator(),
                    x9Properties.getEmv().getMaiTagId(),
                    x9Properties.getEmv().getMaiGui(),
                    x9Properties.getEmv().getUrlId(),
                    qrCodeLocationService.generateLocation(qrCodeEntity.getLocationId(), true), 
                    creditor.merchantCategoryCode().value(),
                    creditorAddress.country(),
                    creditor.name().value(),
                    creditorAddress.city(),
                    x9Properties.getEmv().getTransactionCurrency(),
                    x9Properties.getEmv().getTransactionAmount());

            String qrCodeEmv = merchantPresentedMode.toString();

            log.info("Generated EMV QR Code: {}", qrCodeEmv);

            return qrCodeEmv;
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    @Override
    public String extractPayloadUrl(String emv) throws ServiceException {
        String payloadUrl = decodeEMV(emv).getMerchantAccountInformation().values().stream()
            .flatMap(template -> extractMaiData(x9Properties.getEmv().getMaiGui()).apply(template).stream())
            .flatMap(mai -> extractPayloadUri(x9Properties.getEmv().getUrlId()).apply(mai).stream())
            .findFirst().orElse(null);

        log.info("Extracted payload URL: {}", payloadUrl);

        return payloadUrl;
    }

    private static Function<MerchantAccountInformationTemplate, Optional<MerchantAccountInformationReservedAdditional>> extractMaiData(String maiGui) {
        return template -> {
            MerchantAccountInformation mai = template.getValue();
            if (nonNull(mai) && mai instanceof MerchantAccountInformationReservedAdditional reservedAdditional) {
                if (Objects.equals(maiGui, reservedAdditional.getGloballyUniqueIdentifier().getValue())) {
                    return Optional.of(reservedAdditional);
                }
            }
            return Optional.empty();
        };
    }

    private static Function<MerchantAccountInformationReservedAdditional, Optional<String>> extractPayloadUri(String urlId) {
        return mai -> Optional.ofNullable(mai.getPaymentNetworkSpecific().get(urlId)).map(TLV :: getValue);
    }

    private static MerchantPresentedMode decodeEMV(String emv) {
        try {
            return DecoderMpm.decode(emv, MerchantPresentedMode.class);
        } catch (PresentedModeException e) {
            throw new ServiceException("Could not decode EMV. Details: %s".formatted(e.getMessage()));
        }
    }

}
