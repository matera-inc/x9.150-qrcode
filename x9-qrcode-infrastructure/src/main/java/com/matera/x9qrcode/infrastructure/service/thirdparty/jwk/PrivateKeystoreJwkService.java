/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.jwk;

import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.app.service.JwkService;

import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class PrivateKeystoreJwkService implements JwkService<JWK, JWSSigner> {

    @Autowired
    private JwkSetFacadeBean jwkSetFacadeBean;

    public Map<String, Object> retrieveJwkSet() throws ServiceException {
        try {
            JWKSet jwkSet = new JWKSet(retrieveJwk().toPublicJWK());

            log.debug("Retrieving JWK Set information: {}", jwkSet);

            return jwkSet.toJSONObject();
        } catch (Exception e) {
            throw new ServiceException("Error parsing JWK Set information", e);
        }
    }

    @Override
    public JWK retrieveJwk() throws ServiceException {
        try {
            return jwkSetFacadeBean.getJwkInformation();
        } catch (Exception e) {
            throw new ServiceException("Error retrieving JWK information", e);
        }
    }

    @Override
    public JWSSigner retrieveSigner() throws ServiceException {
        return jwkSetFacadeBean.getSigner();
    }

}
