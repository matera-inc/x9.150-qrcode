/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.validatesignature;

import java.util.UUID;

/**
 * Output DTO for signature validation containing the validation result
 * and the correlationId extracted from the JWS header.
 *
 * @param isValid       Whether the signature is valid.
 * @param correlationId The correlationId extracted from the JWS header.
 */
public record SignatureValidationOutput(
    boolean isValid,
    UUID correlationId,

    /**
     * The decoded EMV string the caller put in the signed body, or null when the request carries
     * none. Carried out of validation so the use case can compare it against the content actually
     * issued — the signature service has no repository and cannot make that comparison itself.
     */
    String submittedQrCodeContent,

    /**
     * The subject of the certificate that signed this JWS, once its chain has been validated.
     *
     * <p>Carried out of validation for the same reason as the content above: the use case needs it
     * and the signature service cannot act on it. This is the one identity in a payment
     * notification the sender did not simply assert — {@code payer.info} is a field in the body,
     * while this is whoever the trusted chain says signed it. Null when the path could not name a
     * subject, which reads as "no identity established".
     */
    String signerSubject
) {

    public static SignatureValidationOutput validSignature(UUID correlationId) {
        return new SignatureValidationOutput(true, correlationId, null, null);
    }

    public static SignatureValidationOutput validSignature(UUID correlationId, String submittedQrCodeContent) {
        return new SignatureValidationOutput(true, correlationId, submittedQrCodeContent, null);
    }

    public static SignatureValidationOutput validSignature(UUID correlationId, String submittedQrCodeContent,
                                                           String signerSubject) {
        return new SignatureValidationOutput(true, correlationId, submittedQrCodeContent, signerSubject);
    }

    public static SignatureValidationOutput invalidSignature() {
        return new SignatureValidationOutput(false, null, null, null);
    }

}
