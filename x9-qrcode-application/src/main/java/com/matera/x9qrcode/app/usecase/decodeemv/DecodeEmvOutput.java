/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.decodeemv;

import java.net.URI;

/**
 * Output for the Decode EMV Use Case.
 * <p>
 * Updated for JWS:
 * - Removed `headers` map (legacy HTTP-SIG headers are no longer needed).
 * - Added `jwsToken` which encapsulates the signature and metadata.
 */
public record DecodeEmvOutput(
    URI decodedLocation,
    Integer statusCode,
    String jwsToken
) {

}
