/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.matera.x9qrcode.app.service.JwkService;
import com.matera.x9qrcode.app.service.PEMService;
import com.matera.x9qrcode.app.service.PrivateKeyRetriever;
import com.matera.x9qrcode.app.service.QRCodeEMVService;
import com.matera.x9qrcode.app.service.QRCodeExternalJwkService;
import com.matera.x9qrcode.app.service.QRCodeExternalPayloadService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.service.TruststoreRetriever;
import com.matera.x9qrcode.domain.service.FixedDiscountLateFeeLinearInterestFormulaService;
import com.matera.x9qrcode.domain.service.FormulaService;
import com.matera.x9qrcode.domain.service.factory.FormulaFactory;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;
import com.matera.x9qrcode.infrastructure.service.DefaultQRCodeLocationService;
import com.matera.x9qrcode.infrastructure.service.JwsQRCodeSignatureService;
import com.matera.x9qrcode.infrastructure.service.thirdparty.emv.MateraAdoptQRCodeEMVService;
import com.matera.x9qrcode.infrastructure.service.thirdparty.jwk.RestClientExternalJwkService;
import com.matera.x9qrcode.infrastructure.service.thirdparty.keystore.TrustManagersFacadeBean;
import com.matera.x9qrcode.infrastructure.service.thirdparty.payload.RestClientQRCodeExternalPayloadService;
import com.matera.x9qrcode.infrastructure.service.thirdparty.pem.PEMServiceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.jwk.JWK;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.PropertyResolver;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class ServiceConfiguration {

    public static final String FIXED_DISCOUNT_FORMULA_SERVICE = "fixedDiscountLateFeeLinearInterestFormulaService";

    @Bean
    public PEMService pemService(X9Properties x9Properties) {
        return new PEMServiceImpl(x9Properties);
    }

    @Bean
    public QRCodeSignatureService qrCodeSignatureGateway(X9Properties x9Properties,
                                                         ObjectMapper objectMapper,
                                                         QRCodeLocationService qrCodeLocationService,
                                                         JwkService<JWK, JWSSigner> jwkService,
                                                         PrivateKeyRetriever privateKeyRetriever,
                                                         TruststoreRetriever truststoreRetriever,
                                                         QRCodeExternalJwkService qrCodeExternalJwkService,
                                                         TrustManagersFacadeBean trustManagersFacadeBean,
                                                         PEMService pemService,
                                                         QRCodeEMVService qrCodeEMVService) {
        return new JwsQRCodeSignatureService(x9Properties, objectMapper, qrCodeLocationService, jwkService, privateKeyRetriever,
            qrCodeExternalJwkService, truststoreRetriever, trustManagersFacadeBean, pemService, qrCodeEMVService);
    }

    @Bean
    public QRCodeLocationService qrCodeLocationGateway(X9Properties x9Properties, PropertyResolver propertyResolver) {
        return new DefaultQRCodeLocationService(x9Properties, propertyResolver);
    }

    @Bean
    public QRCodeEMVService qrCodeEMVGateway(X9Properties x9Properties,
                                             QRCodeLocationService qrCodeLocationService) {
        return new MateraAdoptQRCodeEMVService(x9Properties, qrCodeLocationService);
    }

    @Bean
    public QRCodeExternalPayloadService externalPayloadGateway(RestClient restClient,
                                                               QRCodeLocationService qrCodeLocationService,
                                                               QRCodeSignatureService qrCodeSignatureService) {
        return new RestClientQRCodeExternalPayloadService(restClient, qrCodeLocationService, qrCodeSignatureService);
    }

    @Bean
    public QRCodeExternalJwkService externalJwkGateway(RestClient restClient,
                                                       QRCodeLocationService qrCodeLocationService,
                                                       PEMService pemService) {
        return new RestClientExternalJwkService(restClient, qrCodeLocationService, pemService);
    }

    @Bean
    @DependsOn(FIXED_DISCOUNT_FORMULA_SERVICE)
    public FormulaFactory formulaFactory(ApplicationContext applicationContext) {
        Map<FormulaEnum, FormulaService> formulaServiceMap = new HashMap<>();

        Map<String, FormulaService> beans = applicationContext.getBeansOfType(FormulaService.class);
        beans.values()
            .forEach(formulaService -> formulaServiceMap.put(formulaService.getFormulaType(), formulaService));

        return new FormulaFactory(formulaServiceMap);
    }

    @Bean
    @Qualifier(FIXED_DISCOUNT_FORMULA_SERVICE)
    public FixedDiscountLateFeeLinearInterestFormulaService fixedDiscountLateFeeLinearInterestFormulaService() {
        return new FixedDiscountLateFeeLinearInterestFormulaService();
    }

}
