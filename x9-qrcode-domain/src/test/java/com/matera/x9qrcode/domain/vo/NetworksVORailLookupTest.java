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
     * <b>X9-RAIL-010</b> — only rails with a published shape are enumerated.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.5 names eight networks but defines the structure of only three —
     * fednow, rtp and ach. For the rest it says, in full: <i>"Refer to network documentation on
     * required fields and processing requirements."</i> Solana is interpreted on the embedding its
     * Foundation published. ADR-0010, INTERPRETATION I-2.
     *
     * <p><b>Why:</b> The enum is the list of rails whose inner JSON somebody authoritative has written down. A
     * rail added because a caller sent it would be a shape we invented, and a QR Code advertising it
     * is a promise nobody can keep.
     */
    @Test
    void onlyRailsWithAPublishedShapeAreEnumerated() {
        assertEquals(4, NetworkEnum.values().length,
                "FedNow, RTP and ACH from the standard; Solana from its own published embedding");
    }

    /**
     * <b>X9-RAIL-011</b> — the standard's own rails are not blockchains.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.5 — fednow, rtp and ach are bank rails. Conformance.
     *
     * <p><b>Why:</b> The classification decides which notification evidence is required: a bank rail carries no
     * transaction hash and no wallet addresses, so treating one as a blockchain would demand
     * evidence that cannot exist and refuse every legitimate payment.
     */
    @ParameterizedTest
    @EnumSource(value = NetworkEnum.class, names = {"FEDNOW", "RTP", "ACH"})
    void theStandardsOwnRailsAreNotBlockchains(NetworkEnum rail) {
        assertFalse(rail.isBlockchain(), "%s is a US bank rail".formatted(rail));
    }

    /**
     * <b>X9-RAIL-012</b> — Solana is classified as a blockchain.
     *
     * <p><b>Source:</b> Ours, on the Solana Foundation's published embedding. ADR-0010, official-spec/SOLANA-FIELDS.md.
     *
     * <p><b>Why:</b> It is the only rail that can report a committed transaction, which is why payment.sent and
     * payment.failed exist at all and why they never fire on the bank rails.
     */
    @Test
    void solanaIsClassifiedAsABlockchain() {
        assertTrue(NetworkEnum.SOLANA.isBlockchain());
    }

    /**
     * <b>X9-RAIL-013</b> — a notified network resolves whatever its case.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.5 fixes the spelling in the payload; it says nothing about what a PAYER
     * may send back in a notification. Reading it case-insensitively is ours — INTERPRETATION I-1.
     *
     * <p><b>Why:</b> We emit the spec's lowercase spelling, but refusing "FedNow" from a third party would reject a
     * real payment over a capital letter. Strict in what we send, lenient in what we accept.
     */
    @ParameterizedTest
    @ValueSource(strings = {"FedNow", "fednow", "FEDNOW", "fedNow", "fEdNoW"})
    void theNotificationNetworkValueResolvesWhateverTheCase(String spelling) {
        assertEquals(NetworkEnum.FEDNOW, NetworkEnum.fromValue(spelling));
        assertTrue(NetworkEnum.find(spelling).isPresent());
    }

    /**
     * <b>X9-RAIL-014</b> — every rail resolves from its own value.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Parameterised over the whole enum, so adding a rail without wiring its lookup fails here
     * rather than at the first payment on it.
     */
    @ParameterizedTest
    @EnumSource(NetworkEnum.class)
    void everyRailResolvesFromItsOwnValue(NetworkEnum rail) {
        assertEquals(rail, NetworkEnum.fromValue(rail.value()));
    }

    /**
     * <b>X9-RAIL-015</b> — rail support is checked case-insensitively.
     *
     * <p><b>Source:</b> Ours. I-1, as RAIL-013.
     *
     * <p><b>Why:</b> The same leniency must apply to the SUPPORT check as to the lookup. If they disagreed, a rail
     * would resolve and then be reported unsupported — a contradiction the caller cannot act on.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ACH", "ach", "Ach"})
    void supportsIsCaseInsensitiveForTheNotifiedRail(String spelling) {
        assertTrue(onlyAch().supports(spelling), spelling);
    }

    /**
     * <b>X9-RAIL-016</b> — an unlisted network does not resolve to an interpreted rail.
     *
     * <p><b>Source:</b> Ours. ADR-0010 and ADR-0012 — a network becomes interpretable when its owner publishes how it
     * is embedded, never because a caller sent it.
     *
     * <p><b>Why:</b> Pix, Zelle, Tron and Ethereum are all real networks we have no published embedding for.
     * Resolving one to something near it would mean guessing a shape, and the guess reaches a payer
     * as a QR Code. Not resolving is what makes creation refuse it by name.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Pix", "Zelle", "Tron", "Ethereum"})
    void anUnlistedNetworkSimplyDoesNotResolveToAnInterpretedRail(String name) {
        assertTrue(NetworkEnum.find(name).isEmpty());
    }

    /**
     * <b>X9-RAIL-017</b> — a network we do not interpret is still carried, by name.
     *
     * <p><b>Source:</b> Ours, and the deliberate asymmetry in INTERPRETATION I-9: refused when we ISSUE, carried
     * intact when we TRANSPORT someone else's payload.
     *
     * <p><b>Why:</b> Dropping a network we cannot read would remove the only thing the payer needed in order to
     * pay, and do it silently — the caller receives a payload that looks complete and is not. We
     * refuse to MAKE a promise we cannot keep; we decline to BREAK a message that was never ours.
     */
    @Test
    void aNetworkCarriedByNameIsFoundByName() {
        NetworksVO networks = onlySolana();

        assertTrue(networks.supports("solana"));
        assertTrue(networks.supports("Solana"), "lookup is case-insensitive");
        assertEquals(SOLANA_WALLET, networks.destinationAddressFor("solana"));
    }

    /**
     * <b>X9-RAIL-018</b> — offering one network does not thereby offer another.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.5 — each network object is published independently. Conformance.
     *
     * <p><b>Why:</b> A QR Code offering FedNow has not offered RTP, however similar the two are. Treating rails as
     * a family would accept payment over a route the biller never published bank details for.
     */
    @Test
    void aQRCodeOfferingOneNetworkDoesNotTherebyOfferAnother() {
        NetworksVO networks = onlySolana();

        assertFalse(networks.supports("Pix"));
        assertFalse(networks.supports(NetworkEnum.ACH));
        assertNull(networks.destinationAddressFor("Pix"));
    }

    /**
     * <b>X9-RAIL-019</b> — bank rails carry no destination address.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.5 — bank networks carry routing and account numbers, not addresses.
     * Conformance.
     *
     * <p><b>Why:</b> The acceptance policy matches a blockchain notification by the destination address it names.
     * Bank rails have none, so that lookup must not be attempted for them — otherwise every FedNow
     * payment is refused for failing to match an address that was never published.
     */
    @Test
    void bankRailsCarryNoDestinationAddress() {
        assertNull(onlyAch().destinationAddressFor("ach"));
        assertNotNull(onlyAch().bankAddressFor(NetworkEnum.ACH));
        assertTrue(onlyAch().supports("ACH"));
    }

}
