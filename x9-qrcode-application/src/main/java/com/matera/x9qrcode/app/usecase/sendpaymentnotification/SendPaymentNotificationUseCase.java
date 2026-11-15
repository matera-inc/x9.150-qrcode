/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.sendpaymentnotification;

import com.matera.x9qrcode.app.exception.NotificationUndeliverableException;
import com.matera.x9qrcode.app.service.QRCodeOutboundNotificationService;
import com.matera.x9qrcode.app.service.QRCodeOutboundNotificationService.OutboundNotificationResult;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.app.usecase.validatesignature.CorrelationEcho;
import com.matera.x9qrcode.app.dto.BlockchainDTO;
import com.matera.x9qrcode.app.dto.enumerated.ActionEnumDTO;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Signs a payer's notification and delivers it to the payee.
 *
 * <p>The payer's side of the two-phase flow. Their software decides what to pay and when; this turns
 * that decision into a conformant, signed X9.150 message and puts it in front of the payee — so a PSP
 * integrating here never builds a JWS, manages a keystore, or learns the {@code crit} rules.
 *
 * <p>The payee's answer is returned rather than interpreted. A pre-payment is a request for
 * permission and its verdict is the thing the payer must act on. One exception (owner, 2026-11-15;
 * ADR-0027): a signed answer that does not carry the correlationId sent is not an answer to our
 * question, and is refused before the money moves, or sent once more and then recorded after it.
 */
@Slf4j
@RequiredArgsConstructor
public class SendPaymentNotificationUseCase
    extends UseCase<SendPaymentNotificationInput, SendPaymentNotificationOutput> {

    private final QRCodeOutboundNotificationService outboundNotificationService;

    @Override
    public SendPaymentNotificationOutput execute(SendPaymentNotificationInput input) {
        validate(input);

        UUID sent = nonNull(input.correlationId()) ? input.correlationId() : UUID.randomUUID();

        OutboundNotificationResult result = outboundNotificationService.send(
            input.notification(), input.endpoint(), sent);

        if (notEchoed(result, sent)) {
            result = untied(input, sent, result);
        }

        return new SendPaymentNotificationOutput(result.statusCode(), result.accepted(), result.body(),
            result.signature(), result.signatureProblem(), result.signatureDetail());
    }

    /**
     * The payee's answer is not tied to our question: no correlationId, or another one (owner,
     * 2026-11-15; ADR-0027).
     *
     * <p>The phase is the wire's own (ADR-0004). Without a transactionId the payee reads a pre-commit
     * notification, the money has not moved, and an answer that cannot be tied to the question is no
     * answer: 502. With one, the money has moved: the notification is sent once more, as a new
     * exchange under a new correlationId, and a good answer to it is reported as any other. A second
     * bad answer, or none, is recorded for the payer bank to investigate, and nothing else is started.
     */
    private OutboundNotificationResult untied(SendPaymentNotificationInput input, UUID sent,
                                              OutboundNotificationResult first) {
        String transactionId = input.notification().payment().transactionId();
        String qrcodeId = input.notification().qrCodeId();

        if (isBlank(transactionId)) {
            log.warn("Pre-commit answer refused: bad correlationId. qrcodeId={} correlationIdSent={} "
                    + "correlationIdAnswered={} problem={}",
                qrcodeId, sent, answered(first), first.signatureProblem());
            throw new NotificationUndeliverableException(
                "The payee's answer to a pre-commit notification does not carry the correlationId sent",
                NotificationUndeliverableException.DETAIL_BAD_CORRELATION_ID);
        }

        UUID retryId = UUID.randomUUID();
        OutboundNotificationResult retry;
        try {
            retry = outboundNotificationService.send(input.notification(), input.endpoint(), retryId);
        } catch (NotificationUndeliverableException noAnswer) {
            record(qrcodeId, transactionId, sent, answered(first), first, retryId, NO_ANSWER, null);
            throw noAnswer;
        }

        if (notEchoed(retry, retryId)) {
            record(qrcodeId, transactionId, sent, answered(first), first, retryId, answered(retry), retry);
        }
        return retry;
    }

    /**
     * The record of a post-commit notification whose answer, sent twice, never carried the
     * correlationId sent. The payer side holds no state for a QR Code another bank issued, so this
     * WARN line, under {@link #UNTIED_ANSWERS}, is the QR Code's log: the QR Code, the transactionId,
     * each correlationId sent, what came back under it, and when. Never the endpoint, never a key.
     * Go writes the same fields.
     */
    private static void record(String qrcodeId, String transactionId,
                               UUID firstSent, String firstAnswered, OutboundNotificationResult first,
                               UUID retrySent, String retryAnswered, OutboundNotificationResult retry) {
        log.warn("{} qrcodeId={} transactionId={} correlationIdsSent=[{}, {}] correlationIdsAnswered=[{}, {}] "
                + "problems=[{}, {}] at={}",
            UNTIED_ANSWERS, qrcodeId, transactionId, firstSent, retrySent,
            printable(firstAnswered), printable(retryAnswered),
            first.signatureProblem(), isNull(retry) ? "" : retry.signatureProblem(),
            DateTimeUtils.nowUTC().toInstant());
    }

    /** The message every post-commit record is written under, for finding them in the log. */
    public static final String UNTIED_ANSWERS =
        "x9-qrcode post-commit notification: the payee's answer did not carry our correlationId, twice";

    private static final String NO_ANSWER = "no answer";

    private static boolean notEchoed(OutboundNotificationResult result, UUID sent) {
        return CorrelationEcho.notEchoed(result.signatureProblem(), result.answeredCorrelationId(), sent);
    }

    private static String answered(OutboundNotificationResult result) {
        return isNull(result.answeredCorrelationId()) ? "missing" : result.answeredCorrelationId();
    }

    /** What the other party wrote, made safe for one log line: no control characters. */
    private static String printable(String written) {
        return written.replaceAll("\\p{Cntrl}", "?");
    }

    /**
     * The phase and the transaction reference have to agree, and the check is worth more than it
     * looks.
     *
     * <p>A payee infers the phase from whether a reference is present — the standard gives it nothing
     * else to go on (ADR-0004). So a pre-payment that carries one is not merely mislabelled: it
     * arrives as a <em>post</em>-payment, the QR Code is never reserved, and the payer proceeds to
     * pay against a QR Code somebody else can still claim. Catching it here costs one comparison;
     * catching it afterwards means reconciling a double payment.
     */
    private void validate(SendPaymentNotificationInput input) {
        if (isNull(input.notification()) || isNull(input.notification().payment())) {
            throw new BusinessRuleException("payment", "A notification must carry its payment.");
        }

        if (isNull(input.endpoint())) {
            throw new BusinessRuleException("endpoint",
                "The payee's notification endpoint is required; it is published in the payload the payer fetched.");
        }

        String transactionId = input.notification().payment().transactionId();

        if (PaymentPhase.PRE_PAYMENT.equals(input.phase()) && !isBlank(transactionId)) {
            throw new BusinessRuleException("payment.transactionId",
                "A pre-payment notification must not carry a transactionId: the payee infers the phase "
                    + "from its absence, so one here would be read as a payment already made.");
        }

        // Keyed on the ACTION, not the phase. NOT_SENT is a post-payment notification whose entire
        // meaning is that no transaction exists and none will — so requiring a transactionId of it
        // refused the one message a payer can send to give a reservation back, for lacking the one
        // thing it cannot have. The domain accepted it without one; this half did not, and the two
        // halves of the same deployment disagreed.
        if (PaymentPhase.POST_PAYMENT.equals(input.phase()) && isBlank(transactionId) && reportsAPayment(input)) {
            throw new BusinessRuleException("payment.transactionId",
                "A post-payment notification must carry the transactionId — the on-chain hash, or the "
                    + "End-to-End ID for an ISO 20022 rail. Reporting a payment without its reference "
                    + "gives the payee nothing to reconcile against.");
        }

        if (nonNull(input.endpoint().getScheme()) && !input.endpoint().isAbsolute()) {
            throw new BusinessRuleException("endpoint", "The notification endpoint must be an absolute URL.");
        }
    }


    /**
     * Whether this notification reports a payment that happened, as opposed to one that did not.
     *
     * <p>Only {@code NOT_SENT} says a payment did not happen. Everything else — including a
     * notification with no blockchain section at all, on a rail that has no actions — is reporting
     * money that moved and must carry its reference.
     */
    private static boolean reportsAPayment(SendPaymentNotificationInput input) {
        BlockchainDTO blockchain = input.notification().blockchain();

        return isNull(blockchain) || !ActionEnumDTO.NOT_SENT.equals(blockchain.action());
    }

}
