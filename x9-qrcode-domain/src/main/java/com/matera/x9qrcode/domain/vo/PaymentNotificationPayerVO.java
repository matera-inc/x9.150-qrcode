/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import static java.util.Objects.isNull;

/**
 * Information about the payer, as the payer's own PSP chose to describe them.
 *
 * <p>{@code info} is the only payer field ANSI X9.150-2026 defines — there is no payer identifier in
 * the standard. Of its content §3.1 says only <i>"Any information related to the Payer."</i>
 *
 * <p><b>The 254 limit is the standard's:</b> Table 4 (Payment Notification Requirements), row
 * {@code 3.1 Payer Info}, gives the length as 254, and §3.1 repeats it — <i>"If present, SHALL be
 * string with 254 maximum characters"</i>.
 *
 * <p><b>Enforced here rather than in the schema, and that is the point.</b> The contract declared
 * {@code maxLength} on this field and it was never applied: a payment notification arrives as a
 * signed JWS, so the payload is parsed out of the token rather than bound by Spring, and Bean
 * Validation never sees it. A 255-character value was accepted. Constraints on the notification
 * payload only hold if the domain holds them — the schema describes that path, it does not police
 * it.
 *
 * <p>Nothing here treats {@code info} as an identity. It is supplied by the sender and verified by
 * nobody, so it can narrow an identity the signing certificate already established and never widen
 * one. See {@code official-spec/BEST-PRACTICES.md}.
 */
public record PaymentNotificationPayerVO(
    String info
) {

    /** ANSI X9.150-2026 Table 4, row 3.1 Payer Info, and §3.1. */
    public static final int MAX_INFO_LENGTH = 254;

    public PaymentNotificationPayerVO {
        if (!isNull(info) && info.length() > MAX_INFO_LENGTH) {
            throw new ValueObjectRuleException(
                "payer.info must be at most %d characters (ANSI X9.150-2026 Table 4, row 3.1), but %d were given."
                    .formatted(MAX_INFO_LENGTH, info.length()));
        }
    }

}
