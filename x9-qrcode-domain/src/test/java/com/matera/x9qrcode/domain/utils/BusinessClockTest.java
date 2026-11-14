/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0033: business time is read from one clock, which a test deployment can shift.
 */
class BusinessClockTest {

    private static final long ONE_DAY = Duration.ofDays(1).toMillis();

    @AfterEach
    void resetClock() {
        BusinessClock.reset();
    }

    @Test
    void withNoOffsetBusinessTimeIsRealTime() {
        Instant before = Instant.now();
        OffsetDateTime now = DateTimeUtils.nowUTC();
        Instant after = Instant.now();

        assertEquals(0, BusinessClock.offsetMillis());
        assertTrue(!now.toInstant().isBefore(before) && !now.toInstant().isAfter(after));
        assertEquals(ZoneOffset.UTC, now.getOffset());
    }

    /** S2.1-AC4: {@code DateTimeUtils.nowUTC()} uses the offset. */
    @Test
    void nowUtcIncludesTheOffset() {
        BusinessClock.setOffsetMillis(ONE_DAY);

        Instant real = Instant.now();
        OffsetDateTime shifted = DateTimeUtils.nowUTC();

        long drift = Duration.between(real.plusMillis(ONE_DAY), shifted.toInstant()).toMillis();
        assertTrue(drift >= 0 && drift < 1_000, "nowUTC must be real time plus one day, drift was " + drift + " ms");
        assertEquals(ZoneOffset.UTC, shifted.getOffset());
    }

    /** ADR-0033: the clock is shifted, never frozen, so it keeps ticking. */
    @Test
    void aShiftedClockKeepsTicking() throws InterruptedException {
        BusinessClock.setOffsetMillis(-ONE_DAY);

        Instant first = BusinessClock.clock().instant();
        Thread.sleep(20);
        Instant second = BusinessClock.clock().instant();

        assertTrue(second.isAfter(first));
    }

    @Test
    void resetRestoresRealTime() {
        BusinessClock.setOffsetMillis(ONE_DAY);
        BusinessClock.reset();

        assertEquals(0, BusinessClock.offsetMillis());
        long drift = Duration.between(Instant.now(), DateTimeUtils.nowUTC().toInstant()).abs().toMillis();
        assertTrue(drift < 1_000);
    }

    @Test
    void setAtShiftsTheClockToThatInstant() {
        Instant target = Instant.parse("2031-12-31T23:59:50Z");

        BusinessClock.setAt(target);

        long drift = Duration.between(target, BusinessClock.clock().instant()).abs().toMillis();
        assertTrue(drift < 1_000, "the clock must read the requested instant; drift " + drift + " ms");
    }

    /**
     * Story 2.1 follow-up: {@code at} keeps its milliseconds. Straight after the move the clock reads
     * the instant asked for, never earlier, and within a few milliseconds of it — not within the
     * ±500 ms that rounding to the second allowed.
     */
    @Test
    void setAtKeepsMillisecondsAndNeverReadsEarlier() {
        Instant target = Instant.parse("2031-12-31T23:59:50.001Z");

        BusinessClock.setAt(target);
        Instant read = BusinessClock.clock().instant();

        assertFalse(read.isBefore(target), "read " + read + " is before " + target);
        assertTrue(Duration.between(target, read).toMillis() < 50, "read " + read + " is too far after " + target);
    }

    /** Story 2.1 follow-up: setting E + 1 ms guarantees every later read is after E, which is what edge tests need. */
    @Test
    void oneMillisecondPastAnEdgeIsAfterTheEdge() {
        Instant edge = Instant.parse("2030-06-30T12:00:00Z");

        BusinessClock.setAt(edge.plusMillis(1));

        assertTrue(BusinessClock.clock().instant().isAfter(edge));
    }

    @Test
    void anInstantFinerThanAMillisecondIsRefused() {
        Instant subMillisecond = Instant.parse("2031-12-31T23:59:50.000500Z");

        assertThrows(IllegalArgumentException.class, () -> BusinessClock.setAt(subMillisecond));
        assertEquals(0, BusinessClock.offsetMillis());
    }

    @Test
    void anInstantTooFarAwayIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BusinessClock.setAt(Instant.MAX.truncatedTo(ChronoUnit.MILLIS)));
        assertEquals(0, BusinessClock.offsetMillis());
    }

    @Test
    void anAbsurdOffsetIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BusinessClock.setOffsetMillis(Long.MAX_VALUE));
    }

}
