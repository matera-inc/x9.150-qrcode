/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testclock;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code x9.testing.*} — the movable test clock (ADR-0033). Bound from {@code X9_TESTING_CLOCKCONTROL}
 * and {@code X9_TESTING_CLOCKLISTEN}.
 */
@Data
@ConfigurationProperties(prefix = "x9.testing")
public class TestClockProperties {

    /** Off by default. When true, {@code /test/clock} is served on {@link #clockListen}. */
    private boolean clockControl;

    /** {@code host:port} of the separate clock listener. Never the API port. */
    private String clockListen = "127.0.0.1:8099";

}
