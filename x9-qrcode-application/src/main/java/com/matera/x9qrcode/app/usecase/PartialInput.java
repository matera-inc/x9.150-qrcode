/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase;

import lombok.Getter;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A wrapper class that represents an input value for a partial update operation.
 * <p>
 * This class is essential for distinguishing between three distinct scenarios in an input DTO:
 * <ol>
 * <li><b>Value is Present and Not Null:</b> The client sent a concrete value.</li>
 * <li><b>Value is Present and Null:</b> The client explicitly sent a {@code null} value.</li>
 * <li><b>Value is Absent:</b> The field was not included in the request payload.</li>
 * </ol>
 * @param <INPUT> The type of the value being wrapped.
 */
public final class PartialInput<INPUT> {

    private final INPUT value;

    @Getter
    private final boolean isPresent;

    private PartialInput(INPUT value, boolean isPresent) {
        this.value = value;
        this.isPresent = isPresent;
    }

    public static <INPUT> PartialInput<INPUT> of(INPUT value) {
        return new PartialInput<>(value, true);
    }

    public static <INPUT> PartialInput<INPUT> absent() {
        return new PartialInput<>(null, false);
    }

    public INPUT get() {
        if (!isPresent) {
            throw new IllegalStateException("'value' is not present.");
        }

        return value;
    }

    public void ifPresent(Consumer<? super INPUT> action) {
        Objects.requireNonNull(action, "'PartialInput' action cannot be null.");

        if (isPresent) {
            action.accept(value);
        }
    }

}
