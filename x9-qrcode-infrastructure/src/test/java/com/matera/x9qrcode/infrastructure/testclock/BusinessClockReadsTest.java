/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testclock;

import com.matera.x9qrcode.app.usecase.retrievepayload.RetrieveQRCodePayloadInput;
import com.matera.x9qrcode.domain.utils.BusinessClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S2.1-AC4: the payload's "date for payment" defaults to business time, not the wall clock.
 * (The application module has no test sources of its own; this is a plain unit test.)
 */
class BusinessClockReadsTest {

    @AfterEach
    void resetClock() {
        BusinessClock.reset();
    }

    /** S2.1-AC4: RetrieveQRCodePayloadInput reads the shifted clock when no date is given. */
    @Test
    void theDefaultDateForPaymentIsBusinessTime() {
        BusinessClock.setOffsetMillis(Duration.ofDays(400).toMillis());

        OffsetDateTime dateForPayment = new RetrieveQRCodePayloadInput("id", null).getZonedDateForPayment();

        Instant expected = Instant.now().plus(Duration.ofDays(400));
        assertThat(Duration.between(expected, dateForPayment.toInstant()).abs()).isLessThan(Duration.ofSeconds(2));
        assertEquals(ZoneOffset.UTC, dateForPayment.getOffset());
    }

    @Test
    void anExplicitDateIsNotTouched() {
        BusinessClock.setOffsetMillis(Duration.ofDays(400).toMillis());

        OffsetDateTime dateForPayment =
            new RetrieveQRCodePayloadInput("id", LocalDate.parse("2030-01-02")).getZonedDateForPayment();

        assertEquals(OffsetDateTime.parse("2030-01-02T00:00:00Z"), dateForPayment);
    }

}
