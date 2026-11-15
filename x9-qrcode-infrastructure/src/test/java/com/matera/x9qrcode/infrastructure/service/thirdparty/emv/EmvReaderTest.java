/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.emv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The EMV reader refuses exactly the inputs the Go build's {@code internal/emv} refuses
 * ({@code TestDecodeRefusesMalformedInput}), case for case and under the same names.
 */
public class EmvReaderTest {

    private static final String GUI = "org.x9";
    private static final String URL_SUB_TAG = "01";

    /** The first EMV string of the golden set (others/blackbox/golden/manifest.json). */
    static final String GOOD = "00020101021226800006org.x90166x9-150.example.com/pub/api/v1/loc/"
        + "01A1DC2A6A37D73D6313076E9BFE04F5520449005303840540105802US5911Blackbox Co6011Los Angeles6304AA0B";

    private static final String X9 =
        "26800006org.x90166x9-150.example.com/pub/api/v1/loc/01A1DC2A6A37D73D6313076E9BFE04F5";

    private static final String GOOD_URL = "x9-150.example.com/pub/api/v1/loc/01A1DC2A6A37D73D6313076E9BFE04F5";

    public enum Refusal { MALFORMED, NO_X9 }

    /** Appends tag 63 and the CRC of everything before its value, as an encoder would. */
    static String withCrc(String body) {
        String upTo = body + "6304";
        int crc = 0xFFFF;

        for (byte b : upTo.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;

            for (int i = 0; i < 8; i++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }

        return upTo + "%04X".formatted(crc);
    }

    private static String withoutCrcValue(String emv) {
        return emv.substring(0, emv.length() - 4);
    }

    /** Go's malformed cases, by Go's names. "not UTF-8" is what a Java string holds after decoding "\xff\xfe". */
    public static Stream<Arguments> malformed() {
        String shortCrc = withCrc("000201");

        return Stream.of(
            Arguments.of("empty", "", Refusal.MALFORMED),
            Arguments.of("bad CRC", withoutCrcValue(GOOD) + "0000", Refusal.MALFORMED),
            Arguments.of("CRC not hex", withoutCrcValue(GOOD) + "ZZZZ", Refusal.MALFORMED),
            Arguments.of("no CRC", GOOD.substring(0, GOOD.length() - 8), Refusal.MALFORMED),
            Arguments.of("CRC not last", withCrc("6304AAAA" + "000201"), Refusal.MALFORMED),
            Arguments.of("CRC too short", shortCrc.substring(0, shortCrc.length() - 8) + "6303ABC", Refusal.MALFORMED),
            Arguments.of("truncated", GOOD.substring(0, GOOD.length() - 30), Refusal.MALFORMED),
            Arguments.of("truncated header", "000", Refusal.MALFORMED),
            Arguments.of("length beyond the end", "000501", Refusal.MALFORMED),
            Arguments.of("length not digits", withCrc("00AB01"), Refusal.MALFORMED),
            Arguments.of("length zero", withCrc("0000" + "000201"), Refusal.MALFORMED),
            Arguments.of("tag not digits", withCrc("A00201"), Refusal.MALFORMED),
            Arguments.of("duplicate tag", withCrc("000201000201" + X9), Refusal.MALFORMED),
            Arguments.of("not UTF-8", "��", Refusal.MALFORMED),
            Arguments.of("no template", withCrc("000201010212"), Refusal.NO_X9),
            Arguments.of("wrong GUI", withCrc("000201" + "26220006org.x80108x9.a/loc"), Refusal.NO_X9),
            Arguments.of("org.x9 without URL", withCrc("000201" + "26100006org.x9"), Refusal.NO_X9),
            Arguments.of("template not TLV", withCrc("000201" + "2603abc"), Refusal.MALFORMED),
            Arguments.of("template truncated", withCrc("000201" + "26140006org.x90199"), Refusal.MALFORMED),
            // Java only: a JSON string can carry a lone surrogate, which no UTF-8 input can.
            Arguments.of("lone surrogate", withCrc("000201" + "5901\uD800"), Refusal.MALFORMED),
            // Go's TestDecodeCountsUTF16: a length that splits a surrogate pair.
            Arguments.of("length splits a surrogate pair", withCrc("000201" + "5902Z😀"), Refusal.MALFORMED),
            // A sign is not a hex digit, though Integer.parseInt(..., 16) would take it.
            Arguments.of("CRC with a sign", withoutCrcValue(GOOD) + "+AA0", Refusal.MALFORMED));
    }

    /**
     * <b>X9-DEC-013</b> — a malformed EMV string is refused, never tolerated: the Go build's 19
     * malformed cases, by name, plus three a Java string can carry.
     *
     * <p><b>Source:</b> Ours (owner decision 2026-11-15, ADR-0027 EMV section): "Don't tolerate bad QR
     * Codes." EMVCo's merchant-presented mode defines the TLV layout and the CRC16-CCITT of tag 63;
     * what to do with a string that breaks them is ours.
     *
     * <p><b>Why:</b> The EMV library Java used read a bad CRC, a missing CRC and a truncated string
     * as good, and went on to fetch the payload from the host the string named; a few other
     * breakages escaped as a raw exception and a 500. A scanner rejects every one of these strings,
     * so a decoder that accepts them is answering for a code nobody could have scanned.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    void aMalformedEmvIsRefused(String name, String emv, Refusal refusal) {
        if (refusal == Refusal.MALFORMED) {
            MalformedEmvException e = assertThrows(MalformedEmvException.class,
                () -> EmvReader.readPayloadUrl(emv, GUI, URL_SUB_TAG), name);
            assertEquals("qrCode", e.field());
        } else {
            assertEquals(Optional.empty(), EmvReader.readPayloadUrl(emv, GUI, URL_SUB_TAG), name);
        }
    }

    /**
     * <b>X9-DEC-014</b> — a well-formed EMV string is read: the golden string, its CRC in lower case,
     * an org.x9 template among others, and lengths counted in UTF-16 code units.
     *
     * <p><b>Source:</b> Mechanism. The control for X9-DEC-013, matching Go's
     * {@code TestDecodeFindsX9AmongOthers} and {@code TestDecodeCountsUTF16}.
     *
     * <p><b>Why:</b> A strict reader that also refused good codes would be worse than the lenient one.
     * The first org.x9 template wins, as it did with the library.
     */
    @Test
    void aWellFormedEmvIsRead() {
        assertEquals(Optional.of(GOOD_URL), EmvReader.readPayloadUrl(GOOD, GUI, URL_SUB_TAG));

        String lower = withoutCrcValue(GOOD) + GOOD.substring(GOOD.length() - 4).toLowerCase();
        assertEquals(Optional.of(GOOD_URL), EmvReader.readPayloadUrl(lower, GUI, URL_SUB_TAG));

        String amongOthers = withCrc("000201" + "26220006org.x80108x9.a/loc" + "27220006org.x90108x9.b/loc" + "62070503***");
        assertEquals(Optional.of("x9.b/loc"), EmvReader.readPayloadUrl(amongOthers, GUI, URL_SUB_TAG));

        String utf16 = withCrc("000201" + X9 + "5904Zé😀");
        assertEquals(Optional.of(GOOD_URL), EmvReader.readPayloadUrl(utf16, GUI, URL_SUB_TAG));
    }

}
