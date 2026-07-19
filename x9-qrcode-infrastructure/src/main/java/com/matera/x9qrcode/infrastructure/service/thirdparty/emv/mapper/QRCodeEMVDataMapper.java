/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.emv.mapper;

import com.emv.qrcode.core.model.mpm.TagLengthString;
import com.emv.qrcode.model.mpm.MerchantAccountInformationReservedAdditional;
import com.emv.qrcode.model.mpm.MerchantAccountInformationTemplate;
import com.emv.qrcode.model.mpm.MerchantPresentedMode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class QRCodeEMVDataMapper {

    public static MerchantPresentedMode map(String pointInitiationMethodValue,
                                            String payloadFormatIndicator,
                                            String maiTagId,
                                            String maiGui,
                                            String urlId,
                                            URI location,
                                            String merchantCategoryCode,
                                            String country,
                                            String creditorName,
                                            String creditorCity, String transactionCurrency,
                                            String transactionAmount) {
        MerchantPresentedMode merchantPresentedMode = new MerchantPresentedMode();

        MerchantAccountInformationTemplate merchantAccountInformation =
            new MerchantAccountInformationTemplate(maiTagId, buildMAIData(maiGui, urlId, location.toString()));

        merchantPresentedMode.setPayloadFormatIndicator(payloadFormatIndicator);
        merchantPresentedMode.setPointOfInitiationMethod(pointInitiationMethodValue);
        merchantPresentedMode.addMerchantAccountInformation(merchantAccountInformation);
        merchantPresentedMode.setMerchantCategoryCode(merchantCategoryCode);
        merchantPresentedMode.setTransactionCurrency(transactionCurrency);
        merchantPresentedMode.setTransactionAmount(transactionAmount);
        merchantPresentedMode.setCountryCode(country);
        merchantPresentedMode.setMerchantName(StringUtils.left(creditorName, 25));
        merchantPresentedMode.setMerchantCity(StringUtils.left(creditorCity, 15));

        return merchantPresentedMode;
    }

    private static MerchantAccountInformationReservedAdditional buildMAIData(String maiGui,
                                                                             String urlId,
                                                                             String location) {
        MerchantAccountInformationReservedAdditional maiData = new MerchantAccountInformationReservedAdditional();

        maiData.setGloballyUniqueIdentifier(maiGui);
        maiData.addPaymentNetworkSpecific(new TagLengthString(urlId, location));

        return maiData;
    }

}
