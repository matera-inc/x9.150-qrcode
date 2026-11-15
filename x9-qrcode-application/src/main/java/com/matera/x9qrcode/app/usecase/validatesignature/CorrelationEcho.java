/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.validatesignature;

import com.matera.x9qrcode.app.dto.enumerated.SignatureProblemEnumDTO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

import static java.util.Objects.isNull;

/**
 * Whether a signed answer from another party was refused for not echoing our correlationId.
 *
 * <p>Owner decision of 2026-11-15 (ADR-0027): "a bad correlationId" is a signed answer whose JWS
 * header carries no {@code correlationId}, or another one than the request was sent under. The
 * decoder, and the outbound notification before and after the money moves, each answer it in their
 * own way, so it has to be told apart from every other failure. The verifier's code decides first,
 * so both builds agree: {@code CORRELATION_ID_MISMATCH}, or {@code BAD_HEADER} with no correlationId
 * or another one. An answer refused for its signature, its chain, its time window or its status is
 * not one, whatever it carries; nor is one refused {@code BAD_HEADER} for another parameter while
 * carrying ours. Go's {@code jws.NotEchoed} is the same rule.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CorrelationEcho {

    /**
     * @param problem  the verifier's code, null when the answer verified
     * @param answered the correlationId the answer's header carries as written, null when none
     * @param sent     the correlationId the request went out under
     */
    public static boolean notEchoed(SignatureProblemEnumDTO problem, String answered, UUID sent) {
        if (isNull(problem)) {
            return false;
        }
        return switch (problem) {
            case CORRELATION_ID_MISMATCH -> true;
            case BAD_HEADER -> isNull(answered) || !answered.equalsIgnoreCase(sent.toString());
            default -> false;
        };
    }

}
