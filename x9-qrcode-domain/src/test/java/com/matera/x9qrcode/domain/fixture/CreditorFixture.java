/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.fixture;

import com.matera.x9qrcode.domain.vo.AccountVO;
import com.matera.x9qrcode.domain.vo.CreditorVO;
import com.matera.x9qrcode.domain.vo.UltimateCreditorVO;

import net.datafaker.Faker;

public final class CreditorFixture {

    private final Faker faker;
    private final PhoneFixture phoneFixture;
    private final AddressFixture addressFixture;

    public CreditorFixture(Faker faker, PhoneFixture phoneFixture, AddressFixture addressFixture) {
        this.faker = faker;
        this.phoneFixture = phoneFixture;
        this.addressFixture = addressFixture;
    }

    public CreditorVO creditor() {
        return new CreditorVO(
            faker.name().fullName(),
            phoneFixture.phone(),
            faker.internet().emailAddress(),
            addressFixture.address(),
            ultimateCreditor(),
            faker.number().digits(4)
        );
    }

    public CreditorVO creditorWithoutUltimateCreditor() {
        return new CreditorVO(
            faker.name().fullName(),
            phoneFixture.phone(),
            faker.internet().emailAddress(),
            addressFixture.address(),
            null,
            faker.number().digits(4)
        );
    }

    public UltimateCreditorVO ultimateCreditor() {
        return new UltimateCreditorVO(
            new AccountVO(faker.idNumber().valid(), faker.country().countryCode2()),
            faker.name().fullName(),
            phoneFixture.phone(),
            faker.internet().emailAddress(),
            addressFixture.address()
        );
    }

}