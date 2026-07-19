/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.generatesignature;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import static org.apache.commons.lang3.StringUtils.isBlank;

import static java.util.Objects.isNull;

@Slf4j
public record SignatureGenerationInput(
    UUID correlationId,
    Integer ttlTimeSeconds,
    Object content) {

    public SignatureGenerationInput {
        log.debug("Generation input: correlationId={}, ttlTimeSeconds={}, content={}",
            correlationId, ttlTimeSeconds, content);

        validateCommonFields(correlationId, ttlTimeSeconds, content);
    }

    private void validateCommonFields(UUID correlationId, Integer ttlTimeSeconds, Object content) {
        if (isNull(correlationId)) {
            throw new BusinessRuleException("correlationId", "Should inform the JWS Token for generation !!");
        }

        if (isNull(ttlTimeSeconds) || ttlTimeSeconds <= 0) {
            throw new BusinessRuleException("ttlTimeSeconds", "Should inform a valid TTL time in seconds for generation !!");
        }

        if (isNull(content) || isBlank(content.toString())) {
            throw new BusinessRuleException("content", "Should inform the field content for generation !!");
        }
    }
}
