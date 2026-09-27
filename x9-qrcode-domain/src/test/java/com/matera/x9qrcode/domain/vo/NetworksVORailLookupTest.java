/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rail lookup on {@link NetworksVO}, and the classification on {@link NetworkEnum}.
 *
 * <p>These exist because the previous code hardcoded a partial list of blockchains in several
 * places. Base, XRP and Arc were missing from all of them, so a notification on those rails was
 * accepted and silently ignored. Every rail in the enum is exercised here, by {@code @EnumSource},
 * so a rail added later is covered without anyone remembering to add a case.
 */
class NetworksVORailLookupTest extends AbstractTest {

    private static NetworksVO onlySolana() {
        return new NetworksVO(null, null, null, null,
                NETWORKS_FIXTURE.solana(), null, null, null, null, null, Map.of());
    }

    private static NetworksVO onlyAch() {
        return new NetworksVO(null, NETWORKS_FIXTURE.ach(), null, null,
                null, null, null, null, null, null, Map.of());
    }

    @ParameterizedTest
    @EnumSource(NetworkEnum.class)
    void everyRailIsClassifiedAsBankOrBlockchain(NetworkEnum network) {
        boolean bankRail = network == NetworkEnum.FEDNOW || network == NetworkEnum.RTP || network == NetworkEnum.ACH;

        assertEquals(!bankRail, network.isBlockchain(),
                "%s must be classified deliberately, not by omission".formatted(network));
    }

    @ParameterizedTest
    @EnumSource(NetworkEnum.class)
    void lookupNeverThrowsForAnyRail(NetworkEnum network) {
        NetworksVO networks = onlySolana();

        // The point is total coverage: no rail may fall through a switch.
        networks.cryptoAddressFor(network);
        networks.bankAddressFor(network);
        networks.supports(network);
    }

    @ParameterizedTest
    @EnumSource(value = NetworkEnum.class, names = {"BASE", "XRP", "ARC"})
    void theThreeRailsThatUsedToBeForgottenAreSupportedWhenPresent(NetworkEnum network) {
        NetworksVO networks = new NetworksVO(null, null, null, null, null, null, null,
                NETWORKS_FIXTURE.base(), NETWORKS_FIXTURE.xrp(), NETWORKS_FIXTURE.arc(), Map.of());

        assertTrue(networks.supports(network), "%s must be recognised".formatted(network));
        assertNotNull(networks.cryptoAddressFor(network));
    }

    @Test
    void aQRCodeOfferingOneChainDoesNotTherebyAcceptAnother() {
        NetworksVO networks = onlySolana();

        assertTrue(networks.supports(NetworkEnum.SOLANA));
        assertFalse(networks.supports(NetworkEnum.BITCOIN), "offering Solana must not imply Bitcoin");
        assertFalse(networks.supports(NetworkEnum.ETHEREUM));
        assertFalse(networks.supports(NetworkEnum.BASE));
    }

    @Test
    void bankRailsCarryNoWalletAndBlockchainsCarryNoBankAddress() {
        assertNull(onlyAch().cryptoAddressFor(NetworkEnum.ACH));
        assertNull(onlySolana().bankAddressFor(NetworkEnum.SOLANA));

        assertNotNull(onlyAch().bankAddressFor(NetworkEnum.ACH));
        assertNotNull(onlySolana().cryptoAddressFor(NetworkEnum.SOLANA));
    }

}
