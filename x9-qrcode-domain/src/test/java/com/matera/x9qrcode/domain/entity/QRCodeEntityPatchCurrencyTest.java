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

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A patch names every currency the QR Code offers, or it is refused.
 *
 * <p>Our rule, not the standard's. Both halves replace a silent failure: an unknown currency was
 * discarded before the entity saw it and answered 200, and an omitted currency kept its old amount
 * while the others moved — one debt with two prices depending on how the payer settled it.
 */
class QRCodeEntityPatchCurrencyTest extends AbstractTest {

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final OffsetDateTime VALID_UNTIL = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);

    private QRCodeEntity qrCodeOffering(String... currencies) {
        BillVO bill = new BillVO(
            new DescriptionVO("two prices, one debt"), null, null, TipVO.noTip(),
            new AmountDueVO(new CurrencyAmountVO(1_000L, currencies[0]), null),
            PaymentTimingEnum.IMMEDIATE);

        List<PaymentMethodVO> methods = List.of(currencies).stream()
            .map(currency -> new PaymentMethodVO(
                currency, VALID_UNTIL, new AmountVO(1_000L), null,
                new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, null), Map.of())))
            .toList();

        return QRCodeEntity.create(
            UUID::randomUUID, null, VALID_UNTIL, CREDITOR_FIXTURE.creditor(), bill,
            new UnstructuredVO("patch currency test"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null), methods);
    }

    /**
     * <b>X9-PATCH-001</b> — naming every currency the QR Code offers is accepted.
     *
     * <p><b>Source:</b> Ours. X9.150 describes the payload, not how a payment request is edited. ADR-0018.
     *
     * <p><b>Why:</b> The supported edit: reducing a bill after a partial payment, with every currency restated.
     * The acceptance case the two refusals below are judged against.
     */
    @Test
    void namingEveryCurrencyOnTheQRCodeIsAccepted() {
        assertDoesNotThrow(() ->
            qrCodeOffering("USD", "USDC").requirePaymentMethodCurrencies(List.of("USD", "USDC")));
    }

    /**
     * <b>X9-PATCH-002</b> — the order currencies are named in does not matter.
     *
     * <p><b>Source:</b> Ours. ADR-0018 — the rule is about the SET of currencies, not a sequence.
     *
     * <p><b>Why:</b> A caller building the list from a map or a set has no stable order. Making order matter
     * would turn a correct patch into an intermittent 400 depending on iteration order.
     */
    @Test
    void theOrderTheyAreNamedInDoesNotMatter() {
        assertDoesNotThrow(() ->
            qrCodeOffering("USD", "USDC").requirePaymentMethodCurrencies(List.of("USDC", "USD")));
    }

    /**
     * <b>X9-PATCH-003</b> — a currency the QR Code does not offer is refused, by name.
     *
     * <p><b>Source:</b> Ours. ADR-0018.
     *
     * <p><b>Why:</b> Payment methods are matched and merged BY CURRENCY, so an unmatched entry matched nothing
     * and was dropped by the mapper before the entity saw it — answered 200, with the amount simply
     * absent afterwards. The entity had the right rule all along and could not reach it.
     */
    @Test
    void aCurrencyTheQRCodeDoesNotOfferIsRefusedByName() {
        // Used to be dropped by the mapper and answered 200, with the amount simply absent after.
        BusinessRuleException exception = assertThrows(BusinessRuleException.class, () ->
            qrCodeOffering("USD").requirePaymentMethodCurrencies(List.of("USD", "EUR")));

        assertTrue(exception.field().contains("paymentMethods"), exception.field());
        assertTrue(exception.getMessage().contains("EUR"),
            "the reason must name the currency that is not here: " + exception.getMessage());
    }

    /**
     * <b>X9-PATCH-004</b> — a currency left out of the patch is refused, by name.
     *
     * <p><b>Source:</b> Ours. ADR-0018.
     *
     * <p><b>Why:</b> Omitting one left it at its old amount while the others moved, so one debt ended up with two
     * prices depending on which rail the payer chose to settle with. This is the half with teeth.
     */
    @Test
    void aCurrencyLeftOutIsRefusedByName() {
        // Used to leave USDC at its old amount while USD moved.
        BusinessRuleException exception = assertThrows(BusinessRuleException.class, () ->
            qrCodeOffering("USD", "USDC").requirePaymentMethodCurrencies(List.of("USD")));

        assertTrue(exception.field().contains("paymentMethods"), exception.field());
        assertTrue(exception.getMessage().contains("USDC"),
            "the reason must name the currency left out: " + exception.getMessage());
    }

    /**
     * <b>X9-PATCH-005</b> — a patch naming only an unknown currency is refused.
     *
     * <p><b>Source:</b> Ours. ADR-0018.
     *
     * <p><b>Why:</b> The mapper used to substitute the QR Code's OWN current methods when nothing matched, so the
     * patch became a no-op that still incremented the revision — moving the ETag, so a conditional
     * client saw every sign of success.
     */
    @Test
    void anEntirelyUnknownCurrencyOnItsOwnIsRefused() {
        assertThrows(BusinessRuleException.class, () ->
            qrCodeOffering("USD").requirePaymentMethodCurrencies(List.of("USDC")));
    }

    /**
     * <b>X9-PATCH-006</b> — naming no currency at all is not this rule's business.
     *
     * <p><b>Source:</b> Mechanism. The contract makes paymentMethods required, so this is unreachable over HTTP.
     *
     * <p><b>Why:</b> Guarded so a caller reaching the use case directly gets the contract's error rather than a
     * second, differently-worded opinion from this rule.
     */
    @Test
    void namingNoCurrencyAtAllIsNotThisRulesBusiness() {
        // The contract makes paymentMethods required, so this is unreachable over HTTP. Guarded
        // here so a caller reaching the use case directly gets the contract's error, not ours.
        assertDoesNotThrow(() -> qrCodeOffering("USD").requirePaymentMethodCurrencies(List.of()));
        assertDoesNotThrow(() -> qrCodeOffering("USD").requirePaymentMethodCurrencies(null));
    }
}
