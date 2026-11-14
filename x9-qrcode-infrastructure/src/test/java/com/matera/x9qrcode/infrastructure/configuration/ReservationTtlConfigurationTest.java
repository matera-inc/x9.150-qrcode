/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationTtlConfigurationTest {

    /**
     * <b>X9-HOLD-323</b> — a negative x9.reservation.ttl-seconds refuses to start.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (owner 2026-11-14: a negative value is invalid).
     *
     * <p><b>Why:</b> A negative window would make every reservation lapse before it was taken,
     * silently disabling the protection against two payers; failing the bean that both use cases
     * need stops the service at startup instead.
     */
    @Test
    void aNegativeWindowIsAnInvalidConfiguration() {
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
            () -> UseCaseConfiguration.reservationTtl(-1));

        assertTrue(invalid.getMessage().contains("X9_RESERVATION_TTL_SECONDS"), invalid.getMessage());
    }

    /**
     * <b>X9-HOLD-324</b> — 0 and positive values of x9.reservation.ttl-seconds are accepted as they are.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC7).
     *
     * <p><b>Why:</b> The negative control: 0 is "no window", not an error.
     */
    @Test
    void zeroAndPositiveWindowsAreAccepted() {
        assertEquals(Duration.ZERO, UseCaseConfiguration.reservationTtl(0));
        assertEquals(Duration.ofSeconds(90), UseCaseConfiguration.reservationTtl(90));
    }

}
