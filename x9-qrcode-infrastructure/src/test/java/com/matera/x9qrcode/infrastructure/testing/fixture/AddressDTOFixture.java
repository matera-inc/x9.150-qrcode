/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testing.fixture;

import com.matera.x9qrcode.infrastructure.generated.dto.AddressDTO;
import net.datafaker.Faker;

public final class AddressDTOFixture {

    private final Faker faker;

    public AddressDTOFixture(Faker faker) {
        this.faker = faker;
    }

    public AddressDTO address() {
        return new AddressDTO()
                .line1(faker.address().streetAddress())
                .line2(faker.address().secondaryAddress())
                .city(faker.address().city())
                .state(faker.address().stateAbbr())
                .postalCode(faker.address().zipCode())
                .country("US");
    }

    public AddressDTO addressWithoutLine2() {
        return new AddressDTO()
                .line1(faker.address().streetAddress())
                .city(faker.address().city())
                .state(faker.address().stateAbbr())
                .postalCode(faker.address().zipCode())
                .country("US");
    }
}
