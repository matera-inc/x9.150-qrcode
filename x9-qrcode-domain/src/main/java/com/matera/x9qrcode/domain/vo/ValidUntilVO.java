/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

import static java.util.Objects.isNull;

@EqualsAndHashCode
    /*
     * Time-relative rules deliberately do NOT live here.
     *
     * "Must be in the future" is a rule about CREATING this thing, not an invariant of the thing
     * itself. Enforced in the constructor it also runs on restore, so a stored record becomes
     * unreadable the moment its own window moves — and a payment QR Code is exactly the kind of
     * record whose windows are meant to move. See QRCodeEntityValidator#validateCreationDates.
     */
public class ValidUntilVO extends ValueObject<OffsetDateTime> {

    public ValidUntilVO(OffsetDateTime value) {
        if (isNull(value)) {
            throw new ValueObjectRuleException("ValidUntil must not be null.");
        }

        this.value = value;
    }

}
