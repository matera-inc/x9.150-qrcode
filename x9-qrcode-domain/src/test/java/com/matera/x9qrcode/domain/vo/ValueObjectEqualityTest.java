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

    /**
     * <b>X9-AMT-020</b> — two amounts with different values are not equal.
     *
     * <p><b>Source:</b> Mechanism, and it was a REAL DEFECT. Lombok's @EqualsAndHashCode does not include fields
     * inherited from a superclass, and every value object held its data in ValueObject's `value`.
     *
     * <p><b>Why:</b> All fourteen value objects compared EQUAL to each other regardless of content. The visible
     * symptom was elsewhere entirely: PATCH could not change an amount, because the "has anything
     * changed?" guard compared old and new and always found them identical.
     */
    @Test
    void twoAmountsWithDifferentValuesAreNotEqual() {
        assertNotEquals(new AmountVO(22500L), new AmountVO(12500L),
                "every amount used to equal every other amount, which made a PATCH of the amount "
                    + "alone look like a no-op");
    }

    /**
     * <b>X9-AMT-021</b> — two amounts with the same value are equal.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> The other half: equality must still be TRUE when it should be, or the fix would have been to
     * make everything unequal, which breaks the no-op guard in the opposite direction.
     */
    @Test
    void twoAmountsWithTheSameValueAreEqual() {
        assertEquals(new AmountVO(22500L), new AmountVO(22500L));
        assertEquals(new AmountVO(22500L).hashCode(), new AmountVO(22500L).hashCode());
    }

    /**
     * <b>X9-AMT-022</b> — different values give different hash codes.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Equality and hashCode must agree, or value objects used as map keys or in sets behave
     * differently from value objects compared directly — a bug that appears only in the collection.
     */
    @Test
    void differentValuesGiveDifferentHashCodes() {
        assertNotEquals(new AmountVO(22500L).hashCode(), new AmountVO(12500L).hashCode(),
                "equal hash codes for every amount also degrade any map keyed on one");
    }

    // ------------------------------------------------------------------ the rest of the family

    /**
     * <b>X9-AMT-023</b> — the same breakage applied to every text value object.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Parameterised across the whole family deliberately. The original defect was not in one class
     * but in a base class, so a test covering only AmountVO would have passed while thirteen others
     * stayed broken.
     */
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

    /**
     * <b>X9-AMT-024</b> — different value object types are never equal, even wrapping the same value.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> A DescriptionVO holding "USD" is not a currency. Comparing by value alone would make types
     * interchangeable at exactly the places the type system was supposed to keep them apart.
     */
    @Test
    void valueObjectsOfDifferentTypesAreNotEqualEvenWrappingTheSameValue() {
        assertNotEquals(new NameVO("a@example.com"), (Object) new EmailVO("a@example.com"));
    }

    /**
     * <b>X9-AMT-025</b> — a value object is not equal to null.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> equals(null) returns false rather than throwing — the contract of Object.equals, which
     * collections rely on.
     */
    @Test
    void aValueObjectIsNotEqualToNull() {
        assertNotEquals(null, new AmountVO(1L));
    }

}
