/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.validation;

import com.matera.x9qrcode.infrastructure.generated.dto.NetworksSimpleDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchNetworksSimpleDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchPaymentRequestReplacementDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestInputDTO;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The closed objects' table cannot drift from the generated types it describes.
 */
class ClosedRequestFieldsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GENERATED = "com.matera.x9qrcode.infrastructure.generated.dto.";
    private static final Set<Class<?>> OPEN = Set.of(NetworksSimpleDTO.class, PatchNetworksSimpleDTO.class);

    private static List<BeanPropertyDefinition> properties(JavaType type) {
        return MAPPER.getDeserializationConfig().introspect(type).findProperties();
    }

    /**
     * <b>X9-RAIL-068</b> — every object a create, a PATCH or an outbound notification can carry is in
     * the closed table, except {@code networks}; and each entry lists exactly the type's own fields.
     *
     * <p><b>Source:</b> Mechanism. INTERPRETATION I-16: what we generate is closed at every level. The
     * table is written by hand so its words match the Go build's; this keeps it equal to the generated
     * types, which come from {@code openapi.yaml}.
     *
     * <p><b>Why:</b> A field added to the contract but not to the table would be refused as unknown
     * while being known; an object added without an entry would silently drop an unknown field again.
     * Both are the kind of drift nobody sees until a biller does.
     */
    @Test
    void theClosedTableCoversEveryRequestObjectWithItsOwnFields() {
        Set<Class<?>> reached = new HashSet<>();
        Deque<JavaType> todo = new ArrayDeque<>();
        for (Class<?> root : List.of(PaymentRequestInputDTO.class, PatchPaymentRequestReplacementDTO.class,
            OutboundPaymentNotificationDTO.class)) {
            todo.push(MAPPER.constructType(root));
        }
        while (!todo.isEmpty()) {
            JavaType type = todo.pop();
            Class<?> raw = type.getRawClass();
            if (!raw.getName().startsWith(GENERATED)) {
                // a List, a JsonNullable: what it holds
                for (int i = 0; i < type.containedTypeCount(); i++) {
                    todo.push(type.containedType(i));
                }
                continue;
            }
            if (raw.isEnum() || !reached.add(raw)) {
                continue;
            }
            properties(type).forEach(p -> todo.push(p.getPrimaryType()));
        }
        reached.removeAll(OPEN);

        Set<String> missing = reached.stream().filter(c -> !ClosedRequestFields.OBJECTS.containsKey(c))
            .map(Class::getSimpleName).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of(), missing, "request objects with no entry in the closed table");

        for (Map.Entry<Class<?>, ClosedRequestFields.Closed> entry : ClosedRequestFields.OBJECTS.entrySet()) {
            Set<String> own = properties(MAPPER.constructType(entry.getKey())).stream()
                .map(BeanPropertyDefinition::getName).collect(Collectors.toCollection(TreeSet::new));
            assertEquals(own, new TreeSet<>(entry.getValue().fields()), entry.getKey().getSimpleName());
            assertTrue(reached.contains(entry.getKey()), entry.getKey().getSimpleName() + " is not a request object");
        }
    }

}
