/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class USCodesUtils {

    private static final String US_CODE = "US";
    private static final String US_NAME = "United States";
    private static final Pattern US_POSTAL_CODE_PATTERN = Pattern.compile("^\\d{5}(-\\d{4})?$");
    private static final List<String> US_STATE_CODES = List.of(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE",
        "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS",
        "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS",
        "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY",
        "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
        "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV",
        "WI", "WY", "DC"
    );
    private static final List<String> US_STATE_NAMES = List.of(
        "Alabama", "Alaska", "Arizona", "Arkansas", "California",
        "Colorado", "Connecticut", "Delaware", "Florida", "Georgia",
        "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa",
        "Kansas", "Kentucky", "Louisiana", "Maine", "Maryland",
        "Massachusetts", "Michigan", "Minnesota", "Mississippi", "Missouri",
        "Montana", "Nebraska", "Nevada", "New Hampshire", "New Jersey",
        "New Mexico", "New York", "North Carolina", "North Dakota", "Ohio",
        "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island", "South Carolina",
        "South Dakota", "Tennessee", "Texas", "Utah", "Vermont",
        "Virginia", "Washington", "West Virginia", "Wisconsin", "Wyoming"
    );

    public static boolean isUSCountry(String country) {
        if (isNull(country) || country.isBlank()) {
            return false;
        }

        return country.equalsIgnoreCase(US_CODE) || country.equalsIgnoreCase(US_NAME);
    }

    public static boolean isValidUSState(String state) {
        if (isNull(state) || state.isBlank()) {
            return false;
        }

        if (state.length() == 2) {
            return US_STATE_CODES.contains(state.toUpperCase());
        }

        return US_STATE_NAMES.stream().anyMatch(name -> name.equalsIgnoreCase(state));
    }

    public static boolean isValidUSPostalCode(String postalCode) {
        if (isNull(postalCode) || postalCode.isBlank()) {
            return false;
        }

        return US_POSTAL_CODE_PATTERN.matcher(postalCode).matches();
    }

}
