/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.validatesignature;

import com.matera.x9qrcode.app.dto.enumerated.SignatureProblemEnumDTO;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationEchoTest {

    /**
     * <b>X9-SIG-158</b> — "a bad correlationId" is CORRELATION_ID_MISMATCH, or BAD_HEADER whose header
     * carries no correlationId or another one; the verifier's code decides first, so an answer refused
     * for anything else is not one, whatever it carries.
     *
     * <p><b>Source:</b> Ours. Owner, 2026-11-15 (ADR-0027). Go's {@code jws.NotEchoed} is the same rule.
     *
     * <p><b>Why:</b> The decoder and the outbound notification answer a bad correlationId in their own
     * way; both builds must draw the line in the same place.
     */
    @Test
    void aBadCorrelationIdIsTheVerifiersVerdictOnTheCorrelationId() {
        UUID sent = UUID.randomUUID();
        String ours = sent.toString();

        assertTrue(CorrelationEcho.notEchoed(SignatureProblemEnumDTO.CORRELATION_ID_MISMATCH, "another", sent));
        assertTrue(CorrelationEcho.notEchoed(SignatureProblemEnumDTO.BAD_HEADER, null, sent));
        assertTrue(CorrelationEcho.notEchoed(SignatureProblemEnumDTO.BAD_HEADER, "1-2-3-4-5", sent));
        assertFalse(CorrelationEcho.notEchoed(SignatureProblemEnumDTO.BAD_HEADER, ours, sent),
            "a bad header that carries ours is about another parameter");
        assertFalse(CorrelationEcho.notEchoed(SignatureProblemEnumDTO.BAD_HEADER, ours.toUpperCase(Locale.ROOT), sent));
        assertFalse(CorrelationEcho.notEchoed(SignatureProblemEnumDTO.BAD_SIGNATURE, "another", sent));
        assertFalse(CorrelationEcho.notEchoed(SignatureProblemEnumDTO.UNTRUSTED_CHAIN, null, sent));
        assertFalse(CorrelationEcho.notEchoed(SignatureProblemEnumDTO.NOT_JWS, null, sent));
        assertFalse(CorrelationEcho.notEchoed(SignatureProblemEnumDTO.ABSENT_BODY, null, sent));
        assertFalse(CorrelationEcho.notEchoed(null, ours, sent), "verified");
    }
}
