/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testing.fixture;

import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestInputDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestResponseDTO;

import net.datafaker.Faker;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public final class PaymentRequestDTOFixture {

    private final Faker faker;
    private final CreditorDTOFixture creditorFixture;
    private final BillDTOFixture billFixture;
    private final PaymentMethodDTOFixture paymentMethodFixture;

    public PaymentRequestDTOFixture(Faker faker,
                                    CreditorDTOFixture creditorFixture,
                                    BillDTOFixture billFixture,
                                    PaymentMethodDTOFixture paymentMethodFixture) {
        this.faker = faker;
        this.creditorFixture = creditorFixture;
        this.billFixture = billFixture;
        this.paymentMethodFixture = paymentMethodFixture;
    }

    public PaymentRequestInputDTO paymentRequestInput() {
        long amount = faker.number().numberBetween(1000, 100000);

        return paymentRequestInput(amount);
    }

    public PaymentRequestInputDTO paymentRequestInput(long amount) {
        return new PaymentRequestInputDTO()
            .validUntil(generateFutureDateTime())
            .creditor(creditorFixture.creditor())
            .bill(billFixture.bill(amount))
            .locationId(generateLocationId())
            .paymentMethods(paymentMethodFixture.paymentMethods(amount));
    }

    public PaymentRequestInputDTO paymentRequestInputMinimal() {
        long amount = faker.number().numberBetween(1000, 100000);

        return new PaymentRequestInputDTO()
            .validUntil(generateFutureDateTime())
            .creditor(creditorFixture.creditorWithoutUltimateCreditor())
            .bill(billFixture.bill(amount))
            .paymentMethods(paymentMethodFixture.paymentMethods(amount));
    }

    public PaymentRequestResponseDTO paymentRequestResponse() {
        return new PaymentRequestResponseDTO().id(UUID.randomUUID().toString()).qrCode(generateQRCodeData());
    }

    public PaymentRequestResponseDTO paymentRequestResponse(String id) {
        return new PaymentRequestResponseDTO().id(id).qrCode(generateQRCodeData());
    }

    private OffsetDateTime generateFutureDateTime() {
        int daysInFuture = faker.number().numberBetween(100, 365);
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(daysInFuture);
    }

    private String generateLocationId() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private String generateQRCodeData() {
        return String.format(
            "00020101021226%02d%s5204%s5303840540%d5802US",
            faker.number().numberBetween(20, 50),
            faker.lorem().characters(30, false, true),
            faker.number().digits(4),
            faker.number().numberBetween(1000, 100000)
        );
    }

}
