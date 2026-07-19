/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration.property.keystore;

import lombok.Data;

import static org.apache.commons.lang3.math.NumberUtils.INTEGER_ZERO;

@Data
public class EmvProperties {

    private static final int GUI_MAX_SIZE = 99;
    private static final int MAI_GUI_RESERVED_SIZE = 4;
    private static final int URL_RESERVED_SIZE = 4;

    private String pointInitiationMethodValue = "12";
    private String payloadFormatIndicator = "01";
    private String transactionCurrency = "840";
    private String maiTagId = "26";
    private String urlId = "01";
    private String maiGui = "org.x9";
    private String transactionAmount = INTEGER_ZERO.toString();

    public int getUrlMaxSize() {
        return GUI_MAX_SIZE - MAI_GUI_RESERVED_SIZE - URL_RESERVED_SIZE - this.getMaiGui().length();
    }

}
