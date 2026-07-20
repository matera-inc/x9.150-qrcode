/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrievepayload.mapper;

import com.matera.x9qrcode.app.dto.AmountRangeDTO;
import com.matera.x9qrcode.app.dto.BankPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CryptoWalletPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CurrencyEditableDTO;
import com.matera.x9qrcode.app.dto.NetworksDTO;
import com.matera.x9qrcode.app.dto.PaymentMethodDTO;
import com.matera.x9qrcode.domain.dto.FormulaResultDTO;
import com.matera.x9qrcode.domain.vo.AmountRangeVO;
import com.matera.x9qrcode.domain.vo.BankPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.EditableAmountVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RetrieveQRCodePayloadPaymentMethodMapper {

    public static List<PaymentMethodDTO> map(List<PaymentMethodVO> output, FormulaResultDTO formulaResult) {
        if (isNull(output)) {
            return null;
        }

        return output.stream().map(paymentMethod -> new PaymentMethodDTO(
            paymentMethod.currency(),
            paymentMethod.validUntil(),
            applyAdjustment(paymentMethod.amount().value(), formulaResult),
            buildEditableAmount(paymentMethod.editable()),
            buildNetworks(paymentMethod.networks())
        )).toList();
    }

    /**
     * Carries the bill's adjustment (discount or late fee) to a payment method.
     *
     * <p>The adjustment is computed once against the bill's amount and currency. Every payment
     * method on a QR is guaranteed (by the currency-mix policy) to belong to the same pegged group,
     * so we apply the same <em>proportional</em> adjustment to each method. Pro-rating by ratio —
     * rather than subtracting the raw minor-unit delta — keeps the result correct across currencies
     * whose minor-unit scale differs (e.g. USD has 2 decimal places, USDC has 6).
     *
     * <p>TODO: when non-pegged currencies are supported, convert the discounted amount to each
     * method's currency via the dynamic currency converter instead of pro-rating by ratio.
     */
    private static Long applyAdjustment(Long methodAmount, FormulaResultDTO formulaResult) {
        if (isNull(formulaResult)) {
            return methodAmount;
        }

        long originalBillAmount = formulaResult.amount() - formulaResult.adjustmentAmount();

        if (originalBillAmount <= 0) {
            return methodAmount;
        }

        return BigDecimal.valueOf(methodAmount)
            .multiply(BigDecimal.valueOf(formulaResult.amount()))
            .divide(BigDecimal.valueOf(originalBillAmount), 0, RoundingMode.HALF_UP)
            .longValueExact();
    }

    private static CurrencyEditableDTO buildEditableAmount(EditableAmountVO output) {
        if (isNull(output)) {
            return null;
        }

        return new CurrencyEditableDTO(
            buildEditableAmountRange(output.range())
        );
    }

    private static AmountRangeDTO buildEditableAmountRange(AmountRangeVO output) {
        if (isNull(output)) {
            return null;
        }

        return new AmountRangeDTO(
            output.minAmount(),
            output.maxAmount()
        );
    }

    private static NetworksDTO buildNetworks(NetworksVO output) {
        return NetworksDTO.builder()
            .fedNow(buildBankPaymentAddress(output.fedNow()))
            .ach(buildBankPaymentAddress(output.ach()))
            .rtp(buildBankPaymentAddress(output.rtp()))
            .polygon(buildCryptoWalletPaymentAddress(output.polygon()))
            .solana(buildCryptoWalletPaymentAddress(output.solana()))
            .ethereum(buildCryptoWalletPaymentAddress(output.ethereum()))
            .bitcoin(buildCryptoWalletPaymentAddress(output.bitcoin()))
            .base(buildCryptoWalletPaymentAddress(output.base()))
            .xrp(buildCryptoWalletPaymentAddress(output.xrp()))
            .arc(buildCryptoWalletPaymentAddress(output.arc()))
            .additionalProperties(output.additionalProperties())
            .build();
    }

    private static BankPaymentAddressDTO buildBankPaymentAddress(BankPaymentAddressVO output) {
        if (isNull(output)) {
            return null;
        }

        return new BankPaymentAddressDTO(
            output.routingNumber(),
            output.accountNumber()
        );
    }

    private static CryptoWalletPaymentAddressDTO buildCryptoWalletPaymentAddress(CryptoWalletPaymentAddressVO output) {
        if (isNull(output)) {
            return null;
        }

        return new CryptoWalletPaymentAddressDTO(output.walletAddress());
    }

}
