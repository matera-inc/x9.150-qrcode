/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.config.filter;

import com.matera.x9qrcode.infrastructure.web.config.dto.RequestLoggerFilterDTO;

/**
 * The {@code AfterRequestLoggerFilterHook} interface provides a hook mechanism
 * for actions to be executed after the {@link RegexRequestLoggerFilter#doFilterInternal} method.
 * Implementations of this interface will be called after the main filtering logic has been executed,
 * allowing additional processing, generating metrics, or any other post-processing tasks.
 *
 * @see RegexRequestLoggerFilter#doFilterInternal
 */
public interface AfterRequestLoggerFilterHook {

    void execute(RequestLoggerFilterDTO requestLoggerFilterDTO, long duration);
}
