/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When two announcements come from the same party.
 *
 * <p>Everything the reservation does rests on this one comparison, and both of its failure
 * directions are expensive. Too strict and a payer cannot retry their own announcement after a
 * timeout. Too loose and a stranger adopts somebody else's reservation and can release it.
 */
class ReservationHolderVOTest {

    private static final String SUBJECT = "CN=payer-psp.example.com,O=Example PSP,C=US";
    private static final String OTHER_SUBJECT = "CN=other-psp.example.com,O=Other PSP,C=US";
    private static final String PAYER = "9acc5ba9-ad22-3914-a268-631e367f74d7";

    /**
     * <b>X9-HOLD-001</b> — the same payer at the same institution is the same party.
     *
     * <p><b>Source:</b> Ours. ADR-0021.
     *
     * <p><b>Why:</b> The acceptance case, and the one the rule exists for: a payer whose request
     * timed out announces again. Without it every refusal below would pass against an
     * implementation that matched nobody at all.
     */
    @Test
    void theSamePayerAtTheSameInstitutionIsTheSameParty() {
        assertTrue(ReservationHolderVO.of(PAYER, SUBJECT).isSameParty(ReservationHolderVO.of(PAYER, SUBJECT)));
    }

    /**
     * <b>X9-HOLD-002</b> — a different payer at the same institution is a different party.
     *
     * <p><b>Source:</b> Ours. ADR-0021.
     *
     * <p><b>Why:</b> Two customers of one PSP are two payers. Matching on the certificate alone
     * would let either of them take over the other's reservation, which is the exact collision the
     * reservation exists to prevent — and it would happen inside the one institution where it is
     * most likely, because that is where two payers share a signer.
     */
    @Test
    void aDifferentPayerAtTheSameInstitutionIsADifferentParty() {
        assertFalse(ReservationHolderVO.of(PAYER, SUBJECT)
            .isSameParty(ReservationHolderVO.of("someone-else", SUBJECT)));
    }

    /**
     * <b>X9-HOLD-003</b> — the same payer value from a different institution is a different party.
     *
     * <p><b>Source:</b> Ours. ADR-0021.
     *
     * <p><b>Why:</b> {@code payer.info} is a field in a signed body, chosen by whoever signed it.
     * If it alone decided identity, any PSP could claim to be any payer simply by sending their
     * identifier — and the identifier is visible to every counterparty that has ever received one
     * of that payer's notifications.
     */
    @Test
    void theSamePayerValueFromADifferentInstitutionIsADifferentParty() {
        assertFalse(ReservationHolderVO.of(PAYER, SUBJECT)
            .isSameParty(ReservationHolderVO.of(PAYER, OTHER_SUBJECT)));
    }

    /**
     * <b>X9-HOLD-004</b> — an institution with no payer info matches itself.
     *
     * <p><b>Source:</b> Ours. ADR-0021.
     *
     * <p><b>Why:</b> {@code payer.info} is optional in the standard (§3.1), so a PSP that sends
     * none must still be able to retry its own announcement. The granularity drops from "this
     * payer" to "this payer's bank" — which is the concrete cost of omitting the field, and the
     * reason BEST-PRACTICES.md asks for it.
     */
    @Test
    void anInstitutionWithNoPayerInfoMatchesItself() {
        assertTrue(ReservationHolderVO.of(null, SUBJECT).isSameParty(ReservationHolderVO.of(null, SUBJECT)));
    }

    /**
     * <b>X9-HOLD-005</b> — nothing matches a holder nobody can name.
     *
     * <p><b>Source:</b> Ours. ADR-0021.
     *
     * <p><b>Why:</b> Two anonymous announcements are not evidence of being the same payer. If
     * unidentified matched unidentified, the first caller who sent no identity would hand their
     * reservation to the next caller who also sent none — and a reservation taken through the
     * payee's own API, which belongs to no payer at all, would be adoptable by anybody.
     */
    @Test
    void nothingMatchesAHolderNobodyCanName() {
        assertFalse(ReservationHolderVO.unattributed().isSameParty(ReservationHolderVO.unattributed()));
        assertFalse(ReservationHolderVO.unattributed().isSameParty(ReservationHolderVO.of(PAYER, SUBJECT)));
        assertFalse(ReservationHolderVO.of(PAYER, SUBJECT).isSameParty(ReservationHolderVO.unattributed()));
        assertFalse(ReservationHolderVO.of(PAYER, SUBJECT).isSameParty(null));
    }

}
