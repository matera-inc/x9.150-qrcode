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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0033: business time is read from one clock, which a test deployment can shift.
 */
class BusinessClockTest {

    private static final long ONE_DAY = Duration.ofDays(1).toSeconds();

    @AfterEach
    void resetClock() {
        BusinessClock.reset();
    }

    @Test
    void withNoOffsetBusinessTimeIsRealTime() {
        Instant before = Instant.now();
        OffsetDateTime now = DateTimeUtils.nowUTC();
        Instant after = Instant.now();

        assertEquals(0, BusinessClock.offsetSeconds());
        assertTrue(!now.toInstant().isBefore(before) && !now.toInstant().isAfter(after));
        assertEquals(ZoneOffset.UTC, now.getOffset());
    }

    /** S2.1-AC4: {@code DateTimeUtils.nowUTC()} uses the offset. */
    @Test
    void nowUtcIncludesTheOffset() {
        BusinessClock.setOffsetSeconds(ONE_DAY);

        Instant real = Instant.now();
        OffsetDateTime shifted = DateTimeUtils.nowUTC();

        long drift = Duration.between(real.plusSeconds(ONE_DAY), shifted.toInstant()).toMillis();
        assertTrue(drift >= 0 && drift < 1_000, "nowUTC must be real time plus one day, drift was " + drift + " ms");
        assertEquals(ZoneOffset.UTC, shifted.getOffset());
    }

    /** ADR-0033: the clock is shifted, never frozen, so it keeps ticking. */
    @Test
    void aShiftedClockKeepsTicking() throws InterruptedException {
        BusinessClock.setOffsetSeconds(-ONE_DAY);

        Instant first = BusinessClock.clock().instant();
        Thread.sleep(20);
        Instant second = BusinessClock.clock().instant();

        assertTrue(second.isAfter(first));
    }

    @Test
    void resetRestoresRealTime() {
        BusinessClock.setOffsetSeconds(ONE_DAY);
        BusinessClock.reset();

        assertEquals(0, BusinessClock.offsetSeconds());
        long drift = Duration.between(Instant.now(), DateTimeUtils.nowUTC().toInstant()).abs().toMillis();
        assertTrue(drift < 1_000);
    }

    @Test
    void setAtShiftsTheClockToThatInstant() {
        Instant target = Instant.parse("2031-12-31T23:59:50Z");

        BusinessClock.setAt(target);

        long drift = Duration.between(target, BusinessClock.clock().instant()).abs().toMillis();
        assertTrue(drift < 1_000, "the clock must read the requested instant, to the second; drift " + drift + " ms");
    }

    @Test
    void anAbsurdOffsetIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BusinessClock.setOffsetSeconds(Long.MAX_VALUE));
    }

}
