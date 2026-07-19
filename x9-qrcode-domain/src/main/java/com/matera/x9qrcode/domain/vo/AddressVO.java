/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.utils.USCodesUtils;

import java.util.regex.Pattern;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public record AddressVO(
    String line1,
    String line2,
    String city,
    String state,
    String postalCode,
    String country
) {

    private static final Pattern LINE1_PATTERN = Pattern.compile("^[\\x20-\\x7E]{1,60}$");
    private static final Pattern LINE2_PATTERN = Pattern.compile("^[\\x20-\\x7E]{0,60}$");
    private static final Pattern CITY_PATTERN = Pattern.compile("^[\\x20-\\x7E]{1,40}$");
    private static final Pattern STATE_REGEX = Pattern.compile("^[\\x20-\\x7E]{0,30}$");
    private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("^(\\d{5}(-\\d{4})?|[\\x20-\\x7E]{0,10})$");
    private static final Pattern COUNTRY_PATTERN = Pattern.compile("^[A-Z]{2}$");

    public AddressVO {
        if (isNull(line1)) {
            throw new ValueObjectRuleException("Address line1 must not be null.");
        }

        if (!LINE1_PATTERN.matcher(line1).matches()) {
            throw new ValueObjectRuleException("Address line1 is invalid.");
        }

        if (nonNull(line2) && !LINE2_PATTERN.matcher(line2).matches()) {
            throw new ValueObjectRuleException("Address line2 is invalid.");
        }

        if (isNull(city)) {
            throw new ValueObjectRuleException("Address city must not be null.");
        }

        if (!CITY_PATTERN.matcher(city).matches()) {
            throw new ValueObjectRuleException("Address city is invalid.");
        }

        validateState();

        validatePostalCode();

        if (!COUNTRY_PATTERN.matcher(country).matches()) {
            throw new ValueObjectRuleException("Address country is invalid.");
        }
    }

    private void validateState() {
        if (isNull(state) || state.isBlank()) {
            return;
        }

        if (!STATE_REGEX.matcher(state).matches()) {
            throw new ValueObjectRuleException("Address state is invalid.");
        }

        if (USCodesUtils.isUSCountry(country) && !USCodesUtils.isValidUSState(state)) {
            throw new ValueObjectRuleException("Address state must be a valid state code when country is US.");
        }
    }

    private void validatePostalCode() {
        if (isNull(postalCode) || postalCode.isBlank()) {
            return;
        }

        if (!POSTAL_CODE_PATTERN.matcher(postalCode).matches()) {
            throw new ValueObjectRuleException("Address postalCode is invalid.");
        }

        if (USCodesUtils.isUSCountry(country) && !USCodesUtils.isValidUSPostalCode(postalCode)) {
            throw new ValueObjectRuleException("Address postalCode must be a valid postal code when country is US.");
        }
    }

}
