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
 * <p>Driven by {@code @EnumSource} rather than a hand-written list, so a rail added to the enum is
 * covered without anyone remembering to add a case. That matters here: the code this replaces
 * hardcoded a partial list of blockchains in several places, and the rails missing from those lists
 * were accepted and silently ignored.
 */
class NetworksVORailLookupTest extends AbstractTest {

    private static NetworksVO onlySolana() {
        return new NetworksVO(null, null, null, NETWORKS_FIXTURE.solana(), Map.of());
    }

    private static NetworksVO onlyAch() {
        return new NetworksVO(null, NETWORKS_FIXTURE.ach(), null, null, Map.of());
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

    @Test
    void solanaIsTheOnlyInterpretedBlockchain() {
        // ADR-0010: a chain is modelled only once its owner has published how it embeds in X9.150.
        // Any other chain travels uninterpreted through additionalProperties instead.
        assertEquals(4, NetworkEnum.values().length,
                "only FedNow, RTP, ACH and Solana are interpreted");
        assertTrue(NetworkEnum.SOLANA.isBlockchain());
    }

    @Test
    void aQRCodeOfferingSolanaSupportsNoBankRail() {
        NetworksVO networks = onlySolana();

        assertTrue(networks.supports(NetworkEnum.SOLANA));
        assertFalse(networks.supports(NetworkEnum.ACH));
        assertFalse(networks.supports(NetworkEnum.FEDNOW));
        assertFalse(networks.supports(NetworkEnum.RTP));
    }

    @Test
    void bankRailsCarryNoWalletAndSolanaCarriesNoBankAddress() {
        assertNull(onlyAch().cryptoAddressFor(NetworkEnum.ACH));
        assertNull(onlySolana().bankAddressFor(NetworkEnum.SOLANA));

        assertNotNull(onlyAch().bankAddressFor(NetworkEnum.ACH));
        assertNotNull(onlySolana().cryptoAddressFor(NetworkEnum.SOLANA));
    }

}
