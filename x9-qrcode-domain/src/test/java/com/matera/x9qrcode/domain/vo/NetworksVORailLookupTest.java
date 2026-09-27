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
        return new NetworksVO(null, null, null, new SolanaPaymentAddressVO(SOLANA_WALLET, null), Map.of());
    }

    private static NetworksVO onlyAch() {
        return new NetworksVO(null, NETWORKS_FIXTURE.ach(), null, null, Map.of());
    }

    /**
     * Four rails, on two different authorities — and the distinction is the point.
     *
     * <p>FedNow, RTP and ACH are here because ANSI X9.150 defines their fields. Solana is here
     * because the Solana Foundation published its own (official-spec/SOLANA-FIELDS.md), which is the
     * bar ADR-0010 sets. Nothing is here because it seemed likely.
     */
    @Test
    void onlyRailsWithAPublishedShapeAreEnumerated() {
        assertEquals(4, NetworkEnum.values().length,
                "FedNow, RTP and ACH from the standard; Solana from its own published embedding");
    }

    @ParameterizedTest
    @EnumSource(value = NetworkEnum.class, names = {"FEDNOW", "RTP", "ACH"})
    void theStandardsOwnRailsAreNotBlockchains(NetworkEnum rail) {
        assertFalse(rail.isBlockchain(), "%s is a US bank rail".formatted(rail));
    }

    /**
     * The classification is not cosmetic: it decides whether a notification takes the two-phase
     * on-chain path, which is the only path that can tell before-the-funds-move from after.
     */
    @Test
    void solanaIsClassifiedAsABlockchain() {
        assertTrue(NetworkEnum.SOLANA.isBlockchain());
    }

    /**
     * The §2.4 {@code network} value resolves whatever its case.
     *
     * <p>§2.4 introduces its list as "exact, all-uppercase values" and then gives {@code FedNow},
     * so an implementer reading it can reasonably send {@code FEDNOW} or {@code FedNow}. That value
     * only ever reaches us from outside — a payer's notification, a settlement system's status
     * update — and refusing a payment over the case of a string we can resolve unambiguously would
     * be indefensible.
     *
     * <p>This is <em>not</em> the {@code networks} object key, which §14.5 spells {@code fednow}
     * throughout and which the OpenAPI contract pins.
     */
    @ParameterizedTest
    @ValueSource(strings = {"FedNow", "fednow", "FEDNOW", "fedNow", "fEdNoW"})
    void theNotificationNetworkValueResolvesWhateverTheCase(String spelling) {
        assertEquals(NetworkEnum.FEDNOW, NetworkEnum.fromValue(spelling));
        assertTrue(NetworkEnum.find(spelling).isPresent());
    }

    @ParameterizedTest
    @EnumSource(NetworkEnum.class)
    void everyRailResolvesFromItsOwnValue(NetworkEnum rail) {
        assertEquals(rail, NetworkEnum.fromValue(rail.value()));
    }

    /** A QR Code offering a rail still offers it when the payer names it differently. */
    @ParameterizedTest
    @ValueSource(strings = {"ACH", "ach", "Ach"})
    void supportsIsCaseInsensitiveForTheNotifiedRail(String spelling) {
        assertTrue(onlyAch().supports(spelling), spelling);
    }

    /** Not an error: an unrecognised name is a network we do not interpret, not a malformed one. */
    @ParameterizedTest
    @ValueSource(strings = {"Pix", "Zelle", "Tron", "Ethereum"})
    void anUnlistedNetworkSimplyDoesNotResolveToAnInterpretedRail(String name) {
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
