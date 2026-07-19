/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testing.fixture;

import com.matera.x9qrcode.infrastructure.generated.dto.CreditorDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.CreditorUltimateCreditorAccountDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.CreditorUltimateCreditorDTO;
import net.datafaker.Faker;

public final class CreditorDTOFixture {

    private final Faker faker;
    private final AddressDTOFixture addressFixture;

    public CreditorDTOFixture(Faker faker, AddressDTOFixture addressFixture) {
        this.faker = faker;
        this.addressFixture = addressFixture;
    }

    public CreditorDTO creditor() {
        return new CreditorDTO()
                .name(faker.company().name())
                .phone(generateUsPhoneNumber())
                .email(faker.internet().emailAddress())
                .address(addressFixture.address())
                .ultimateCreditor(ultimateCreditor())
                .MCC(faker.number().digits(4));
    }

    public CreditorDTO creditorWithoutUltimateCreditor() {
        return new CreditorDTO()
                .name(faker.company().name())
                .phone(generateUsPhoneNumber())
                .email(faker.internet().emailAddress())
                .address(addressFixture.address())
                .MCC(faker.number().digits(4));
    }

    public CreditorUltimateCreditorDTO ultimateCreditor() {
        return new CreditorUltimateCreditorDTO()
                .name(faker.name().fullName())
                .phone(generateUsPhoneNumber())
                .email(faker.internet().emailAddress())
                .account(getUltimateCreditorAccount())
                .address(addressFixture.address());
    }

    private CreditorUltimateCreditorAccountDTO getUltimateCreditorAccount() {
        return new CreditorUltimateCreditorAccountDTO()
            .id(faker.idNumber().valid())
            .schemaName(faker.number().digit());
    }

    private String generateUsPhoneNumber() {
        // Format: +1AAABBBCCCC where AAA is area code (2-9 first digit), BBB is exchange (2-9 first digit)
        int areaCode = faker.number().numberBetween(200, 999);
        int exchange = faker.number().numberBetween(200, 999);
        int subscriber = faker.number().numberBetween(1000, 9999);
        return String.format("+1%d%d%d", areaCode, exchange, subscriber);
    }
}
