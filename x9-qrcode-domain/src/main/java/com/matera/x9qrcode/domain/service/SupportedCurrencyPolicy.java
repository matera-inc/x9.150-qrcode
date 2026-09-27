/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import java.util.Collection;

/**
 * Decides which currencies this deployment will accept on a payment request.
 *
 * <p>The payload format is currency-agnostic: ANSI X9.150 carries any ISO 4217 code or digital-asset
 * ticker verbatim and leaves interpretation to the paying PSP. What a given deployment can actually
 * <em>settle</em>, though, is decided by the rails it supports, and the rails supported here are the
 * US bank rails — FedNow, RTP and ACH — which move USD.
 *
 * <p>So a QR Code denominated in EUR or USDC would advertise an amount no supported rail can pay.
 * Better refused at creation, where the biller can still fix it, than accepted and left unpayable:
 * a QR Code that cannot be honoured is worse than one that was never made.
 *
 * <p>Configured rather than fixed, because this list grows with the rails — adding a rail that
 * settles another currency is exactly what makes that currency payable.
 *
 * <p>Distinct from {@link CurrencyMixPolicy}, which asks whether the currencies on one request may
 * appear <em>together</em>. This one asks whether each is acceptable at all.
 */
public interface SupportedCurrencyPolicy {

    /**
     * Validates every currency used on the request. Null/blank entries are ignored and comparison is
     * case-insensitive.
     *
     * @param currencies every currency used on the request
     * @throws com.matera.x9qrcode.domain.exception.BusinessRuleException when any currency is not
     *                                                                    supported by this deployment
     */
    void validate(Collection<String> currencies);

}
