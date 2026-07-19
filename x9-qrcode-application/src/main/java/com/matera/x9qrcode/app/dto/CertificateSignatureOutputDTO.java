/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto;

/**
 * Output DTO for the signature operation.
 * <p>
 * Updated for JWS Migration:
 * - Removed legacy HTTP-SIG fields (Digest, Signature-Input, etc).
 * - Contains only the self-contained JWS Token.
 */
public record CertificateSignatureOutputDTO(String jwsToken) {

}
