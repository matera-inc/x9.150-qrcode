/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto.enumerated;

/**
 * What a payee's answer to a payment notification turned out to be, once checked.
 *
 * <p>ANSI X9.150 A.4.5 asks the payee to answer with a signed {@code {"statusCode": integer}}, so
 * the payer holds the payee's decision in a form anyone with the public certificates can verify.
 * These values say whether the answer received is that.
 */
public enum AnswerSignatureEnumDTO {

    /** A JWS that verifies, carries the correlationId sent, and signs the HTTP status it came with. */
    VERIFIED,

    /** Not a JWS at all: empty, plain JSON or plain text. An unsigned answer. */
    ABSENT,

    /** Presented as a JWS but failing a check. Reported, and treated as unsigned. */
    INVALID
}
