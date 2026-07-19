/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.service;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SignatureConstants {

    public static final String X9_KEY_ID = "x9-key";
    public static final long SECONDS_TO_MILLIS_VALUE = 1000L;
    public static final String DEFAULT_PKIX_ALGORITHM = "PKIX";

    // JWS Key IDs and Context Labels
    public static final String X9_SIGNATURE_PAYLOAD_LABEL = "sig-x9";
    public static final String X9_SIGNATURE_EXTERNAL_LABEL = "external-sig";

    // Custom JWS/JOSE Header Parameters
    public static final String JWS_HEADER_CORRELATION_ID = "correlationId";
    public static final String JWS_HEADER_IAT = "iat";
    public static final String JWS_HEADER_TTL = "ttl";
    public static final String JWS_HEADER_STATUS_CODE= "statusCode";

    // Custom JWS/JOSE Payload Property
    public static final String JWS_PAYLOAD_QR_CODE_CONTENT = "qrCodeContent";

    // Http Header Constants
    public static final String CONTENT_DISPOSITION_HEADER = "Content-Disposition";
    public static final String CONTENT_TYPE_HEADER = "Content-Type";
    public static final String HEADER_PAYLOAD_BASED_DATE = "X-Payload-Based-Date";

    // PEM Format Constants
    public static final int PEM_LINE_LENGTH = 64;
    public static final String PEM_FILE_EXTENSION = ".pem";
    public static final String PEM_LINE_BREAK = "\n";
    public static final String PEM_BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----";
    public static final String PEM_END_CERTIFICATE = "-----END CERTIFICATE-----";
    public static final String PEM_CONTENT_TYPE_HEADER_VALUE = "application/x-pem-file";
    public static final String PEM_ATTACHMENT_FILENAME_HEADER_VALUE = "attachment; filename=\"%s\"";

}
