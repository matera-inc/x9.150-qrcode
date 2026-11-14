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
 * <p><b>What happens when {@code payerInfo} is absent: the two are different payers</b>
 * (ADR-0036, replacing ADR-0021's certificate-only fallback). Without {@code payer.info} nothing
 * tells two customers of one PSP apart, and matching on the certificate alone let either retry or
 * release the other's reservation: the collision the reservation exists to prevent. So a payer
 * that sends no {@code payer.info} may still win a pre-commit and hold the QR Code, but it cannot
 * retry that pre-commit (409), release it with {@code NOT_SENT}, or report its post-commit as the
 * holder (it is registered with {@code RECEIVED_IN_WRONG_STATUS}). Sending {@code payer.info} is
 * what lets a PSP's payers retry, release and report cleanly.
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
     * Whether {@code other} is the same payer as this one (ADR-0036).
     *
     * <p>Only when <b>both</b> carry {@code payer.info}, the two values are equal, <b>and</b> the
     * certificate subjects are equal. Without {@code payer.info} on either side there is no means
     * to tell two payers apart, so they are assumed to be different. An unattributed holder (a
     * reservation taken through the status-update API) therefore matches nobody.
     */
    public boolean isSameParty(ReservationHolderVO other) {
        if (isNull(other) || isBlank(this.payerInfo) || isBlank(other.payerInfo)) {
            return false;
        }

        return this.payerInfo.equals(other.payerInfo)
            && Objects.equals(this.signerSubject, other.signerSubject);
    }

    private static boolean isBlank(String value) {
        return isNull(value) || value.isBlank();
    }

}
