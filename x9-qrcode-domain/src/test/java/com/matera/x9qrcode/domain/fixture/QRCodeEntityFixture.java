/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.fixture;

import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.generator.IdGenerator;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.utils.UUIDUtils;
import com.matera.x9qrcode.domain.vo.PaymentDetailsVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;

import net.datafaker.Faker;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QRCodeEntityFixture {

    private static final String QRCODE_EMV =
        "00020101021226800006org.x92566x9-150.example.com/api/v1/pay/0196cb82-afab-46f1-bf8d-fff2f9c8a53e5204490053039865406150.005802US5907X9 Corp6004test63046195";

    private final Faker faker;
    private final CreditorFixture creditorFixture;
    private final BillFixture billFixture;
    private final PaymentMethodFixture paymentMethodFixture;
    private final IdGenerator<UUID> uuidGenerator;

    public QRCodeEntityFixture(Faker faker,
                               CreditorFixture creditorFixture,
                               BillFixture billFixture,
                               PaymentMethodFixture paymentMethodFixture) {
        this.faker = faker;
        this.creditorFixture = creditorFixture;
        this.billFixture = billFixture;
        this.paymentMethodFixture = paymentMethodFixture;
        this.uuidGenerator = UUID::randomUUID;
    }

    public QRCodeEntity qrCodeEntity() {
        return QRCodeEntity.create(
            uuidGenerator,
            location(),
            validUntil(),
            creditorFixture.creditor(),
            billFixture.bill(),
            new UnstructuredVO(faker.lorem().characters(10, 100)),
            Map.of(faker.lorem().word(), faker.lorem().characters(10, 100)),
            new PaymentNotificationVO(NotificationKindEnum.EXTERNAL, URI.create(faker.internet().url()), null),
            List.of(
                paymentMethodFixture.paymentMethodWithUSD(),
                paymentMethodFixture.paymentMethodWithUSDC(),
                paymentMethodFixture.paymentMethodWithCAD()
            )
        );
    }

    public IdGenerator<UUID> uuidGenerator() {
        return uuidGenerator;
    }

    public OffsetDateTime validUntil() {
        return DateTimeUtils.nowUTC().plusDays(10);
    }

    public String location() {
        return UUIDUtils.toShortenString(UUID.randomUUID());
    }

    public String emv() {
        return QRCODE_EMV;
    }

    public PaymentDetailsVO paymentDetails() {
        return new PaymentDetailsVO(faker.lorem().characters(36), faker.random().nextEnum(NetworkEnum.class));
    }

}
