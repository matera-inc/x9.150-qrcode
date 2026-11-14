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
 * <p>The clock is the real UTC clock shifted by a whole number of seconds, so it keeps ticking. The
 * offset is zero unless a <b>test</b> deployment moves it over {@code /test/clock}, which exists only
 * when {@code X9_TESTING_CLOCKCONTROL=true} and the signing certificate is a bundled demo or test-CA
 * one. Nothing else in the code sets it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BusinessClock {

    /** About a century. Large enough for any scenario; small enough that {@code Instant} arithmetic never overflows. */
    static final long MAX_OFFSET_SECONDS = Duration.ofDays(36_525).toSeconds();

    private static volatile long offsetSeconds;

    /** The business clock: real UTC time plus the current offset. */
    public static Clock clock() {
        long offset = offsetSeconds;
        return offset == 0 ? Clock.systemUTC() : Clock.offset(Clock.systemUTC(), Duration.ofSeconds(offset));
    }

    public static long offsetSeconds() {
        return offsetSeconds;
    }

    public static void setOffsetSeconds(long seconds) {
        if (Math.abs(seconds) > MAX_OFFSET_SECONDS) {
            throw new IllegalArgumentException(
                "offsetSeconds must be within ±%d seconds".formatted(MAX_OFFSET_SECONDS));
        }
        offsetSeconds = seconds;
    }

    /** Shifts the clock so it reads {@code at} now, to the nearest second, and keeps ticking from there. */
    public static void setAt(Instant at) {
        Objects.requireNonNull(at, "at");
        Duration shift = Duration.between(Instant.now(), at);
        setOffsetSeconds(shift.getSeconds() + (shift.getNano() >= 500_000_000 ? 1 : 0));
    }

    public static void reset() {
        offsetSeconds = 0;
    }

}
