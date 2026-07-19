/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import static java.util.Objects.isNull;

public record CreditorVO(
        NameVO name,
        PhoneVO phone,
        EmailVO email,
        AddressVO address,
        UltimateCreditorVO ultimateCreditor,
        MerchantCategoryCodeVO merchantCategoryCode
) {
    
    public CreditorVO {
        if (isNull(name)) {
            throw new ValueObjectRuleException("Creditor name must not be null");
        }

        if (isNull(address)) {
            throw new ValueObjectRuleException("Creditor address must not be null");
        }

        if (isNull(merchantCategoryCode)) {
            throw new ValueObjectRuleException("Creditor mcc must not be null");
        }
    }

    public CreditorVO(String name,
                      String phone,
                      String email,
                      AddressVO address,
                      UltimateCreditorVO ultimateCreditor,
                      String merchantCategoryCode) {
        this(
            new NameVO(name),
            new PhoneVO(phone),
            new EmailVO(email),
            address,
            ultimateCreditor,
            new MerchantCategoryCodeVO(merchantCategoryCode)
        );
    }

    public NameVO name() {
        return name;
    }

    public PhoneVO phone() {
        return phone;
    }

    public EmailVO email() {
        return email;
    }

    public MerchantCategoryCodeVO merchantCategoryCode() {
        return merchantCategoryCode;
    }

    public AddressVO address() {
        return address;
    }

    public UltimateCreditorVO ultimateCreditor() {
        return ultimateCreditor;
    }
}