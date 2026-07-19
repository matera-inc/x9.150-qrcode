/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.app.service.QRCodeEMVService;
import com.matera.x9qrcode.app.service.QRCodeExternalPayloadService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.createqrcode.CreateQRCodeUseCase;
import com.matera.x9qrcode.app.usecase.decodeemv.DecoveEmvUseCase;
import com.matera.x9qrcode.app.usecase.generatesignature.GenerationSignatureUseCase;
import com.matera.x9qrcode.app.usecase.patchqrcode.PatchQRCodeUseCase;
import com.matera.x9qrcode.app.usecase.paymentnotification.PaymentNotificationQRCodeUseCase;
import com.matera.x9qrcode.app.usecase.retrievepayload.RetrieveQRCodePayloadUseCase;
import com.matera.x9qrcode.app.usecase.retrieveqrcode.RetrieveQRCodeUseCase;
import com.matera.x9qrcode.app.usecase.updatestatus.UpdateQRCodeStatusUseCase;
import com.matera.x9qrcode.app.usecase.validatesignature.ValidateSignatureUseCase;
import com.matera.x9qrcode.domain.generator.IdGenerator;
import com.matera.x9qrcode.domain.service.factory.FormulaFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class UseCaseConfiguration {

    @Bean
    public CreateQRCodeUseCase createQRCodeUseCase(QRCodeRepository qrCodeRepository,
                                                   QRCodeEMVService qrCodeEMVService,
                                                   QRCodeLocationService qrCodeLocationService,
                                                   IdGenerator<UUID> idGenerator) {
        return new CreateQRCodeUseCase(qrCodeRepository, qrCodeEMVService, qrCodeLocationService, idGenerator);
    }

    @Bean
    public RetrieveQRCodePayloadUseCase retrieveQRCodePayloadUseCase(QRCodeRepository qrCodeRepository,
                                                                     QRCodeSignatureService QRCodeSignatureService,
                                                                     FormulaFactory formulaFactory, 
                                                                     QRCodeLocationService qrCodeLocationService) {
        return new RetrieveQRCodePayloadUseCase(qrCodeRepository, QRCodeSignatureService, formulaFactory, qrCodeLocationService);
    }

    @Bean
    public UpdateQRCodeStatusUseCase updateQRCodeStatusUseCase(QRCodeRepository qrCodeRepository) {
        return new UpdateQRCodeStatusUseCase(qrCodeRepository);
    }

    @Bean
    public RetrieveQRCodeUseCase retrieveQRCodeUseCase(QRCodeRepository qrCodeRepository,
                                                       QRCodeEMVService qrCodeEMVService,
                                                       QRCodeLocationService qrCodeLocationService) {
        return new RetrieveQRCodeUseCase(qrCodeRepository, qrCodeEMVService, qrCodeLocationService);
    }

    @Bean
    public ValidateSignatureUseCase validateQRCodeSignatureUseCase(QRCodeSignatureService QRCodeSignatureService) {
        return new ValidateSignatureUseCase(QRCodeSignatureService);
    }

    @Bean
    public GenerationSignatureUseCase generationSignatureUseCase(QRCodeSignatureService QRCodeSignatureService) {
        return new GenerationSignatureUseCase(QRCodeSignatureService);
    }

    @Bean
    public PatchQRCodeUseCase patchQRCodeUseCase(QRCodeRepository qrCodeRepository,
                                                 QRCodeEMVService qrCodeEMVService,
                                                 QRCodeLocationService qrCodeLocationService) {
        return new PatchQRCodeUseCase(qrCodeRepository, qrCodeEMVService, qrCodeLocationService);
    }

    @Bean
    public DecoveEmvUseCase decoveEmvUseCase(QRCodeExternalPayloadService qrCodeExternalPayloadService,
                                             QRCodeEMVService qrCodeEMVService,
                                             QRCodeSignatureService qrCodeSignatureService) {
        return new DecoveEmvUseCase(qrCodeExternalPayloadService, qrCodeEMVService, qrCodeSignatureService);
    }

    @Bean
    public PaymentNotificationQRCodeUseCase paymentNotificationQRCodeUseCase(QRCodeRepository qrCodeRepository) {
        return new PaymentNotificationQRCodeUseCase(qrCodeRepository);
    }

}
