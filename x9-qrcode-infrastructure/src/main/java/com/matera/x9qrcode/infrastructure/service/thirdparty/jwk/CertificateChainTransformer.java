/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.jwk;

import com.nimbusds.jose.util.Base64;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CertificateChainTransformer implements Function<Certificate[], ArrayList<Base64>> {
    
    @Override
    @SneakyThrows
    public ArrayList<Base64> apply(Certificate[] chain) {
        var certEncodedList =
            Arrays.stream(chain).map(a -> getEncoded(a)).collect(Collectors.toCollection(ArrayList::new));

        return certEncodedList;
    }

    @SneakyThrows
    private static Base64 getEncoded(Certificate a) {
        return Base64.encode(a.getEncoded());
    }

}