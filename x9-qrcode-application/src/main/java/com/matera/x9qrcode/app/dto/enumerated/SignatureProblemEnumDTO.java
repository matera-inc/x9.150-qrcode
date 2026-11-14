/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto.enumerated;

/**
 * Why a JWS did not verify: one fixed code from a closed list, so a caller can act on the category
 * and a test can assert on it. The words for a person reading a log travel separately, as a detail.
 *
 * <p>The verifier is shared: it checks the questions this service receives, the payloads it
 * decodes, and the answers payees give to the notifications it sends. Most codes can come from any
 * of them. {@link #ABSENT_BODY}, {@link #STATUS_MISSING} and {@link #STATUS_MISMATCH} are about a
 * payee's answer only, and {@link #CONTENT_MISMATCH} about a payload request only.
 */
public enum SignatureProblemEnumDTO {

    /** There is nothing to check: the body is empty. */
    ABSENT_BODY,

    /** The body is not a JOSE object at all: plain JSON, plain text, or garbage. */
    NOT_JWS,

    /** An unsecured JOSE object, {@code alg: none}: shaped like a JWS, carrying no signature. */
    ALG_NONE,

    /** An encrypted JOSE object (JWE), not a signed one. */
    ENCRYPTED,

    /**
     * The protected header breaks the X9.150 profile: {@code crit} missing, incomplete or naming a
     * parameter we do not understand; {@code correlationId}, {@code iat}, {@code ttl},
     * {@code statusCode} or {@code x5t#S256} missing or malformed; no way to find the certificate; or
     * an {@code alg} the key or this verifier does not support.
     */
    BAD_HEADER,

    /** The signer's certificate or key could not be fetched or found ({@code x5u}, {@code jku}, {@code kid}). */
    CERTIFICATE_UNAVAILABLE,

    /**
     * The signer's certificate is not one to trust: the chain does not validate to a trusted root,
     * the issuer is not the expected one, the certificate is expired, not yet valid or revoked, it
     * lacks the digitalSignature key usage, or it does not match the header's thumbprint.
     */
    UNTRUSTED_CHAIN,

    /** The signature does not verify over the header and payload: altered, or signed by another key. */
    BAD_SIGNATURE,

    /** {@code iat + ttl} has passed by the verifier's clock. */
    EXPIRED,

    /** {@code iat} is ahead of the verifier's clock by more than the allowed skew. */
    FUTURE_IAT,

    /** A valid signature under a {@code correlationId} other than the one expected: a reply to another question. */
    CORRELATION_ID_MISMATCH,

    /** A payload request whose signed QR content does not name the location asked for. */
    CONTENT_MISMATCH,

    /** A payee's answer whose signed body has no integer {@code statusCode} (ANSI X9.150 A.4.5 note). */
    STATUS_MISSING,

    /** A payee's answer whose signed {@code statusCode} differs from the HTTP status it came with. */
    STATUS_MISMATCH,

    /** The check failed for a reason none of the codes above names. The detail says what happened. */
    UNVERIFIABLE
}
