/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.utils;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import java.nio.ByteBuffer;
import java.util.UUID;

import static java.util.Objects.nonNull;

public class UUIDUtils {

    public static UUID parse(String id, String fieldName) {
        try {
            if (nonNull(id) && id.length() == 32) {
                id = id.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                    "$1-$2-$3-$4-$5"
                );
            }
            return UUID.fromString(id);
        } catch (Exception e) {
            throw new BusinessRuleException(fieldName, "Given format is invalid: " + id);
        }
    }

    public static String toShortenString(UUID id) {
        if (nonNull(id)) {
            return id.toString().replace("-", "").toUpperCase();
        }

        return null;
    }

    public static byte[] toByteArray(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    public static UUID fromByteArray(byte[] decoded) {
        ByteBuffer bb = ByteBuffer.wrap(decoded);
        long mostSigBits = bb.getLong();
        long leastSigBits = bb.getLong();
        return new UUID(mostSigBits, leastSigBits);
    }

}
