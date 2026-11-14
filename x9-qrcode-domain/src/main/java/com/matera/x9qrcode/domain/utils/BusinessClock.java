/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The one clock business time is read from (ADR-0033).
 *
 * <p>Every decision that depends on "now" — acceptance, the formula, validUntil, the reservation
 * lapse, the effective status, JWS {@code iat}/{@code ttl}, timestamps written to the database — reads
 * {@link #clock()}, directly or through {@link DateTimeUtils#nowUTC()}. Request logging, lease timers
 * and long-poll waits measure real durations and keep the real clock.
 *
 * <p>The clock is the real UTC clock shifted by a whole number of <b>milliseconds</b>, so it keeps
 * ticking, and an edge such as "accepted at E, refused at E + 1 ms" can be reached. The offset is zero
 * unless a <b>test</b> deployment moves it over {@code /test/clock}, which exists only when
 * {@code X9_TESTING_CLOCKCONTROL=true} and the signing certificate is one bundled with this repository.
 * Nothing else in the code sets it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BusinessClock {

    /** About a century. Large enough for any scenario; small enough that {@code Instant} arithmetic never overflows. */
    static final long MAX_OFFSET_MILLIS = Duration.ofDays(36_525).toMillis();

    private static volatile long offsetMillis;

    /** The business clock: real UTC time plus the current offset. */
    public static Clock clock() {
        long offset = offsetMillis;
        return offset == 0 ? Clock.systemUTC() : Clock.offset(Clock.systemUTC(), Duration.ofMillis(offset));
    }

    /** The current offset from real time, in milliseconds. */
    public static long offsetMillis() {
        return offsetMillis;
    }

    public static void setOffsetMillis(long millis) {
        if (Math.abs(millis) > MAX_OFFSET_MILLIS) {
            throw new IllegalArgumentException(
                "the offset must be within ±%d milliseconds".formatted(MAX_OFFSET_MILLIS));
        }
        offsetMillis = millis;
    }

    /**
     * Shifts the clock so it reads {@code at} now, and keeps ticking from there.
     *
     * <p>The offset is {@code at} minus the real clock, both in whole milliseconds, so straight after
     * this call the clock reads {@code at} plus less than one millisecond — never earlier than
     * {@code at}. Setting {@code at} to E + 1 ms therefore guarantees every later read is after E.
     *
     * @throws IllegalArgumentException if {@code at} is finer than a millisecond, or too far away
     */
    public static void setAt(Instant at) {
        Objects.requireNonNull(at, "at");
        if (at.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("at must be given to the millisecond at most, e.g. 2031-12-31T23:59:59.999Z");
        }
        long shift;
        try {
            shift = Math.subtractExact(at.toEpochMilli(), Clock.systemUTC().millis());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                "the offset must be within ±%d milliseconds".formatted(MAX_OFFSET_MILLIS), e);
        }
        setOffsetMillis(shift);
    }

    public static void reset() {
        offsetMillis = 0;
    }

}
