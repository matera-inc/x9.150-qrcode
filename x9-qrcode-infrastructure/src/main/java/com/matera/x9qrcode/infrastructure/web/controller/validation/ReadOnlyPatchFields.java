/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.validation;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static java.util.Objects.isNull;

/**
 * A PATCH may carry what a GET answer carries but a PATCH cannot change (top-level {@code id},
 * {@code status}, {@code revision}, {@code creditor}, … and {@code bill.amountDue.currency}): owner
 * decision of 2026-11-15, option B (INTERPRETATION I-16). Each is accepted and ignored when it equals
 * the stored value, so a body copied from GET keeps working, and refused by name when it differs:
 *
 * <pre>
 * bill.amountDue.currency: cannot be changed by a PATCH (stored USD)
 * </pre>
 *
 * <p>"Stored" is the value as the GET answer renders it at the same instant, compared as JSON: a
 * number by its value, a string exactly as GET writes it (ids in upper-case hex, timestamps as GET
 * formats them), an absent member and a null alike, an object member by member. {@code sentAt} is
 * never compared: GET writes the instant of its own answer there, so no copy of it can ever match.
 * The Go build answers in the same words.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ReadOnlyPatchFields {

    private static final String ALWAYS_IGNORED = "sentAt";

    /**
     * The violations of the read-only members the current PATCH carried, top level first, in the
     * order sent; empty when it carried none or each equals what {@code getAnswer} renders. The GET
     * answer is built only when there is something to compare.
     */
    public static List<String> changed(Supplier<JsonNode> getAnswer) {
        List<ClosedRequestFields.ReadOnly> sent = new ArrayList<>(ClosedRequestFields.readOnlyRecorded());
        sent.removeIf(r -> r.path().equals(ALWAYS_IGNORED));
        if (sent.isEmpty()) {
            return List.of();
        }
        sent.sort(Comparator.comparing((ClosedRequestFields.ReadOnly r) -> r.path().contains(".")));
        JsonNode answer = getAnswer.get();
        List<String> violations = new ArrayList<>();
        for (ClosedRequestFields.ReadOnly r : sent) {
            JsonNode stored = answer.at("/" + r.path().replace('.', '/'));
            if (!same(stored, r.value())) {
                violations.add(r.path() + ": " + message(stored));
            }
        }
        return List.copyOf(violations);
    }

    /** {@code cannot be changed by a PATCH (stored USD)}; {@code (none stored)}; nothing more for an object. */
    static String message(JsonNode stored) {
        if (absent(stored)) {
            return "cannot be changed by a PATCH (none stored)";
        }
        if (stored.isContainerNode()) {
            return "cannot be changed by a PATCH";
        }
        return "cannot be changed by a PATCH (stored %s)".formatted(stored.asText());
    }

    private static boolean absent(JsonNode node) {
        return isNull(node) || node.isMissingNode() || node.isNull();
    }

    static boolean same(JsonNode a, JsonNode b) {
        if (absent(a) || absent(b)) {
            return absent(a) && absent(b);
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            Set<String> names = new HashSet<>();
            a.fieldNames().forEachRemaining(names::add);
            b.fieldNames().forEachRemaining(names::add);
            return names.stream().allMatch(n -> same(a.get(n), b.get(n)));
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<JsonNode> i = a.elements();
            Iterator<JsonNode> j = b.elements();
            while (i.hasNext()) {
                if (!same(i.next(), j.next())) {
                    return false;
                }
            }
            return true;
        }
        return a.getNodeType() == b.getNodeType() && a.asText().equals(b.asText());
    }

}
