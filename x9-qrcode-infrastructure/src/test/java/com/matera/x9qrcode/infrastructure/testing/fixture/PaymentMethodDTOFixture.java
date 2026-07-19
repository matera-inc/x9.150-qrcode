/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testing.fixture;

import com.matera.x9qrcode.infrastructure.generated.dto.FedNowDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.NetworksSimpleDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentMethodDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.ProtectionTypeEnumDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.RTPDTO;
import net.datafaker.Faker;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class PaymentMethodDTOFixture {

    private final Faker faker;

    public PaymentMethodDTOFixture(Faker faker) {
        this.faker = faker;
    }

    public PaymentMethodDTO paymentMethod() {
        return paymentMethod(faker.number().numberBetween(1000, 100000));
    }

    public PaymentMethodDTO paymentMethod(long amount) {
        return new PaymentMethodDTO()
                .currency("USD")
                .validUntil(generateFutureDateTime())
                .amount(amount)
                .networks(networks());
    }

    public List<PaymentMethodDTO> paymentMethods() {
        long amount = faker.number().numberBetween(1000, 100000);
        return List.of(paymentMethod(amount));
    }

    public List<PaymentMethodDTO> paymentMethods(long amount) {
        return List.of(paymentMethod(amount));
    }

    public NetworksSimpleDTO networks() {
        return new NetworksSimpleDTO()
                .fedNow(fedNow())
                .RTP(rtp());
    }

    public FedNowDTO fedNow() {
        return new FedNowDTO()
                .routingNumber(generateRoutingNumber())
                .accountNumber(generateAccountNumber())
                .protectionType(ProtectionTypeEnumDTO.TOKENIZED);
    }

    public RTPDTO rtp() {
        return new RTPDTO()
                .routingNumber(generateRoutingNumber())
                .accountNumber(generateAccountNumber())
                .protectionType(ProtectionTypeEnumDTO.TOKENIZED);
    }

    private OffsetDateTime generateFutureDateTime() {
        int daysInFuture = faker.number().numberBetween(1, 10);
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(daysInFuture);
    }

    private String generateRoutingNumber() {
        // ABA routing number: 9 digits
        return faker.number().digits(9);
    }

    private String generateAccountNumber() {
        // Bank account number: typically 10-12 digits
        return faker.number().digits(faker.number().numberBetween(10, 13));
    }
}
