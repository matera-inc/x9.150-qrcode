/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.validatesignature;

import com.matera.x9qrcode.app.dto.enumerated.SignatureProblemEnumDTO;

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
    String signerSubject,

    /**
     * Why the signature did not verify, as a fixed code from a closed list, or null when it did. For
     * a caller of this service who asked to be told (the outbound notification's
     * {@code signatureProblem}); never echoed to an unauthenticated sender.
     */
    SignatureProblemEnumDTO problem,

    /**
     * The same reason in words, for a person reading a log or a report (the outbound notification's
     * {@code signatureDetail}); null when the signature verified. Not meant for parsing.
     */
    String problemDetail
) {

    public static SignatureValidationOutput validSignature(UUID correlationId) {
        return new SignatureValidationOutput(true, correlationId, null, null, null, null);
    }

    public static SignatureValidationOutput validSignature(UUID correlationId, String submittedQrCodeContent) {
        return new SignatureValidationOutput(true, correlationId, submittedQrCodeContent, null, null, null);
    }

    public static SignatureValidationOutput validSignature(UUID correlationId, String submittedQrCodeContent,
                                                           String signerSubject) {
        return new SignatureValidationOutput(true, correlationId, submittedQrCodeContent, signerSubject, null, null);
    }

    public static SignatureValidationOutput invalidSignature(SignatureProblemEnumDTO problem, String problemDetail) {
        return new SignatureValidationOutput(false, null, null, null, problem, problemDetail);
    }

}
