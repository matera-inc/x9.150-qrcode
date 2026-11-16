/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.response;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matera.x9qrcode.infrastructure.generated.dto.BillPayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadResponseDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DecoderResponseMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String DUE = "\"invoice\": {\"dueDate\": \"2030-02-01T00:00:00Z\"}";

    private static BillPayloadResponseDTO.PaymentTimingEnum decoded(String bill) throws Exception {
        String payload = "{\"bill\": " + bill + "}";
        PaymentPayloadResponseDTO dto = MAPPER.readValue(payload, PaymentPayloadResponseDTO.class);
        return DecoderResponseMapper.map(dto, MAPPER.readTree(payload)).getBill().getPaymentTiming();
    }

    /**
     * <b>X9-DEC-027</b> — another bank's bill without paymentTiming is presented as "immediate" only
     * when it has no invoice.dueDate; with a dueDate it stays absent; a paymentTiming that was sent is
     * presented as sent.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.1 (paymentTiming SHALL be present; deferred requires
     * invoice.dueDate); openapi.yaml (always present, "immediate" by default). Owner decision,
     * 2026-11-16. The Go build's X9-DEC-516 pins the same cases.
     *
     * <p><b>Why:</b> "immediate" is the only safe reading of a bill with nothing to defer to; with a
     * due date the intent may be deferred, and inventing "immediate" would tell the payer the bill is
     * due now when it may not be.
     */
    @Test
    void aMissingPaymentTimingIsImmediateOnlyWithoutADueDate() throws Exception {
        assertEquals(BillPayloadResponseDTO.PaymentTimingEnum.IMMEDIATE, decoded("{\"description\": \"d\"}"));
        assertEquals(BillPayloadResponseDTO.PaymentTimingEnum.IMMEDIATE,
            decoded("{\"paymentTiming\": null, \"invoice\": {\"number\": \"I-1\"}}"));
        assertNull(decoded("{" + DUE + "}"), "with a dueDate, paymentTiming is not guessed");
        assertEquals(BillPayloadResponseDTO.PaymentTimingEnum.DEFERRED,
            decoded("{\"paymentTiming\": \"deferred\", " + DUE + "}"));
        assertEquals(BillPayloadResponseDTO.PaymentTimingEnum.IMMEDIATE,
            decoded("{\"paymentTiming\": \"immediate\", " + DUE + "}"), "a value sent is kept");
    }
}
