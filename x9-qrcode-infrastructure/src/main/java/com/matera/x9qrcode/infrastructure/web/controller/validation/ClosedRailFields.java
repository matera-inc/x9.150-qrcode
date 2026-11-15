/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.validation;

import com.matera.x9qrcode.infrastructure.generated.dto.ACHDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.FedNowDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.RTPDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.SolanaDTO;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Notes every unknown field found inside a rail we generate ({@code fednow}, {@code rtp}, {@code ach},
 * {@code solana}) while a request body is read.
 *
 * <p>Those rails are {@code additionalProperties: false} in the contract: what we generate is closed
 * (ADR-0012, amendment of 2026-11-15; INTERPRETATION I-9, rule {@code R-I9-03}). The service's
 * ObjectMapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, which on its own drops such a
 * field without a word.
 *
 * <p>The field is not refused here, mid-parse, because that would stop the read and hide every other
 * violation of the same body. It is recorded on the current request, with its path and its message,
 * and {@link ClosedRailFieldsValidator} adds it to the body's validation result, beside the rest.
 *
 * <p>Only the request-side types are watched. A payload we read (the location's payload, and what the
 * EMV decoder parses from another bank) uses the {@code Payload*} types, which are open: there an
 * unknown field is ignored, never refused. Outside a web request nothing is recorded.
 */
public final class ClosedRailFields extends DeserializationProblemHandler {

    private static final String BANK_FIELDS = "routingNumber, accountNumber and protectionType";

    /** Each closed rail: its key, and the fields that are its own. */
    private static final Map<Class<?>, Rail> RAILS = Map.of(
        FedNowDTO.class, new Rail("fednow", BANK_FIELDS),
        RTPDTO.class, new Rail("rtp", BANK_FIELDS),
        ACHDTO.class, new Rail("ach", BANK_FIELDS),
        SolanaDTO.class, new Rail("solana", "recipient and memo"));

    private static final String ATTRIBUTE = ClosedRailFields.class.getName() + ".violations";

    private record Rail(String key, String fields) {

        /** Word for word what the Go build answers for {@code solana}, and the same shape for the rest. */
        String message() {
            return "is not a field of %s, whose fields are %s".formatted(key, fields);
        }
    }

    /** One unknown field: where it is, and what is said about it. */
    record Violation(String path, String message) {
    }

    @Override
    public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p, JsonDeserializer<?> deserializer,
                                         Object beanOrClass, String propertyName) throws IOException {
        Rail rail = isNull(beanOrClass) ? null : RAILS.get(beanOrClass.getClass());
        if (isNull(rail)) {
            return false;
        }

        p.skipChildren();

        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (nonNull(request)) {
            @SuppressWarnings("unchecked")
            List<Violation> found = (List<Violation>) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (isNull(found)) {
                found = new ArrayList<>();
                request.setAttribute(ATTRIBUTE, found, RequestAttributes.SCOPE_REQUEST);
            }
            found.add(new Violation(pathOf(p.getParsingContext(), propertyName), rail.message()));
        }

        return true;
    }

    /** The unknown fields recorded on the current request. Empty when there are none. */
    static List<Violation> recorded() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (isNull(request)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Violation> found = (List<Violation>) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return isNull(found) ? List.of() : Collections.unmodifiableList(found);
    }

    /**
     * The field's path in the notation of every other violation: {@code paymentMethods[0].networks.fednow.x}.
     *
     * <p>{@code context} is the rail object itself. Its own current name may or may not be the
     * unknown field yet, so the walk starts at its parent and {@code propertyName} is appended.
     */
    static String pathOf(JsonStreamContext context, String propertyName) {
        StringBuilder path = new StringBuilder();
        for (JsonStreamContext c = context.getParent(); nonNull(c) && !c.inRoot(); c = c.getParent()) {
            if (c.inArray()) {
                path.insert(0, "[" + c.getCurrentIndex() + "]");
            } else if (nonNull(c.getCurrentName())) {
                path.insert(0, "." + c.getCurrentName());
            }
        }
        path.append('.').append(propertyName);
        return path.charAt(0) == '.' ? path.substring(1) : path.toString();
    }

}
