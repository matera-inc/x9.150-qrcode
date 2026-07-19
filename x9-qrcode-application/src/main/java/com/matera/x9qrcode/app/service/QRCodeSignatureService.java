/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.service;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.SignatureOutputDataDTO;
import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationInput;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationOutput;

import java.security.cert.X509Certificate;
import java.util.Map;

public interface QRCodeSignatureService {

    /**
     * Signs arbitrary input data and returns a JWS structure.
     *
     * @param content Input data DTO (containing payload and signature type).
     * @return DTO containing the JWS Compact Serialization string.
     */
    SignatureOutputDataDTO signData(SignatureInputDataDTO content) throws ServiceException;

    /**
     * Validates a JWS signature against a specific content expectation.
     * Used when the JWS might be detached or requires context validation.
     *
     * @param input The validation input data (Content + JWS Token).
     * @return SignatureValidationOutput containing isValid and correlationId.
     */
    SignatureValidationOutput validateSignature(SignatureValidationInput input);

    /**
     * Retrieves the Public Certificate used for verification.
     */
    byte[] retrieveDigitalSignatureCertificate() throws ServiceException;

    /**
     * Retrieves the JWK Set (JSON Web Key Set) for verification.
     */
    Map<String, Object> retrieveDigitalSignatureJwkSet() throws ServiceException;

}
