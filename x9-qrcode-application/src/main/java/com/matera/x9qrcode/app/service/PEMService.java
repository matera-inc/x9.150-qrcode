/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.service;

import java.security.cert.X509Certificate;
import java.util.List;

public interface PEMService {

    /**
     * Generates a PEM formatted byte array from an array of X509Certificate objects.
     *
     * @param certificates Array of X509Certificate objects
     * @return PEM formatted certificate chain as byte array
     */
    byte[] generate(X509Certificate[] certificates);

    /**
     * Converts PEM formatted byte array into a list of X509Certificate objects.
     *
     * @param pemBytes PEM formatted certificate chain as byte array
     * @return List of X509Certificate objects
     * @throws Exception if parsing fails
     */
    List<X509Certificate> parse(byte[] pemBytes);

}
