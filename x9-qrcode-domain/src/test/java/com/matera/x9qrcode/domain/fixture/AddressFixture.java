/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.fixture;

import com.matera.x9qrcode.domain.vo.AddressVO;

import net.datafaker.Faker;

public final class AddressFixture {

    private final Faker faker;

    public AddressFixture(Faker faker) {
        this.faker = faker;
    }

    public AddressVO address() {
        return new AddressVO(
            faker.address().streetAddress(),
            faker.address().streetAddress(),
            faker.address().city(),
            faker.address().state(),
            faker.address().zipCode(),
            faker.address().countryCode()
        );
    }

}
