/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testing.fixture;

import net.datafaker.Faker;

public final class DTOFixtures {

    private final AddressDTOFixture addressFixture;
    private final AmountDueDTOFixture amountDueFixture;
    private final CreditorDTOFixture creditorFixture;
    private final BillDTOFixture billFixture;
    private final PaymentMethodDTOFixture paymentMethodFixture;
    private final PaymentRequestDTOFixture paymentRequestFixture;

    private DTOFixtures(Faker faker) {
        this.addressFixture = new AddressDTOFixture(faker);
        this.amountDueFixture = new AmountDueDTOFixture(faker);
        this.creditorFixture = new CreditorDTOFixture(faker, addressFixture);
        this.billFixture = new BillDTOFixture(faker, amountDueFixture);
        this.paymentMethodFixture = new PaymentMethodDTOFixture(faker);
        this.paymentRequestFixture = new PaymentRequestDTOFixture(faker, creditorFixture, billFixture, paymentMethodFixture);
    }

    public static DTOFixtures create() {
        return new DTOFixtures(new Faker());
    }

    public AddressDTOFixture address() {
        return addressFixture;
    }

    public AmountDueDTOFixture amountDue() {
        return amountDueFixture;
    }

    public CreditorDTOFixture creditor() {
        return creditorFixture;
    }

    public BillDTOFixture bill() {
        return billFixture;
    }

    public PaymentMethodDTOFixture paymentMethod() {
        return paymentMethodFixture;
    }

    public PaymentRequestDTOFixture paymentRequest() {
        return paymentRequestFixture;
    }

}
