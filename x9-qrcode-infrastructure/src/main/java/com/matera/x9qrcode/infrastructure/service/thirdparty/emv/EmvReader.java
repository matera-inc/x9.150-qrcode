/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.emv;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reads an EMV merchant-presented string strictly, and refuses one that is not well formed
 * (ADR-0027, EMV: malformed EMV is refused, never tolerated, on both builds).
 *
 * <p>It refuses exactly what the Go build's {@code internal/emv.Decode} refuses: an empty string; a
 * tag or a length that is not two digits; a length of 0; a value that runs past the end or splits a
 * surrogate pair; a tag that appears twice at the top level; tag 63 missing, not last, not four
 * characters, not hex, or not the CRC16-CCITT of everything before it. Lengths count UTF-16 code
 * units, as {@link String#length()} does. A lower-case CRC is read, as Go reads it.
 *
 * <p>The org.x9 template is the first merchant account information template (tags 26 to 51) whose
 * sub-tag 00 is the GUI; each template before it must itself be well-formed TLV. A string with no
 * such template, or whose org.x9 template has no URL, is well formed but carries no location: that
 * is an empty result, not a malformed string.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EmvReader {

    private static final String TAG_CRC = "63";
    private static final String SUB_TAG_GUI = "00";
    private static final int CRC_LENGTH = 4;
    private static final int FIRST_TEMPLATE = 26;
    private static final int LAST_TEMPLATE = 51;

    /** One TLV field: a two-digit tag and its value. */
    public record Field(String tag, String value) { }

    /**
     * The location URL of the string's org.x9 template, or empty when it carries none.
     *
     * @throws MalformedEmvException when the string is not well-formed EMV
     */
    public static Optional<String> readPayloadUrl(String emv, String gui, String urlSubTag) {
        List<Field> fields = readTopLevel(emv);

        for (Field field : fields) {
            int tag = Integer.parseInt(field.tag());

            if (tag < FIRST_TEMPLATE || tag > LAST_TEMPLATE) {
                continue;
            }

            List<Field> template;
            try {
                template = readFields(field.value());
            } catch (MalformedEmvException e) {
                throw new MalformedEmvException("in tag %s, %s".formatted(field.tag(), e.reason()));
            }

            if (!Objects.equals(gui, valueOf(template, SUB_TAG_GUI))) {
                continue;
            }

            return Optional.ofNullable(valueOf(template, urlSubTag));
        }

        return Optional.empty();
    }

    /**
     * The top-level fields of a whole EMV string, tag 63 included, once every top-level rule holds.
     *
     * @throws MalformedEmvException when the string is not well-formed EMV
     */
    public static List<Field> readTopLevel(String emv) {
        List<Field> fields = readFields(emv);

        if (fields.isEmpty()) {
            throw new MalformedEmvException("it is empty");
        }

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            String tag = fields.get(i).tag();

            if (!seen.add(tag)) {
                throw new MalformedEmvException("tag %s appears twice".formatted(tag));
            }
            if (TAG_CRC.equals(tag) && i != fields.size() - 1) {
                throw new MalformedEmvException("the CRC (tag 63) is not the last field");
            }
        }

        Field crc = fields.getLast();

        if (!TAG_CRC.equals(crc.tag())) {
            throw new MalformedEmvException("there is no CRC (tag 63) at the end");
        }
        if (crc.value().length() != CRC_LENGTH) {
            throw new MalformedEmvException("the CRC (tag 63) has %d characters, not 4".formatted(crc.value().length()));
        }
        if (!crc.value().chars().allMatch(EmvReader::isHexDigit)) {
            throw new MalformedEmvException("the CRC (tag 63) is not four hex digits");
        }

        String computed = crc16(emv.substring(0, emv.length() - CRC_LENGTH));
        if (!computed.equalsIgnoreCase(crc.value())) {
            throw new MalformedEmvException("the CRC (tag 63) does not match its content");
        }

        return fields;
    }

    /**
     * A sequence of TLV fields, in order. Refuses a string that is not well-formed UTF-16, a tag or
     * length that is not two digits, a length of 0, and a value that runs past the end or would end
     * inside a surrogate pair.
     *
     * @throws MalformedEmvException when the string is not well-formed TLV
     */
    public static List<Field> readFields(String s) {
        if (Objects.isNull(s)) {
            throw new MalformedEmvException("it is absent");
        }
        if (!isWellFormedUtf16(s)) {
            throw new MalformedEmvException("it holds an unpaired surrogate");
        }

        List<Field> fields = new ArrayList<>();
        int pos = 0;

        while (pos < s.length()) {
            if (s.length() - pos < 4) {
                throw new MalformedEmvException("the field at offset %d is truncated".formatted(pos));
            }

            String tag = s.substring(pos, pos + 2);
            String length = s.substring(pos + 2, pos + 4);

            if (!isTwoDigits(tag)) {
                throw new MalformedEmvException("the tag at offset %d is not two digits".formatted(pos));
            }
            if (!isTwoDigits(length) || "00".equals(length)) {
                throw new MalformedEmvException("tag %s has a length that is not 01 to 99".formatted(tag));
            }

            int start = pos + 4;
            int end = start + Integer.parseInt(length);

            if (end > s.length()) {
                throw new MalformedEmvException("tag %s declares %s characters, and fewer are left".formatted(tag, length));
            }
            if (end < s.length() && Character.isLowSurrogate(s.charAt(end))) {
                throw new MalformedEmvException("tag %s ends inside a character".formatted(tag));
            }

            fields.add(new Field(tag, s.substring(start, end)));
            pos = end;
        }

        return Collections.unmodifiableList(fields);
    }

    /** CRC16-CCITT (poly 0x1021, init 0xFFFF), over the UTF-8 bytes, as four upper-case hex digits. */
    static String crc16(String data) {
        int crc = 0xFFFF;

        for (byte b : data.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;

            for (int i = 0; i < 8; i++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }

        return "%04X".formatted(crc);
    }

    private static String valueOf(List<Field> fields, String tag) {
        return fields.stream().filter(f -> f.tag().equals(tag)).map(Field::value).findFirst().orElse(null);
    }

    private static boolean isTwoDigits(String s) {
        return s.length() == 2 && isDigit(s.charAt(0)) && isDigit(s.charAt(1));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    private static boolean isWellFormedUtf16(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }

        return true;
    }

}
