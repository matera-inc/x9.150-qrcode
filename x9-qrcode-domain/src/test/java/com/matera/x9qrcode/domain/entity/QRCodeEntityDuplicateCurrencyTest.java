/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.SolanaPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QRCodeEntityDuplicateCurrencyTest extends AbstractTest {

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final OffsetDateTime VALID_UNTIL = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);

    private static QRCodeEntity create(String... currencies) {
        BillVO bill = new BillVO(new DescriptionVO("duplicates"), null, null, TipVO.noTip(),
            new AmountDueVO(new CurrencyAmountVO(1_000L, "USD"), null), PaymentTimingEnum.IMMEDIATE);
        List<PaymentMethodVO> methods = Arrays.stream(currencies)
            .map(currency -> new PaymentMethodVO(currency, VALID_UNTIL, new AmountVO(1_000L), null,
                new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, null), Map.of())))
            .toList();

        return QRCodeEntity.create(UUID::randomUUID, null, VALID_UNTIL, CREDITOR_FIXTURE.creditor(), bill,
            new UnstructuredVO("duplicates"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null), methods);
    }

    /**
     * <b>X9-CUR-080</b> — of several repeated currencies, the refusal names the first one in request
     * order: the first, by first appearance, that appears again.
     *
     * <p><b>Source:</b> Ours. The duplicate-currency rule itself is ours; which culprit it names is
     * the owner's decision of 2026-11-15 (Story 9.4), shared by the Java and Go builds.
     *
     * <p><b>Why:</b> The currencies were counted in a HashMap, so the one named depended on their
     * hash codes: USD and CAD, which share a bucket, named CAD for {@code USD, CAD, CAD, USD}. A
     * biller fixing the first thing they wrote should be told about that one.
     */
    @ParameterizedTest(name = "{0} names {1}")
    @CsvSource(delimiter = ';', value = {
        "USDC,USD,USDC,USD; USDC",
        "USD,CAD,CAD,USD; USD",
        "FRNT,USD,USDC,USD; USD",
    })
    void theFirstRepeatedCurrencyInRequestOrderIsNamed(String currencies, String named) {
        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
            () -> create(currencies.split(",")));

        assertEquals("paymentMethods", refused.field());
        assertEquals("must not contain duplicated currency: " + named, refused.getMessage());
    }

}
