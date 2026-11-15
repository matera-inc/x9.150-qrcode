/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.sendpaymentnotification;

import com.matera.x9qrcode.app.dto.BlockchainDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPaymentDTO;
import com.matera.x9qrcode.app.dto.enumerated.ActionEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.AnswerSignatureEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureProblemEnumDTO;
import com.matera.x9qrcode.app.exception.NotificationUndeliverableException;
import com.matera.x9qrcode.app.service.QRCodeOutboundNotificationService;
import com.matera.x9qrcode.app.service.QRCodeOutboundNotificationService.OutboundNotificationResult;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * What a bad correlationId in the payee's answer means on each side of the money moving (owner,
 * 2026-11-15; ADR-0027). The delivery is stubbed; each answer is the result the adapter would give.
 */
class SendPaymentNotificationUseCaseTest {

    private static final URI ENDPOINT = URI.create("https://payee.example.com/pub/api/v1/payment-notification");
    private static final String QR_ID = "01A0E3A12805CB382EF4687F18CDC43A";
    private static final String TX = "3n8kLq2Vb7yTfXpR9sWzM4cJhG6dEuA1";

    private final QRCodeOutboundNotificationService delivery = Mockito.mock(QRCodeOutboundNotificationService.class);
    private final SendPaymentNotificationUseCase useCase = new SendPaymentNotificationUseCase(delivery);

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SendPaymentNotificationUseCase.class);

    @BeforeEach
    void captureLog() {
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(log);
    }

    private static SendPaymentNotificationInput input(boolean postCommit, UUID correlationId) {
        PaymentNotificationPaymentDTO payment =
            new PaymentNotificationPaymentDTO(1000L, null, "USDC", "solana", postCommit ? TX : null);
        BlockchainDTO blockchain = new BlockchainDTO(postCommit ? ActionEnumDTO.SENT : ActionEnumDTO.PAYMENT_INITIATED,
            "from", "to");
        return new SendPaymentNotificationInput(postCommit ? PaymentPhase.POST_PAYMENT : PaymentPhase.PRE_PAYMENT,
            new PaymentNotificationDataDTO(QR_ID, payment, null, null, blockchain), ENDPOINT, correlationId);
    }

    private static OutboundNotificationResult verified(UUID sent) {
        return new OutboundNotificationResult(200, "a.b.c", AnswerSignatureEnumDTO.VERIFIED, null, null, sent.toString());
    }

    private static OutboundNotificationResult underAnother(UUID sent) {
        return new OutboundNotificationResult(200, "a.b.c", AnswerSignatureEnumDTO.INVALID,
            SignatureProblemEnumDTO.CORRELATION_ID_MISMATCH, "correlationId mismatch", "another-" + sent);
    }

    private static OutboundNotificationResult withoutId(UUID sent) {
        return new OutboundNotificationResult(200, "a.b.c", AnswerSignatureEnumDTO.INVALID,
            SignatureProblemEnumDTO.BAD_HEADER, "Missing required header parameter: correlationId", null);
    }

    /** The payee answers the n-th delivery with {@code answers[n]}, each a function of the id sent. */
    @SafeVarargs
    private void payeeAnswers(Function<UUID, OutboundNotificationResult>... answers) {
        int[] n = {0};
        Mockito.when(delivery.send(any(), eq(ENDPOINT), any())).thenAnswer(call -> {
            UUID sent = call.getArgument(2);
            return answers[Math.min(n[0]++, answers.length - 1)].apply(sent);
        });
    }

    private List<UUID> sentUnder(int times) {
        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        Mockito.verify(delivery, Mockito.times(times)).send(any(), eq(ENDPOINT), ids.capture());
        return ids.getAllValues();
    }

    private List<ILoggingEvent> records() {
        return log.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith(SendPaymentNotificationUseCase.UNTIED_ANSWERS))
            .toList();
    }

    /**
     * <b>X9-SIG-154</b> — before the money moves, an answer under another correlationId or none is
     * refused after one delivery: notification-undeliverable with the detail "failed to pay - payee
     * is not responding (bad correlationId)". Nothing is recorded.
     *
     * <p><b>Source:</b> Ours. Owner, 2026-11-15 (ADR-0027).
     *
     * <p><b>Why:</b> An answer that cannot be tied to the question is no answer; a payer must not pay on it.
     */
    @Test
    void beforeTheMoneyMovesABadCorrelationIdIsRefused() {
        for (Function<UUID, OutboundNotificationResult> bad : List.<Function<UUID, OutboundNotificationResult>>of(
                SendPaymentNotificationUseCaseTest::underAnother, SendPaymentNotificationUseCaseTest::withoutId)) {
            Mockito.reset(delivery);
            payeeAnswers(bad);

            NotificationUndeliverableException refused =
                assertThrows(NotificationUndeliverableException.class, () -> useCase.execute(input(false, null)));

            assertEquals("failed to pay - payee is not responding (bad correlationId)", refused.detail());
            assertEquals(1, sentUnder(1).size());
        }
        assertTrue(records().isEmpty(), "a pre-commit refusal is not a post-commit record");
    }

    /**
     * <b>X9-SIG-155</b> — after the money moved, an answer under another correlationId is followed by
     * one more delivery under a new correlationId, and a good answer to it is reported as any other.
     *
     * <p><b>Source:</b> Ours. Owner, 2026-11-15 (ADR-0027): "money was sent already, so we can try one
     * more time".
     *
     * <p><b>Why:</b> The retry is a new exchange; under the same id, an answer replayed from the first
     * would pass for an answer to the second.
     */
    @Test
    void afterTheMoneyMovedABadCorrelationIdIsSentOnceMore() {
        UUID callers = UUID.randomUUID();
        payeeAnswers(SendPaymentNotificationUseCaseTest::underAnother, SendPaymentNotificationUseCaseTest::verified);

        SendPaymentNotificationOutput output = useCase.execute(input(true, callers));

        assertEquals(AnswerSignatureEnumDTO.VERIFIED, output.signature());
        assertTrue(output.accepted());
        List<UUID> ids = sentUnder(2);
        assertEquals(callers, ids.get(0), "the first under the caller's id");
        assertNotNull(ids.get(1));
        assertNotEquals(ids.get(0), ids.get(1), "the retry under a new id");
        assertTrue(records().isEmpty(), "a good retry records nothing");
    }

    /**
     * <b>X9-SIG-156</b> — after the money moved, a second bad answer ends the exchange: two deliveries,
     * the retry's answer reported as it is, and one WARN record carrying the QR Code, the
     * transactionId, both correlationIds sent, what came back under each, and when. A retry that gets
     * no answer is recorded as "no answer", and the caller gets the usual 502.
     *
     * <p><b>Source:</b> Ours. Owner, 2026-11-15 (ADR-0027): "we just log the tentative on the log for
     * the QR Code, letting the payer bank know about it".
     *
     * <p><b>Why:</b> No operational work starts, but the payer bank must be able to investigate.
     */
    @Test
    void afterTheMoneyMovedABadCorrelationIdTwiceIsRecorded() {
        payeeAnswers(SendPaymentNotificationUseCaseTest::underAnother, SendPaymentNotificationUseCaseTest::withoutId);

        SendPaymentNotificationOutput output = useCase.execute(input(true, null));

        assertEquals(AnswerSignatureEnumDTO.INVALID, output.signature());
        assertEquals(SignatureProblemEnumDTO.BAD_HEADER, output.signatureProblem(), "the retry's answer");
        List<UUID> ids = sentUnder(2);
        List<ILoggingEvent> recorded = records();
        assertEquals(1, recorded.size());
        ILoggingEvent record = recorded.get(0);
        String line = record.getFormattedMessage();
        assertEquals(Level.WARN, record.getLevel());
        assertTrue(line.contains("qrcodeId=" + QR_ID), line);
        assertTrue(line.contains("transactionId=" + TX), line);
        assertTrue(line.contains("correlationIdsSent=[" + ids.get(0) + ", " + ids.get(1) + "]"), line);
        assertTrue(line.contains("correlationIdsAnswered=[another-" + ids.get(0) + ", missing]"), line);
        assertTrue(line.contains(" at="), line);
        assertFalse(line.contains(ENDPOINT.getHost()), "never the endpoint: " + line);

        Mockito.reset(delivery);
        log.list.clear();
        NotificationUndeliverableException noAnswer = new NotificationUndeliverableException("no answer", (Throwable) null);
        int[] n = {0};
        Mockito.when(delivery.send(any(), eq(ENDPOINT), any())).thenAnswer(call -> {
            if (n[0]++ == 0) {
                return underAnother(call.getArgument(2));
            }
            throw noAnswer;
        });

        assertSame(noAnswer, assertThrows(NotificationUndeliverableException.class,
            () -> useCase.execute(input(true, null))));
        assertEquals(1, records().size());
        assertTrue(records().get(0).getFormattedMessage().contains(", no answer]"),
            records().get(0).getFormattedMessage());
    }

    /**
     * <b>X9-SIG-157</b> — an answer that fails another check is reported as before, before or after the
     * money moves: one delivery, no refusal, no record.
     *
     * <p><b>Source:</b> Ours. Owner, 2026-11-15 (ADR-0027): only the correlationId changes; the rest of
     * Story 2.3 stands.
     *
     * <p><b>Why:</b> An unsigned yes is still the payer's to judge.
     */
    @Test
    void anotherFailureIsReportedAsBefore() {
        for (boolean postCommit : new boolean[] {false, true}) {
            Mockito.reset(delivery);
            payeeAnswers(sent -> new OutboundNotificationResult(200, "a.b.c", AnswerSignatureEnumDTO.INVALID,
                SignatureProblemEnumDTO.BAD_SIGNATURE, "bad", "another"));

            SendPaymentNotificationOutput output = useCase.execute(input(postCommit, null));

            assertEquals(SignatureProblemEnumDTO.BAD_SIGNATURE, output.signatureProblem());
            sentUnder(1);
        }
        assertTrue(records().isEmpty());
    }
}
