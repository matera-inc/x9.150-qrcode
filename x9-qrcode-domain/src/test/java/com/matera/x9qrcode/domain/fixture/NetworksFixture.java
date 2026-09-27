/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.fixture;

import com.matera.x9qrcode.domain.vo.BankPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;

import net.datafaker.Faker;

import java.util.HashMap;
import java.util.Map;

public final class NetworksFixture {

    private static final String HEX_CHARACTERS = "0123456789abcdef";
    private static final String BASE58_CHARACTERS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private final Faker faker;

    public NetworksFixture(Faker faker) {
        this.faker = faker;
    }

    public NetworksVO networks() {
        Map<String, Object> additionalProperties = new HashMap<>();
        additionalProperties.put(faker.lorem().word(), faker.lorem().sentence());
        additionalProperties.put(faker.lorem().word(), faker.number().randomNumber());

        return new NetworksVO(
                fedNow(),
                ach(),
                rtp(),
                solana(),
                additionalProperties);
    }

    public BankPaymentAddressVO fedNow() {
        return createBankPaymentAddressVO();
    }

    public BankPaymentAddressVO ach() {
        return createBankPaymentAddressVO();
    }

    public BankPaymentAddressVO rtp() {
        return createBankPaymentAddressVO();
    }

    public CryptoWalletPaymentAddressVO solana() {
        return new CryptoWalletPaymentAddressVO(generateSolanaWallet());
    }

    private BankPaymentAddressVO createBankPaymentAddressVO() {
        return new BankPaymentAddressVO(
                faker.finance().usRoutingNumber(),
                faker.number().randomNumber(4) + "," + faker.number().randomNumber(17));
    }


    private String generateSolanaWallet() {
        int length = faker.random().nextInt(44 - 32 + 1) + 32;

        StringBuilder addressBuilder = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            int randomIndex = faker.random().nextInt(BASE58_CHARACTERS.length());
            addressBuilder.append(BASE58_CHARACTERS.charAt(randomIndex));
        }

        return addressBuilder.toString();
    }



}
