/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.sendpaymentnotification;

/**
 * Which half of the payment a notification is announcing.
 *
 * <p>Explicit here, although the wire format infers it. ANSI X9.150 has no phase marker — the
 * committee rejected one ({@code docs/adr/0004-phase-inferred-from-transaction-id.md}) — so a payee
 * deduces the phase from whether a transaction reference is present. That inference is forced on the
 * <em>payload</em>; it is not forced on this API, and being explicit here lets a payer's mistake be
 * caught before it reaches anybody else.
 */
public enum PaymentPhase {

    /** Before the money moves: a request for permission, which the payee may refuse. */
    PRE_PAYMENT,

    /** After the money moves: a report of fact, carrying the reference that proves it. */
    POST_PAYMENT
}
