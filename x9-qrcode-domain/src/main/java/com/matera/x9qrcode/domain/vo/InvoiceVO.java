/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class InvoiceVO {

    private final NumberIdentifierVO number;
    private final LocalDate date;
    private final OffsetDateTime dueDate;
    private final InvoiceeVO invoicee;

    public InvoiceVO(String number,
                     LocalDate date,
                     OffsetDateTime dueDate,
                     InvoiceeVO invoicee) {
        this.number = new NumberIdentifierVO(number);
        this.date = date;
        this.dueDate = dueDate;
        this.invoicee = invoicee;

        if (isNull(date)) {
            throw new ValueObjectRuleException("Invoice date must not be null.");
        }

        if (isNull(dueDate)) {
            throw new ValueObjectRuleException("Invoice dueDate must not be null.");
        }

        if (dueDate.toLocalDate().isBefore(date)) {
            throw new ValueObjectRuleException("Invoice dueDate must be after or equal to creation date.");
        }

        if (dueDate.isBefore(DateTimeUtils.nowUTC())) {
            throw new ValueObjectRuleException("Invoice dueDate must be after or equal to actual date.");
        }

        if (date.isBefore(DateTimeUtils.nowUTC().toLocalDate())) {
            throw new ValueObjectRuleException("Invoice creation date must be after or equal to actual date.");
        }

        String value = this.number.value();

        if (nonNull(invoicee) && isNull(value) || value.isBlank()) {
            throw new ValueObjectRuleException("When invoicee is informed, invoice number is mandatory.");
        }
    }

    public NumberIdentifierVO number() {
        return number;
    }

    public LocalDate date() {
        return date;
    }

    public OffsetDateTime dueDate() {
        return dueDate;
    }

    public InvoiceeVO invoicee() {
        return invoicee;
    }

}
