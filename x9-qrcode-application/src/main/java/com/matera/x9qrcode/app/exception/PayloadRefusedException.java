/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.exception;

import java.util.Collections;
import java.util.List;

/**
 * The payee's service answered, and the answer was a refusal.
 *
 * <p>The refusal is the useful part. A payer scanning a QR Code gets back one of a handful of very
 * different situations, and each calls for a different thing from the person holding the phone:
 *
 * <ul>
 *   <li><b>already paid</b> — stop; somebody has settled this bill;</li>
 *   <li><b>cancelled</b> — stop; the biller withdrew it;</li>
 *   <li><b>expired</b> — ask the biller for a fresh code;</li>
 *   <li><b>content does not match</b> — stop, and do not pay: this code is not the one issued.</li>
 * </ul>
 *
 * <p>Collapsing those into "something went wrong" is not merely unhelpful, it is wrong in a way the
 * caller cannot detect: a payer told that a system failed will retry, and the one case where
 * retrying is dangerous — a tampered code — looks exactly like the three where it is harmless.
 *
 * <p>Separate from {@link PayloadUnreachableException} for the same reason
 * {@link NotificationUndeliverableException} is separate from a refusal: "they said no" and "we
 * never got an answer" are opposite instructions wearing the same clothes.
 *
 * <p><b>The violations are somebody else's words.</b> The payload location comes out of the scanned
 * QR Code, so the server that produced them is whichever host that code names — not necessarily
 * this deployment. They are relayed because they are the only account of what happened, and they
 * are capped and reduced to plain strings on the way through. A consumer should render them as a
 * quotation, never execute or trust them.
 */
public class PayloadRefusedException extends ServiceException {

    private final int upstreamStatus;
    private final List<String> violations;

    public PayloadRefusedException(int upstreamStatus, List<String> violations, Throwable cause) {
        super("The payee's service refused to release this payload.", cause);
        this.upstreamStatus = upstreamStatus;
        this.violations = List.copyOf(violations);
    }

    /** The status the payee's service answered with, relayed so the caller can act on it. */
    public int upstreamStatus() {
        return upstreamStatus;
    }

    public List<String> violations() {
        return Collections.unmodifiableList(violations);
    }

}
