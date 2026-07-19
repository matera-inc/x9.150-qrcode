/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.fixture;

import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.AmountRangeVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.EditableAmountVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;

import net.datafaker.Faker;

public final class PaymentMethodFixture {

    private final Faker faker;
    private final NetworksFixture networksFixture;

    public PaymentMethodFixture(Faker faker, NetworksFixture networksFixture) {
        this.faker = faker;
        this.networksFixture = networksFixture;
    }

    public PaymentMethodVO paymentMethodWithUSD() {
        return createPaymentMethodVO("USD");
    }

    public PaymentMethodVO paymentMethodWithUSDC() {
        return createPaymentMethodVO("USDC");
    }

    public PaymentMethodVO paymentMethodWithCAD() {
        return createPaymentMethodVO("CAD");
    }

    private PaymentMethodVO createPaymentMethodVO(String currencyEnum) {
        return new PaymentMethodVO(
            currencyEnum,
            DateTimeUtils.nowUTC().plusDays(1),
            new AmountVO(faker.number().numberBetween(1000L, 10000000L)),
            new EditableAmountVO(
                new AmountRangeVO(faker.number().numberBetween(1000L, 10000L), faker.number().numberBetween(100000L, 1000000L))),
            networksFixture.networks());
    }

}
