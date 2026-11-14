/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.sendpaymentnotification;

import com.matera.x9qrcode.app.dto.enumerated.AnswerSignatureEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureProblemEnumDTO;

/**
 * The payee's answer, as reported to the payer: its status, its body verbatim, and whether the
 * answer was signed as ANSI X9.150 A.4.5 asks ({@code signature}, with {@code signatureProblem} as a
 * fixed code and {@code signatureDetail} in words saying why when it was not).
 */
public record SendPaymentNotificationOutput(int statusCode,
                                            boolean accepted,
                                            String body,
                                            AnswerSignatureEnumDTO signature,
                                            SignatureProblemEnumDTO signatureProblem,
                                            String signatureDetail) {
}
