/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto;

import java.util.UUID;

/**
 * Output DTO for generic data signing operations.
 * <p>
 * Updated for JWS:
 * Returns the complete JWS Compact Serialization string.
 * The complex map of HTTP headers is no longer needed as metadata is
 * now self-contained within the JWS Header.
 */
public record SignatureOutputDataDTO(String jwsToken, UUID correlationId) {

}
