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
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network lookup, now that only the standard's own rails are typed.
 *
 * <p>ANSI X9.150-2026 defines FedNow, RTP and ACH and fixes their structure, so those are an enum.
 * Every other network is open by design — §2.4 says the notification "MAY also carry a network not
 * listed above" — so it is carried by NAME in `additionalProperties` and looked up by name. A closed
 * enum over an open set is what once made Base, XRP and Arc silently unpayable.
 */
class NetworksVORailLookupTest extends AbstractTest {

    private static final String SOLANA_WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";

    private static NetworksVO onlySolana() {
        return new NetworksVO(null, null, null, Map.of("solana", Map.of("walletAddress", SOLANA_WALLET)));
    }

    private static NetworksVO onlyAch() {
        return new NetworksVO(null, NETWORKS_FIXTURE.ach(), null, Map.of());
    }

    @Test
    void onlyTheStandardsOwnRailsAreEnumerated() {
        assertEquals(3, NetworkEnum.values().length,
                "the enum holds exactly what ANSI X9.150 defines: FedNow, RTP, ACH");
    }

    @ParameterizedTest
    @EnumSource(NetworkEnum.class)
    void noStandardRailIsClassifiedAsABlockchain(NetworkEnum rail) {
        assertFalse(rail.isBlockchain(), "%s is a US bank rail".formatted(rail));
    }

    /** The spec contradicts itself on case, so every spelling has to resolve. */
    @ParameterizedTest
    @ValueSource(strings = {"FedNow", "fednow", "FEDNOW", "fedNow"})
    void aStandardRailResolvesWhateverTheCase(String spelling) {
        assertEquals(NetworkEnum.FEDNOW, NetworkEnum.fromValue(spelling));
        assertTrue(NetworkEnum.find(spelling).isPresent());
    }

    /** Not an error: an unrecognised name is a network we do not interpret, not a malformed one. */
    @ParameterizedTest
    @ValueSource(strings = {"Solana", "Pix", "Zelle", "Tron"})
    void anUnlistedNetworkSimplyDoesNotResolveToAStandardRail(String name) {
        assertTrue(NetworkEnum.find(name).isEmpty());
    }

    @Test
    void aNetworkCarriedByNameIsFoundByName() {
        NetworksVO networks = onlySolana();

        assertTrue(networks.supports("solana"));
        assertTrue(networks.supports("Solana"), "lookup is case-insensitive");
        assertEquals(SOLANA_WALLET, networks.destinationAddressFor("solana"));
    }

    @Test
    void aQRCodeOfferingOneNetworkDoesNotTherebyOfferAnother() {
        NetworksVO networks = onlySolana();

        assertFalse(networks.supports("Pix"));
        assertFalse(networks.supports(NetworkEnum.ACH));
        assertNull(networks.destinationAddressFor("Pix"));
    }

    @Test
    void bankRailsCarryNoDestinationAddress() {
        assertNull(onlyAch().destinationAddressFor("ach"));
        assertNotNull(onlyAch().bankAddressFor(NetworkEnum.ACH));
        assertTrue(onlyAch().supports("ACH"));
    }

}
