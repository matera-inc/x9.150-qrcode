/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

/**
 * All Value Object classes (VO) with primitive attributes should extend this component and have the VO suffix.
 */
public abstract class ValueObject<T> {

    protected T value;

    public T value() {
        return value;
    }

}
