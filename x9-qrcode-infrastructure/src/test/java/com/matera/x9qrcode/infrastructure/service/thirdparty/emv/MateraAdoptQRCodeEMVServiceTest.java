/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.emv;

import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

class MateraAdoptQRCodeEMVServiceTest {

    private MateraAdoptQRCodeEMVService materaAdoptQRCodeEMVService = new MateraAdoptQRCodeEMVService(new X9Properties(), null);

    static Stream<Arguments> inputEmvToDecode() {
        return Stream.of(
            Arguments.of("00020101021226760006org.x90162localhost:8080/pub/api/v1/loc/0198E20E29F146FA992E414FD762CF4C520449005303986540105802US5925California Electric Compa6011Los Angeles6304CB7E",
                "localhost:8080/pub/api/v1/loc/0198E20E29F146FA992E414FD762CF4C"),
            Arguments.of("00020101021126480014br.gov.bcb.pix0114069903530001620308133708355204000053039865406100.005802BR5905Teste6009SAO PAULO62070503***6304B3AB",
                null)
        );
    }

    @ParameterizedTest
    @MethodSource("inputEmvToDecode")
    void testExtractPayloadUrl(String emv, String expectedUrl) {
        String payloadUrl = materaAdoptQRCodeEMVService.extractPayloadUrl(emv);
        Assertions.assertEquals(expectedUrl, payloadUrl);
    }

}