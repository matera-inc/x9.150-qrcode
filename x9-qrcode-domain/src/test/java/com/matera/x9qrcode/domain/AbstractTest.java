/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain;

import com.matera.x9qrcode.domain.fixture.AddressFixture;
import com.matera.x9qrcode.domain.fixture.BillFixture;
import com.matera.x9qrcode.domain.fixture.CreditorFixture;
import com.matera.x9qrcode.domain.fixture.NetworksFixture;
import com.matera.x9qrcode.domain.fixture.PaymentMethodFixture;
import com.matera.x9qrcode.domain.fixture.PhoneFixture;
import com.matera.x9qrcode.domain.fixture.QRCodeEntityFixture;

import net.datafaker.Faker;

import java.util.Locale;

import static java.util.Locale.ENGLISH;

public abstract class AbstractTest {

    protected final static Faker FAKER;
    protected final static PhoneFixture PHONE_FIXTURE;
    protected final static AddressFixture ADDRESS_FIXTURE;
    protected final static NetworksFixture NETWORKS_FIXTURE;
    protected final static BillFixture BILL_FIXTURE;
    protected final static CreditorFixture CREDITOR_FIXTURE;
    protected final static PaymentMethodFixture PAYMENT_METHOD_FIXTURE;
    protected final static QRCodeEntityFixture QR_CODE_ENTITY_FIXTURE;

    static {
        FAKER = new Faker(new Locale("en-US"));
        PHONE_FIXTURE = new PhoneFixture(FAKER);
        ADDRESS_FIXTURE = new AddressFixture(FAKER);
        NETWORKS_FIXTURE = new NetworksFixture(FAKER);
        BILL_FIXTURE = new BillFixture(FAKER, PHONE_FIXTURE, ADDRESS_FIXTURE);
        CREDITOR_FIXTURE = new CreditorFixture(FAKER, PHONE_FIXTURE, ADDRESS_FIXTURE);
        PAYMENT_METHOD_FIXTURE = new PaymentMethodFixture(FAKER, NETWORKS_FIXTURE);
        QR_CODE_ENTITY_FIXTURE = new QRCodeEntityFixture(FAKER, CREDITOR_FIXTURE, BILL_FIXTURE, PAYMENT_METHOD_FIXTURE);
    }

}
