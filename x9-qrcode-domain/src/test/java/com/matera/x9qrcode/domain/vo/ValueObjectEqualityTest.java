/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A value object compares its value. It sounds too obvious to test, which is exactly why it went
 * wrong and stayed wrong.
 *
 * <p>Every subclass carried a bare Lombok {@code @EqualsAndHashCode}. That annotation includes only
 * the fields declared in the annotated class, and each of them declares none — {@code value} lives
 * in {@link ValueObject}. So the generated {@code equals} compared nothing, and any two instances of
 * the same value object were equal.
 *
 * <p>Nothing threw. Equality simply always agreed, so every check built on it silently passed. It
 * took an adopter, trying to reduce a QR Code's amount after a partial payment, to notice: the
 * entity compares old and new payment methods to decide whether anything changed, saw two "equal"
 * amounts, and refused the update as a no-op.
 */
class ValueObjectEqualityTest {

    /** The one that cost a feature. */
    @Test
    void twoAmountsWithDifferentValuesAreNotEqual() {
        assertNotEquals(new AmountVO(22500L), new AmountVO(12500L),
                "every amount used to equal every other amount, which made a PATCH of the amount "
                    + "alone look like a no-op");
    }

    @Test
    void twoAmountsWithTheSameValueAreEqual() {
        assertEquals(new AmountVO(22500L), new AmountVO(22500L));
        assertEquals(new AmountVO(22500L).hashCode(), new AmountVO(22500L).hashCode());
    }

    @Test
    void differentValuesGiveDifferentHashCodes() {
        assertNotEquals(new AmountVO(22500L).hashCode(), new AmountVO(12500L).hashCode(),
                "equal hash codes for every amount also degrade any map keyed on one");
    }

    // ------------------------------------------------------------------ the rest of the family

    @Test
    void theSameBreakageAppliedToEveryTextValueObject() {
        assertNotEquals(new NameVO("Alice"), new NameVO("Bob"));
        assertNotEquals(new EmailVO("a@example.com"), new EmailVO("b@example.com"));
        assertNotEquals(new DescriptionVO("one"), new DescriptionVO("two"));
        assertNotEquals(new UnstructuredVO("one"), new UnstructuredVO("two"));
        assertNotEquals(new NumberIdentifierVO("INV-1"), new NumberIdentifierVO("INV-2"));

        assertEquals(new NameVO("Alice"), new NameVO("Alice"));
        assertEquals(new EmailVO("a@example.com"), new EmailVO("a@example.com"));
    }

    /** Type is part of identity: a name that reads like an email is still not one. */
    @Test
    void valueObjectsOfDifferentTypesAreNotEqualEvenWrappingTheSameValue() {
        assertNotEquals(new NameVO("a@example.com"), (Object) new EmailVO("a@example.com"));
    }

    @Test
    void aValueObjectIsNotEqualToNull() {
        assertNotEquals(null, new AmountVO(1L));
    }

}
