/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import lombok.EqualsAndHashCode;

import static java.util.Objects.isNull;

@EqualsAndHashCode
public class UltimateCreditorVO {

    private final AccountVO account;
    private final NameVO name;
    private final PhoneVO phone;
    private final EmailVO email;
    private final AddressVO address;

    public UltimateCreditorVO(AccountVO account, String name, String phone, String email, AddressVO address) {
        if (isNull(account)) {
            throw new ValueObjectRuleException("Ultimate creditor account must not be null");
        }

        if (isNull(name)) {
            throw new ValueObjectRuleException("Ultimate creditor name must not be null");
        }

        if (isNull(address)) {
            throw new ValueObjectRuleException("Ultimate creditor address must not be null");
        }

        this.account = account;
        this.name = new NameVO(name);
        this.phone = new PhoneVO(phone);
        this.email = new EmailVO(email);
        this.address = address;
    }

    public AccountVO account() {
        return account;
    }

    public String name() {
        return name.value();
    }

    public String phone() {
        return phone.value();
    }

    public String email() {
        return email.value();
    }

    public AddressVO address() {
        return address;
    }

}
