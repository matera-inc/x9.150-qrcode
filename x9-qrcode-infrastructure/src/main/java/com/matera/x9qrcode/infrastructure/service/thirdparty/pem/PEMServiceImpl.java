/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.pem;

import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.app.service.PEMService;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;

import lombok.RequiredArgsConstructor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.matera.x9qrcode.app.service.SignatureConstants.PEM_BEGIN_CERTIFICATE;
import static com.matera.x9qrcode.app.service.SignatureConstants.PEM_END_CERTIFICATE;
import static com.matera.x9qrcode.app.service.SignatureConstants.PEM_LINE_BREAK;
import static com.matera.x9qrcode.app.service.SignatureConstants.PEM_LINE_LENGTH;

@RequiredArgsConstructor
public class PEMServiceImpl implements PEMService {

    private final X9Properties x9Properties;

    @Override
    public byte[] generate(X509Certificate[] certificates) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            for (X509Certificate certificate : certificates) {
                writeCertificateAsPem(certificate, outputStream);
            }

            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new ServiceException("Error obtaining local certificate", e);
        }
    }

    @Override
    public List<X509Certificate> parse(byte[] pemBytes) {
        try {
            CertificateFactory certificateFactory =
                CertificateFactory.getInstance(x9Properties.getCertificate().getCertificateSupportedType());

            Collection<? extends Certificate> certificates =
                certificateFactory.generateCertificates(new ByteArrayInputStream(pemBytes));

            List<X509Certificate> x509Certificates = new ArrayList<>();
            for (Certificate cert : certificates) {
                if (cert instanceof X509Certificate) {
                    x509Certificates.add((X509Certificate) cert);
                }
            }

            return x509Certificates;
        } catch (Exception e) {
            throw new ServiceException("Error parsing external certificate", e);
        }
    }

    private void writeCertificateAsPem(X509Certificate certificate,
                                       ByteArrayOutputStream outputStream) throws Exception {
        String pemContent = PEM_BEGIN_CERTIFICATE  + PEM_LINE_BREAK +
                            getBase64Encoded(certificate) + PEM_LINE_BREAK +
                            PEM_END_CERTIFICATE  + PEM_LINE_BREAK;

        outputStream.write(pemContent.getBytes(StandardCharsets.UTF_8));
    }

    private static String getBase64Encoded(X509Certificate certificate) throws CertificateEncodingException {
        return java.util.Base64.getMimeEncoder(PEM_LINE_LENGTH, PEM_LINE_BREAK.getBytes(StandardCharsets.UTF_8))
                               .encodeToString(certificate.getEncoded());
    }

}
