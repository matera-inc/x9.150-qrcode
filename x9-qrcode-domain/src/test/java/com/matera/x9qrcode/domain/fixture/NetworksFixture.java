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
                polygon(),
                solana(),
                ethereum(),
                bitcoin(),
                base(),
                xrp(),
                arc(),
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

    public CryptoWalletPaymentAddressVO polygon() {
        return new CryptoWalletPaymentAddressVO(generateEvmWallet());
    }

    public CryptoWalletPaymentAddressVO solana() {
        return new CryptoWalletPaymentAddressVO(generateSolanaWallet());
    }

    /** Ethereum uses the EVM address format (0x + 40 hex chars) */
    public CryptoWalletPaymentAddressVO ethereum() {
        return new CryptoWalletPaymentAddressVO(generateEvmWallet());
    }

    public CryptoWalletPaymentAddressVO bitcoin() {
        return new CryptoWalletPaymentAddressVO(generateBitcoinWallet());
    }

    /** Base is Coinbase's EVM L2 — same 0x address format as Ethereum */
    public CryptoWalletPaymentAddressVO base() {
        return new CryptoWalletPaymentAddressVO(generateEvmWallet());
    }

    public CryptoWalletPaymentAddressVO xrp() {
        return new CryptoWalletPaymentAddressVO(generateXrpWallet());
    }

    /** Arc (Circle) is EVM-compatible — same 0x address format as Ethereum */
    public CryptoWalletPaymentAddressVO arc() {
        return new CryptoWalletPaymentAddressVO(generateEvmWallet());
    }

    private BankPaymentAddressVO createBankPaymentAddressVO() {
        return new BankPaymentAddressVO(
                faker.finance().usRoutingNumber(),
                faker.number().randomNumber(4) + "," + faker.number().randomNumber(17));
    }

    /** EVM-compatible address (0x + 40 hex chars) — Ethereum, Polygon, Base, Arc */
    private String generateEvmWallet() {
        StringBuilder addressBuilder = new StringBuilder("0x");

        for (int i = 0; i < 40; i++) {
            int randomIndex = faker.random().nextInt(HEX_CHARACTERS.length());

            addressBuilder.append(HEX_CHARACTERS.charAt(randomIndex));
        }

        return addressBuilder.toString();
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

    private String generateBitcoinWallet() {
        int length = faker.random().nextInt(44 - 32 + 1) + 32;

        StringBuilder addressBuilder = new StringBuilder("bc1");

        for (int i = 0; i < length; i++) {
            int randomIndex = faker.random().nextInt(BASE58_CHARACTERS.length());
            addressBuilder.append(BASE58_CHARACTERS.charAt(randomIndex));
        }

        return addressBuilder.toString();
    }

    /** XRP Ledger address (r prefix + 24-34 Base58 chars) */
    private String generateXrpWallet() {
        int length = faker.random().nextInt(34 - 24 + 1) + 24;

        StringBuilder addressBuilder = new StringBuilder("r");

        for (int i = 0; i < length; i++) {
            int randomIndex = faker.random().nextInt(BASE58_CHARACTERS.length());
            addressBuilder.append(BASE58_CHARACTERS.charAt(randomIndex));
        }

        return addressBuilder.toString();
    }

}
