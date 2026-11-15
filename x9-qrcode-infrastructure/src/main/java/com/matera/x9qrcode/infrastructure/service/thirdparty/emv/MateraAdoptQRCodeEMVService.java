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

import com.emv.qrcode.model.mpm.MerchantPresentedMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    /**
     * The location URL of the scanned string's org.x9 template, or null when it carries none.
     *
     * <p>Read by hand ({@link EmvReader}), not by the EMV library: the library read a bad, missing or
     * non-hex CRC and a truncated string as good, and let other breakages escape as raw exceptions.
     * A malformed string is refused here, before anything is fetched from the host it names
     * (ADR-0027, EMV: malformed EMV is refused, never tolerated).
     *
     * @throws MalformedEmvException when the string is not well-formed EMV
     */
    @Override
    public String extractPayloadUrl(String emv) throws ServiceException {
        String payloadUrl = EmvReader.readPayloadUrl(emv, x9Properties.getEmv().getMaiGui(), x9Properties.getEmv().getUrlId())
            .orElse(null);

        log.info("Extracted payload URL: {}", payloadUrl);

        return payloadUrl;
    }

}
