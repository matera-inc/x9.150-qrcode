/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.payload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relayed refusal is somebody else's text, and it is rendered under our name.
 *
 * <p>The payload location comes out of the scanned QR Code, so the host that answers is whichever
 * one that code names — not necessarily this deployment. Relaying its reason is the only way a
 * payer learns whether to stop or to retry, so we do relay it; but a crafted code can choose which
 * host answers, and therefore choose the text.
 *
 * <p>That makes an error handler into a publishing channel, and the obvious abuse is an address:
 * print a link of the attacker's choosing inside a message carrying the payee's name and the whole
 * phishing apparatus is somebody else's error path. No legitimate reason a payload is unavailable
 * needs a URL in order to say it, so nothing is lost by refusing to carry one.
 */
class RelayedRefusalIsSafeToRenderTest {

    /**
     * <b>X9-DEC-008</b> — a relayed reason cannot carry a link.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> The host that wrote the text is named by the scanned code, so it is chosen by
     * whoever printed that code. Rendering its address back to a payer under our name turns a
     * refusal into a phishing surface.
     */
    @Test
    void aRelayedReasonCannotCarryALink() {
        String relayed = RestClientQRCodeExternalPayloadService.sanitised(
            "Payment failed. Verify at http://verify-your-account.example.com/login now.");

        assertFalse(relayed.contains("verify-your-account"), relayed);
        assertFalse(relayed.contains("http"), relayed);
        assertTrue(relayed.contains("[link removed]"), "the removal must be visible, not silent: " + relayed);
    }

    /**
     * <b>X9-DEC-009</b> — a bare domain is a link too.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> A reader types what looks like an address whether or not it carries a scheme,
     * so matching only {@code http://} would leave the attack intact and the check decorative.
     */
    @Test
    void aBareDomainIsALinkToo() {
        String relayed = RestClientQRCodeExternalPayloadService.sanitised("Go to verify-now.xyz to continue");

        assertFalse(relayed.contains("verify-now.xyz"), relayed);
    }

    /**
     * <b>X9-DEC-010</b> — an ordinary reason passes through untouched.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> The point of relaying is the reason. A filter that mangles the legitimate
     * case has traded one unusable message for another.
     */
    @Test
    void anOrdinaryReasonPassesThroughUntouched() {
        String reason = "payment payload with ID: 01A102 has already been paid.";

        assertEquals(reason, RestClientQRCodeExternalPayloadService.sanitised(reason));
    }

    /**
     * <b>X9-DEC-011</b> — control characters do not survive.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> A reason is a sentence. One that can move a cursor or inject a line is being
     * used for something other than being read — a log forged a line at a time, or a terminal
     * rewritten mid-render.
     */
    @Test
    void controlCharactersDoNotSurvive() {
        String relayed = RestClientQRCodeExternalPayloadService.sanitised(
            "paid\u001b[2Kinjected\nsecond line");

        assertFalse(relayed.contains("\u001b"), relayed);
        assertFalse(relayed.contains("\n"), relayed);
    }

    /**
     * <b>X9-DEC-012</b> — a reason reduced to nothing still says something.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> A payload whose only content was a link must not relay an empty string: the
     * caller would be told a refusal happened and shown a blank reason, which reads as a bug in
     * this service rather than as a refusal from somewhere else.
     */
    @Test
    void aReasonReducedToNothingStillSaysSomething() {
        String relayed = RestClientQRCodeExternalPayloadService.sanitised("   \u0000  ");

        assertFalse(relayed.isBlank(), "an empty reason is not a reason");
    }

}
