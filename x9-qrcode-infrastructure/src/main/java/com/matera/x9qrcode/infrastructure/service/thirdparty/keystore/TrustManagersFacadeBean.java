/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.keystore;

import com.matera.x9qrcode.app.service.TruststoreRetriever;

import lombok.Getter;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.X509TrustManager;

public class TrustManagersFacadeBean {
    
    @Getter
    private X509TrustManager trustManager;

    @Getter
    private TruststoreRetriever truststoreRetriever;
    
    public TrustManagersFacadeBean(X509TrustManager defaultTm, X509TrustManager localTm){
        trustManager = createAndUpdateCustomTrustManagersArray(defaultTm, localTm);
    }
    
    public X509TrustManager createAndUpdateCustomTrustManagersArray(X509TrustManager defaultTm, X509TrustManager localTm) {
        if (localTm != null){
            trustManager = createCustomTrustManagerImpl(defaultTm, localTm);
        } else {
            trustManager = defaultTm;
        }
        return trustManager;
    }
    
    private X509TrustManager createCustomTrustManagerImpl(final X509TrustManager defaultTm, final X509TrustManager localTm) {
        return new X509TrustManager() {
            
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                Set<X509Certificate> acceptedIssuers = new HashSet<>();
                acceptedIssuers.addAll(Arrays.asList(defaultTm.getAcceptedIssuers()));
                acceptedIssuers.addAll(Arrays.asList(localTm.getAcceptedIssuers()));
                return acceptedIssuers.toArray(new X509Certificate[acceptedIssuers.size()]);
            }
            
            @Override
            public void checkServerTrusted(X509Certificate[] chain,
                                           String authType) throws CertificateException {
                try {
                    defaultTm.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    localTm.checkServerTrusted(chain, authType);
                }
            }
            
            @Override
            public void checkClientTrusted(X509Certificate[] chain,
                                           String authType) throws CertificateException {
                try {
                    defaultTm.checkClientTrusted(chain, authType);
                } catch (CertificateException e) {
                    localTm.checkClientTrusted(chain, authType);
                }

            }
        };
    }
    
}
