/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import java.util.Objects;

import static java.util.Objects.isNull;

/**
 * Who is holding a reservation, so that a second announcement can be told from a repeated one.
 *
 * <p>The reservation exists so two payers cannot both believe they are paying one bill. Until this
 * record existed the rule was enforced on the status alone — <em>anybody</em> announcing on a
 * reserved QR Code was refused — which protects against the second payer and punishes the first:
 * a payer whose request timed out, or whose process crashed between sending and recording, could
 * not retry the thing they had already done. The commonest reason to see the same announcement
 * twice is not a second payer. It is one payer, twice.
 *
 * <p><b>Two parts, and the second is the one that cannot be forged.</b>
 *
 * <ul>
 *   <li>{@code payerInfo} — ANSI X9.150-2026 §3.1 {@code payer.info}, whatever the payer's PSP put
 *       there. Self-asserted: it is a field in a body, and the signer chose its contents.</li>
 *   <li>{@code signerSubject} — the subject of the certificate that signed the JWS. Not
 *       self-asserted in the same way: the chain was validated against the trusted roots, so this
 *       identifies the institution whatever the body claims.</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> the blockchain {@code from} address. A PSP settling USDC sends every
 * customer's payment from one hot wallet, so the sending address identifies the PSP and says
 * nothing about which of its customers this is.
 *
 * <p><b>What happens when {@code payerInfo} is absent.</b> Identity falls back to the certificate
 * alone, which means the granularity drops from "this payer" to "this payer's bank" — and two
 * customers of one PSP then read as the same party, so either may refresh or release the other's
 * reservation. That is the best this service can do with what it was given, and it is the concrete
 * reason for a PSP to send {@code payer.info}: without it, its own customers tread on each other.
 */
public record ReservationHolderVO(String payerInfo, String signerSubject) {

    /**
     * The holder of a reservation taken through the payee's own API rather than by a payer.
     *
     * <p>Nobody identifiable holds it. A payee marking a QR Code {@code PAYMENT_INITIATED} is
     * recording a reservation it made somewhere else, and this service has no party to attribute
     * it to — so no payer matches it, and a payer's announcement is refused rather than being
     * allowed to adopt somebody else's hold.
     */
    public static ReservationHolderVO unattributed() {
        return new ReservationHolderVO(null, null);
    }

    public static ReservationHolderVO of(String payerInfo, String signerSubject) {
        return new ReservationHolderVO(payerInfo, signerSubject);
    }

    /** Whether anything at all is known about who holds this. */
    public boolean isIdentified() {
        return !isNull(payerInfo) || !isNull(signerSubject);
    }

    /**
     * Whether {@code other} is the same party as this one.
     *
     * <p>Both parts must agree, and an unidentified holder matches nobody — including another
     * unidentified one. Two anonymous announcements are not evidence of being the same payer, and
     * treating them as such would hand one payer's reservation to the next caller who sent no
     * identity at all, which is precisely the case the reservation exists to prevent.
     */
    public boolean isSameParty(ReservationHolderVO other) {
        if (isNull(other) || !this.isIdentified() || !other.isIdentified()) {
            return false;
        }

        return Objects.equals(this.payerInfo, other.payerInfo)
            && Objects.equals(this.signerSubject, other.signerSubject);
    }

}
