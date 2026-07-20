/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrievepayload.mapper;

import com.matera.x9qrcode.app.dto.PaymentMethodDTO;
import com.matera.x9qrcode.domain.dto.FormulaResultDTO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BankPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for BUG-001 — an adjustment (discount / late fee) computed against the bill's
 * amount and currency must carry to every payment method in the (pegged) group, pro-rated by ratio
 * so it stays correct across currencies with different minor-unit scales (USD 2 decimals, USDC 6).
 */
class RetrieveQRCodePayloadPaymentMethodMapperTest {

    private static final OffsetDateTime VALID_UNTIL = OffsetDateTime.parse("2026-08-30T23:59:59Z");

    // USD 50.00 -> 5000 minor units (2 decimals); USDC 50.00 -> 50000000 minor units (6 decimals).
    private static final PaymentMethodVO USD_METHOD = new PaymentMethodVO(
        "USD", VALID_UNTIL, new AmountVO(5000L), null,
        new NetworksVO(new BankPaymentAddressVO("121000248", "4455667788"),
            null, null, null, null, null, null, null, null, null, null));

    private static final PaymentMethodVO USDC_METHOD = new PaymentMethodVO(
        "USDC", VALID_UNTIL, new AmountVO(50000000L), null,
        new NetworksVO(null, null, null,
            new CryptoWalletPaymentAddressVO("0x742d35Cc6634C0539Ff82c466ae367A6097dE123"),
            null, null, null, null, null, null, null));

    private long amountOf(List<PaymentMethodDTO> methods, String currency) {
        return methods.stream().filter(m -> currency.equals(m.currency())).findFirst().orElseThrow().amount();
    }

    @Test
    @DisplayName("early-payment discount pro-rates across pegged currencies (USD + USDC)")
    void discountAppliesToEveryPeggedMethod() {
        // 5% off a 5000-minor-unit (USD) bill: adjusted 4750, delta -250. Original = 4750 - (-250) = 5000.
        FormulaResultDTO discount = new FormulaResultDTO(4750L, -250L,
            "5% off when paid early", "USD", VALID_UNTIL);

        List<PaymentMethodDTO> methods =
            RetrieveQRCodePayloadPaymentMethodMapper.map(List.of(USD_METHOD, USDC_METHOD), discount);

        assertEquals(4750L, amountOf(methods, "USD"), "USD method should reflect the 5% discount");
        assertEquals(47500000L, amountOf(methods, "USDC"),
            "USDC method should reflect the SAME 5% discount, pro-rated to its own minor-unit scale");
    }

    @Test
    @DisplayName("late fee pro-rates across pegged currencies as a positive adjustment")
    void lateFeeAppliesToEveryPeggedMethod() {
        // $2.50 late fee on a 5000-minor-unit (USD) bill: adjusted 5250, delta +250. Original = 5000.
        FormulaResultDTO lateFee = new FormulaResultDTO(5250L, 250L,
            "late fee", "USD", VALID_UNTIL);

        List<PaymentMethodDTO> methods =
            RetrieveQRCodePayloadPaymentMethodMapper.map(List.of(USD_METHOD, USDC_METHOD), lateFee);

        assertEquals(5250L, amountOf(methods, "USD"));
        assertEquals(52500000L, amountOf(methods, "USDC"));
    }

    @Test
    @DisplayName("no adjustment leaves every method amount untouched")
    void noFormulaResultLeavesAmountsUntouched() {
        List<PaymentMethodDTO> methods =
            RetrieveQRCodePayloadPaymentMethodMapper.map(List.of(USD_METHOD, USDC_METHOD), null);

        assertEquals(5000L, amountOf(methods, "USD"));
        assertEquals(50000000L, amountOf(methods, "USDC"));
    }
}
