/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testing.fixture;

import com.matera.x9qrcode.infrastructure.generated.dto.AmountDueDTO;
import net.datafaker.Faker;

public final class AmountDueDTOFixture {

    private final Faker faker;

    public AmountDueDTOFixture(Faker faker) {
        this.faker = faker;
    }

    public AmountDueDTO amountDue() {
        return new AmountDueDTO()
                .amount(faker.number().numberBetween(1000L, 100000L))
                .currency("USD");
    }

    public AmountDueDTO amountDue(long amount) {
        return new AmountDueDTO()
                .amount(amount)
                .currency("USD");
    }

    public AmountDueDTO amountDue(long amount, String currency) {
        return new AmountDueDTO()
                .amount(amount)
                .currency(currency);
    }
}
