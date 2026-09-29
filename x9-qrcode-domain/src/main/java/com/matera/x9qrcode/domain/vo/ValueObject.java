/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import java.util.Objects;

/**
 * Base of the single-valued value objects.
 *
 * <p>Equality lives here, deliberately, and not on the subclasses. Each of them used to carry a
 * bare Lombok {@code @EqualsAndHashCode}, which includes only the fields declared in the annotated
 * class — and every one of them declares none, because {@code value} is here. The generated
 * {@code equals} therefore compared <em>nothing</em>, so any two instances of the same value object
 * were equal: {@code new AmountVO(22500).equals(new AmountVO(12500))} answered {@code true}, with
 * matching hash codes.
 *
 * <p>That is a quiet kind of wrong. Nothing throws; equality simply always agrees, so every check
 * built on it silently passes. It surfaced as a PATCH that could not change a payment method's
 * amount — the entity compares old and new methods to decide whether anything changed, saw two
 * equal amounts, and refused the update as a no-op. An adopter found it trying to reduce a QR Code
 * after a partial payment.
 *
 * <p>Comparing the concrete class as well as the value keeps two different value objects wrapping
 * the same string from being equal: a {@code NameVO("x")} is not an {@code EmailVO("x")}.
 */
public abstract class ValueObject<T> {

    protected T value;

    public T value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        return Objects.equals(this.value, ((ValueObject<?>) other).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

}
