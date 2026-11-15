/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.decodeemv;

import com.matera.x9qrcode.app.service.QRCodeEMVService;
import com.matera.x9qrcode.app.service.QRCodeExternalPayloadService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationInput;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationOutput;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * The decoder holds the payee's payload to the correlationId it asked under, whoever chose it.
 */
class DecoveEmvUseCaseTest {

    private static final String EMV = "emv";
    private static final String LOCATION = "other-bank.example.com/pub/api/v1/loc/1";

    private final QRCodeExternalPayloadService payloads = Mockito.mock(QRCodeExternalPayloadService.class);
    private final QRCodeEMVService emv = Mockito.mock(QRCodeEMVService.class);
    private final QRCodeSignatureService signatures = Mockito.mock(QRCodeSignatureService.class);
    private final DecoveEmvUseCase useCase = new DecoveEmvUseCase(payloads, emv, signatures);

    /** Runs a decode and returns the ids the fetch and the verification were given. */
    private UUID[] decode(UUID callers) {
        Mockito.when(emv.extractPayloadUrl(EMV)).thenReturn(LOCATION);
        Mockito.when(payloads.retrievePayload(eq(EMV), eq(LOCATION), any(), any()))
            .thenReturn(new DecodeEmvOutput(URI.create("https://" + LOCATION), 200, "a.b.c"));
        Mockito.when(signatures.validateSignature(any())).thenReturn(SignatureValidationOutput.validSignature(callers));

        useCase.execute(new DecodeEmvInput(EMV, null, callers));

        ArgumentCaptor<UUID> fetched = ArgumentCaptor.forClass(UUID.class);
        Mockito.verify(payloads).retrievePayload(anyString(), anyString(), any(), fetched.capture());
        ArgumentCaptor<SignatureValidationInput> verified = ArgumentCaptor.forClass(SignatureValidationInput.class);
        Mockito.verify(signatures).validateSignature(verified.capture());
        return new UUID[] {fetched.getValue(), verified.getValue().correlationId()};
    }

    /**
     * <b>X9-DEC-025</b> — the payload is verified against the correlationId the request was sent
     * under: the caller's when it gave one, else the one generated for the request, never none.
     *
     * <p><b>Source:</b> Ours. ADR-0027, amendment of 2026-11-15 (owner): every signed answer from
     * another party must echo our correlationId.
     *
     * <p><b>Why:</b> The id used to be generated inside the HTTP adapter and lost there, so the
     * verification was asked to compare against nothing, and any id passed. The use case now chooses
     * it and holds both steps to it.
     */
    @Test
    void thePayloadIsHeldToTheCorrelationIdSent() {
        UUID callers = UUID.randomUUID();
        UUID[] given = decode(callers);
        assertEquals(callers, given[0], "the request goes out under the caller's id");
        assertEquals(callers, given[1], "and the payload is held to it");

        Mockito.reset(payloads, emv, signatures);

        UUID[] generated = decode(null);
        assertNotNull(generated[0], "without the caller's, the request goes out under a generated id");
        assertEquals(generated[0], generated[1], "and the payload is held to that one");
    }
}
