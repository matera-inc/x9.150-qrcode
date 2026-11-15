/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.validation;

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

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Notes every unknown field found inside a {@code solana} object while a request body is read.
 *
 * <p>{@code Solana} is {@code additionalProperties: false} in the contract (INTERPRETATION I-9, rule
 * {@code R-I9-03}): a rail interpreted on its owner's published embedding reports a field it does not
 * know, rather than carrying or dropping it. The service's ObjectMapper runs with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, which on its own drops such a field without a word.
 *
 * <p>The field is not refused here, mid-parse, because that would stop the read and hide every other
 * violation of the same body. It is recorded on the current request, with its path, and
 * {@link ClosedSolanaFieldsValidator} adds it to the body's validation result, beside the rest.
 *
 * <p>Outside a web request nothing is recorded and the field is skipped as before. Inside one, only
 * the bodies of a create and a PATCH are checked (see the validator): a payload decoded for somebody
 * else is transported, not policed.
 */
public final class ClosedSolanaFields extends DeserializationProblemHandler {

    /** The message, word for word what the Go build answers. */
    static final String MESSAGE = "is not a field of solana, whose fields are recipient and memo";

    private static final String ATTRIBUTE = ClosedSolanaFields.class.getName() + ".violations";

    @Override
    public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p, JsonDeserializer<?> deserializer,
                                         Object beanOrClass, String propertyName) throws IOException {
        if (!(beanOrClass instanceof SolanaDTO)) {
            return false;
        }

        p.skipChildren();

        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (nonNull(request)) {
            @SuppressWarnings("unchecked")
            List<String> found = (List<String>) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (isNull(found)) {
                found = new ArrayList<>();
                request.setAttribute(ATTRIBUTE, found, RequestAttributes.SCOPE_REQUEST);
            }
            found.add(pathOf(p.getParsingContext(), propertyName));
        }

        return true;
    }

    /** The paths of the unknown fields recorded on the current request. Empty when there are none. */
    static List<String> recorded() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (isNull(request)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<String> found = (List<String>) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return isNull(found) ? List.of() : Collections.unmodifiableList(found);
    }

    /**
     * The field's path in the notation of every other violation: {@code paymentMethods[0].networks.solana.x}.
     *
     * <p>{@code context} is the {@code solana} object itself. Its own current name may or may not be the
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
