/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testing.fixture;

import com.matera.x9qrcode.infrastructure.generated.dto.BillDTO;
import net.datafaker.Faker;

public final class BillDTOFixture {

    private final Faker faker;
    private final AmountDueDTOFixture amountDueFixture;

    public BillDTOFixture(Faker faker, AmountDueDTOFixture amountDueFixture) {
        this.faker = faker;
        this.amountDueFixture = amountDueFixture;
    }

    public BillDTO bill() {
        return new BillDTO()
                .amountDue(amountDueFixture.amountDue())
                .description(generateBillDescription());
    }

    public BillDTO bill(long amount) {
        return new BillDTO()
                .amountDue(amountDueFixture.amountDue(amount))
                .description(generateBillDescription());
    }

    public BillDTO billWithDescription(String description) {
        return new BillDTO()
                .amountDue(amountDueFixture.amountDue())
                .description(description);
    }

    private String generateBillDescription() {
        String[] serviceTypes = {
            "Electricity service",
            "Water service", 
            "Gas service",
            "Internet service",
            "Phone service",
            "Cable service",
            "Insurance premium"
        };
        String[] months = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        };
        
        String serviceType = serviceTypes[faker.number().numberBetween(0, serviceTypes.length)];
        String month = months[faker.number().numberBetween(0, months.length)];
        int year = faker.number().numberBetween(2024, 2031);
        
        return String.format("%s for %s %d", serviceType, month, year);
    }
}
