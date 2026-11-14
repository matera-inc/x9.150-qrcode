/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPayerDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPaymentDTO;
import com.matera.x9qrcode.app.usecase.paymentnotification.PaymentNotificationQRCodeInput;
import com.matera.x9qrcode.app.usecase.paymentnotification.PaymentNotificationQRCodeUseCase;
import com.matera.x9qrcode.domain.exception.QRCodeStatusConflictException;

import io.restassured.module.mockmvc.response.MockMvcResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 2.4: pre-commit works on every rail, so two payers cannot pay the same QR Code (ADR-0036).
 *
 * <p>Each test names the acceptance criterion it proves and the matrix cell it covers. The entity
 * pins every cell too ({@code QRCodeEntityNotificationMatrixTest}); these prove them through the
 * real endpoint, signature, store and event log.
 */
class PreCommitOnEveryRailApiTest extends NotificationMatrixApiSupport {

    private static final String E2E = "20261114021000021P1BRJQ0000000001";
    private static final String TRACE = "021000021.0000001";
    private static final String HASH_1 = "5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f";
    private static final String HASH_2 = "3kXz8YtWdq1uQvBmn9Lr2HfP6sJcEgTaZo4NyRbVi7Dw5MKeUhGpQjAxCs8tLoWn";
    private static final String WRONG_STATUS = "RECEIVED_IN_WRONG_STATUS";
    private static final String CONTRADICTS = "ACTION_CONTRADICTS_HASH";

    @Autowired
    private PaymentNotificationQRCodeUseCase paymentNotificationQRCodeUseCase;

    // ------------------------------------------------------------------- S2.4-AC1

    /**
     * <b>X9-HOLD-201</b> — a FedNow or RTP pre-commit is gated at the payee's clock and reserves the QR Code (S2.4-AC1).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC1). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> FedNow and RTP required an End-to-End ID on every notification, so they had no
     * pre-commit: two payers could pay one QR Code at once.
     */
    @Test
    void aFedNowOrRtpPreCommitIsGatedAndReserves() {
        for (String network : List.of("FedNow", "RTP")) {
            String qrCodeId = createQRCode();

            MockMvcResponse wrongAmount = bank(qrCodeId, network, null, PAYER_A, AMOUNT - 1);
            assertEquals(HttpStatus.BAD_REQUEST.value(), wrongAmount.statusCode(),
                "a pre-commit goes through the acceptance gate: " + wrongAmount.asString());
            assertEquals("ACTIVE", statusOf(qrCodeId));

            MockMvcResponse accepted = bank(qrCodeId, network, null, PAYER_A);
            assertEquals(HttpStatus.OK.value(), accepted.statusCode(), accepted.asString());
            assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId), network + " now has a pre-commit");

            Map<String, Object> initiated = lastEventFor(qrCodeId);
            assertEquals("payment.initiated", initiated.get("type"));
            assertEquals(PAYER_A, initiated.get("payerInfo"));
            assertEquals("ACTIVE", initiated.get("receivedInStatus"));
            assertFalse(initiated.containsKey("deviations"), "absent when the payer followed the rules");
        }
    }

    // ------------------------------------------------------------------- S2.4-AC2

    /**
     * <b>X9-HOLD-202</b> — a second payer's pre-commit is a 409 on every rail (S2.4-AC2).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC2). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> The reservation only protects the payee if every rail honours it.
     */
    @Test
    void aSecondPayersPreCommitIsAConflictOnEveryRail() {
        for (String network : List.of("FedNow", "RTP", "ACH")) {
            String qrCodeId = createQRCode();
            assertEquals(HttpStatus.OK.value(), bank(qrCodeId, network, null, PAYER_A).statusCode());

            MockMvcResponse second = bank(qrCodeId, network, null, PAYER_B);

            assertEquals(HttpStatus.CONFLICT.value(), second.statusCode(), network + ": " + second.asString());
            assertEquals(List.of("payment.initiated"), eventTypesFor(qrCodeId));
        }

        String qrCodeId = createQRCode();
        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A).statusCode());
        assertEquals(HttpStatus.CONFLICT.value(),
            solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_B).statusCode());
    }

    /**
     * <b>X9-HOLD-203</b> — two payers racing for one QR Code end with exactly one winner and one 409 (S2.4-AC2).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC2). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> Both read ACTIVE at the same instant; without a re-read after the lost save,
     * the loser got a 500, or both were told yes.
     */
    @Test
    void twoPayersRacingForOneQRCodeEndWithExactlyOneWinner() throws Exception {
        int rounds = 10;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        try {
            for (int round = 0; round < rounds; round++) {
                String qrCodeId = createQRCode();
                CountDownLatch start = new CountDownLatch(1);

                List<Future<String>> outcomes = new ArrayList<>();
                for (String payer : List.of(PAYER_A, PAYER_B)) {
                    Callable<String> attempt = () -> {
                        start.await();
                        try {
                            paymentNotificationQRCodeUseCase.execute(preCommit(qrCodeId, payer));
                            return "won";
                        } catch (QRCodeStatusConflictException conflict) {
                            return "409";
                        }
                    };
                    outcomes.add(pool.submit(attempt));
                }

                start.countDown();

                List<String> results = new ArrayList<>();
                for (Future<String> outcome : outcomes) {
                    results.add(outcome.get());
                }

                assertEquals(1, results.stream().filter("won"::equals).count(), "round " + round + ": " + results);
                assertEquals(1, results.stream().filter("409"::equals).count(), "round " + round + ": " + results);
                assertEquals(List.of("payment.initiated"), eventTypesFor(qrCodeId), "one winner, one event");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static PaymentNotificationQRCodeInput preCommit(String qrCodeId, String payerInfo) {
        return new PaymentNotificationQRCodeInput(
            new PaymentNotificationDataDTO(qrCodeId,
                new PaymentNotificationPaymentDTO(AMOUNT, null, "USD", "FedNow", null),
                new PaymentNotificationPayerDTO(payerInfo),
                OffsetDateTime.parse("2030-10-08T06:59:59Z"),
                null),
            "CN=race-test-psp.example.com");
    }

    /**
     * <b>X9-HOLD-204</b> — another payer's Solana post-commit is registered, flagged, and the holder keeps the QR Code (S2.4-AC2).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC2). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> Java answered 409 and dropped the hash of money that had already moved, hiding
     * a duplicate payment from the payment system.
     */
    @Test
    void aSecondPayersPostCommitIsRegisteredAndTheHolderKeepsTheQRCode() {
        String qrCodeId = createQRCode();
        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A).statusCode());

        MockMvcResponse fromB = solana(qrCodeId, "SENT", HASH_2, PAYER_B);

        assertEquals(HttpStatus.OK.value(), fromB.statusCode(), fromB.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
        assertEquals(HttpStatus.CONFLICT.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_B).statusCode(),
            "A still holds it");
        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A).statusCode(),
            "and A may still retry");

        Map<String, Object> sent = eventsFor(qrCodeId).stream()
            .filter(event -> "payment.sent".equals(event.get("type"))).findFirst().orElseThrow();
        assertEquals(HASH_2, sent.get("transactionId"));
        assertEquals(PAYER_B, sent.get("payerInfo"));
        assertEquals(List.of(WRONG_STATUS), sent.get("deviations"));
        assertEquals("PAYMENT_INITIATED", sent.get("receivedInStatus"));
        assertEquals("1.1", sent.get("schemaVersion"));
    }

    /**
     * <b>X9-HOLD-205</b> — another payer's bank-rail post-commit changes nothing and publishes nothing (S2.4-AC2).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC2). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> Bank rails carry QRCD in the payment; the post-commit must not take the
     * reservation from its holder.
     */
    @Test
    void aSecondPayersBankRailPostCommitChangesNothing() {
        String qrCodeId = createQRCode();
        assertEquals(HttpStatus.OK.value(), bank(qrCodeId, "FedNow", null, PAYER_A).statusCode());

        assertEquals(HttpStatus.OK.value(), bank(qrCodeId, "FedNow", E2E, PAYER_B).statusCode());

        assertEquals(List.of("payment.initiated"), eventTypesFor(qrCodeId), "bank rails never emit payment.sent");
        assertEquals(HttpStatus.CONFLICT.value(), bank(qrCodeId, "FedNow", null, PAYER_B).statusCode());
    }

    // ------------------------------------------------------------------- S2.4-AC3

    /**
     * <b>X9-HOLD-206</b> — the holder's bank-rail post-commit is recorded, never judged, with no payment.sent (S2.4-AC3).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC3). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> ACH used to judge a notification carrying a Trace Number as a pre-commit, so a
     * report of money already sent could be refused on its amount.
     */
    @Test
    void theHoldersBankRailPostCommitIsRecordedAndNeverJudged() {
        for (String[] rail : List.of(new String[] {"FedNow", E2E}, new String[] {"RTP", E2E},
                                     new String[] {"ACH", TRACE})) {
            String qrCodeId = createQRCode();
            assertEquals(HttpStatus.OK.value(), bank(qrCodeId, rail[0], null, PAYER_A).statusCode());

            MockMvcResponse report = bank(qrCodeId, rail[0], rail[1], PAYER_A, AMOUNT + 7);

            assertEquals(HttpStatus.OK.value(), report.statusCode(),
                rail[0] + ": a post-commit is never judged on amount: " + report.asString());
            assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
            assertEquals(List.of("payment.initiated"), eventTypesFor(qrCodeId),
                "no payment.sent on a bank rail");
            assertEquals(rail[1], given().when().get(CREATE + "/" + qrCodeId).then().extract()
                .path("paymentNotification.data.payment.transactionId"), "the reference is recorded");
        }
    }

    // ------------------------------------------------------------------- S2.4-AC4

    /**
     * <b>X9-HOLD-207</b> — a Solana post-commit on an ACTIVE QR Code reserves it, registers the hash, flags both events (S2.4-AC4).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC4). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> A payer that skipped the pre-commit has still paid; refusing it would leave
     * the QR Code open to a second payer and the payment system without the hash.
     */
    @Test
    void aSolanaPostCommitOnAnActiveQRCodeReservesItAndRegistersTheHash() {
        String qrCodeId = createQRCode();

        MockMvcResponse response = solana(qrCodeId, "SENT", HASH_1, PAYER_A, AMOUNT + 99);

        assertEquals(HttpStatus.OK.value(), response.statusCode(), "never judged: " + response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));

        List<Map<String, Object>> events = eventsFor(qrCodeId);
        assertEquals(List.of("payment.initiated", "payment.sent"),
            events.stream().map(event -> event.get("type")).toList());
        events.forEach(event -> {
            assertEquals(List.of(WRONG_STATUS), event.get("deviations"));
            assertEquals("ACTIVE", event.get("receivedInStatus"));
            assertEquals("1.1", event.get("schemaVersion"));
            assertEquals(HASH_1, event.get("transactionId"));
        });

        assertEquals(HttpStatus.CONFLICT.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_B).statusCode(),
            "another payer's pre-commit is then refused");
    }

    /**
     * <b>X9-HOLD-208</b> — a bank-rail post-commit on an ACTIVE QR Code reserves it with payment.initiated only (S2.4-AC4).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC4). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> The FedNow courtesy notification used to change nothing, so a QR Code already
     * paid stayed open to a second payer.
     */
    @Test
    void aBankRailPostCommitOnAnActiveQRCodeReservesItWithPaymentInitiatedOnly() {
        for (String[] rail : List.of(new String[] {"FedNow", E2E}, new String[] {"RTP", E2E},
                                     new String[] {"ACH", TRACE})) {
            String qrCodeId = createQRCode();

            assertEquals(HttpStatus.OK.value(), bank(qrCodeId, rail[0], rail[1], PAYER_A).statusCode());

            assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
            List<Map<String, Object>> events = eventsFor(qrCodeId);
            assertEquals(List.of("payment.initiated"), events.stream().map(event -> event.get("type")).toList());
            assertEquals(List.of(WRONG_STATUS), events.get(0).get("deviations"));
            assertEquals("ACTIVE", events.get(0).get("receivedInStatus"));
            assertEquals(HttpStatus.CONFLICT.value(), bank(qrCodeId, rail[0], null, PAYER_B).statusCode());
        }
    }

    // ------------------------------------------------------------------- S2.4-AC5

    /**
     * <b>X9-HOLD-209</b> — a Solana PAYMENT_INITIATED carrying a hash is a post-commit, recorded as received and flagged (S2.4-AC5).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC5). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> It was judged as a pre-commit and could be refused although the money had
     * moved, losing the payee's only link between the funds and the bill.
     */
    @Test
    void aPaymentInitiatedCarryingAHashIsAPostCommitRecordedAsReceived() {
        String qrCodeId = createQRCode();

        MockMvcResponse response = solana(qrCodeId, "PAYMENT_INITIATED", HASH_1, PAYER_A, AMOUNT + 5);

        assertEquals(HttpStatus.OK.value(), response.statusCode(),
            "no longer refused by the acceptance gate: the money has moved: " + response.asString());
        assertEquals("PAYMENT_INITIATED", given().when().get(CREATE + "/" + qrCodeId).then().extract()
            .path("paymentNotification.data.blockchain.action"), "its declared action is recorded as received");

        List<Map<String, Object>> events = eventsFor(qrCodeId);
        assertEquals(List.of("payment.initiated", "payment.sent"),
            events.stream().map(event -> event.get("type")).toList());
        events.forEach(event -> assertEquals(List.of(WRONG_STATUS, CONTRADICTS), event.get("deviations")));
    }

    /**
     * <b>X9-HOLD-210</b> — the holder's PAYMENT_INITIATED carrying a hash is flagged ACTION_CONTRADICTS_HASH only (S2.4-AC5).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC5). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> The flags must say which rule the payer broke, so the payee can take the count
     * back to that payer.
     */
    @Test
    void aPaymentInitiatedCarryingAHashFromTheHolderIsFlaggedOnlyAsAContradiction() {
        String qrCodeId = createQRCode();
        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A).statusCode());

        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "PAYMENT_INITIATED", HASH_1, PAYER_A).statusCode());

        Map<String, Object> sent = lastEventFor(qrCodeId);
        assertEquals("payment.sent", sent.get("type"));
        assertEquals(List.of(CONTRADICTS), sent.get("deviations"));
    }

    /**
     * <b>X9-HOLD-211</b> — a PAYMENT_INITIATED carrying a hash on a held or PAID QR Code carries both codes (S2.4-AC5).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC5). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> Both facts reach the payee: it paid in the wrong status, and it mislabelled
     * the report.
     */
    @Test
    void aPaymentInitiatedCarryingAHashOnAHeldOrClosedQRCodeCarriesBothCodes() {
        String held = createQRCode();
        assertEquals(HttpStatus.OK.value(), solana(held, "PAYMENT_INITIATED", null, PAYER_A).statusCode());
        assertEquals(HttpStatus.OK.value(), solana(held, "PAYMENT_INITIATED", HASH_1, PAYER_B).statusCode());
        assertEquals(List.of(WRONG_STATUS, CONTRADICTS), lastEventFor(held).get("deviations"));

        String paid = createQRCode();
        markPaid(paid);
        assertEquals(HttpStatus.OK.value(), solana(paid, "PAYMENT_INITIATED", HASH_1, PAYER_B).statusCode());
        assertEquals(List.of(WRONG_STATUS, CONTRADICTS), lastEventFor(paid).get("deviations"));
        assertEquals("PAID", statusOf(paid));
    }

    /**
     * <b>X9-HOLD-212</b> — SENT without a hash is a 400 in every status (S2.4-AC5).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC5). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> A committed transaction with no hash cannot exist, and there is nothing to
     * register.
     */
    @Test
    void sentWithoutAHashIsRefusedInEveryStatus() {
        String active = createQRCode();
        String heldBySender = createQRCode();
        solana(heldBySender, "PAYMENT_INITIATED", null, PAYER_A);
        String paid = createQRCode();
        markPaid(paid);

        for (String qrCodeId : List.of(active, heldBySender, paid)) {
            assertEquals(HttpStatus.BAD_REQUEST.value(), solana(qrCodeId, "SENT", null, PAYER_A).statusCode());
        }
        assertEquals(HttpStatus.BAD_REQUEST.value(), solana(heldBySender, "SENT", null, PAYER_B).statusCode(),
            "held by someone else: still 400, there is nothing to record");
    }

    /**
     * <b>X9-HOLD-213</b> — NOT_SENT still releases the reservation (S2.4-AC5).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC5). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> Keying the phase on the hash must not break the one message by which a payer
     * gives a QR Code back.
     */
    @Test
    void notSentStillReleases() {
        String qrCodeId = createQRCode();
        solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A);

        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "NOT_SENT", null, PAYER_A).statusCode());

        assertEquals("ACTIVE", statusOf(qrCodeId));
        assertEquals(List.of("payment.initiated", "payment.failed"), eventTypesFor(qrCodeId));
    }

    // ------------------------------------------------------------------- S2.4-AC6

    /**
     * <b>X9-HOLD-214</b> — every hash is registered as its own payment.sent, and X9.150 never marks PAID (S2.4-AC6).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC6). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> Two hashes for one QR Code are the payment system's only signal of a duplicate
     * payment.
     */
    @Test
    void everyHashIsRegisteredAsItsOwnPaymentSent() {
        String qrCodeId = createQRCode();
        solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A);

        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "SENT", HASH_1, PAYER_A).statusCode());
        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "SENT", HASH_2, PAYER_A).statusCode());

        List<Object> hashes = eventsFor(qrCodeId).stream()
            .filter(event -> "payment.sent".equals(event.get("type")))
            .map(event -> event.get("transactionId"))
            .toList();
        assertEquals(List.of(HASH_1, HASH_2), hashes, "the second beside the first, never overwriting it");
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId), "only the payment system marks PAID");
    }

    // ------------------------------------------------------------------- S2.4-AC7

    /**
     * <b>X9-HOLD-215</b> — a post-commit on a PAID or CANCELLED QR Code is registered, flagged, and nothing reopens (S2.4-AC7).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC7). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> Java answered 400; money that arrives for a closed bill still needs a link to
     * its QR Code, which may save the payment system hours.
     */
    @Test
    void aPostCommitOnAClosedQRCodeIsRegisteredAndChangesNothing() {
        String paid = createQRCode();
        markPaid(paid);
        String cancelled = createQRCode();
        cancel(cancelled);

        assertEquals(HttpStatus.OK.value(), solana(paid, "SENT", HASH_1, PAYER_A, AMOUNT + 3).statusCode());
        assertEquals(HttpStatus.OK.value(), solana(cancelled, "SENT", HASH_1, null).statusCode(),
            "from anyone, payer.info or not");

        assertEquals("PAID", statusOf(paid));
        assertEquals("CANCELLED", statusOf(cancelled));

        Map<String, Object> onPaid = lastEventFor(paid);
        assertEquals("payment.sent", onPaid.get("type"));
        assertEquals(List.of(WRONG_STATUS), onPaid.get("deviations"));
        assertEquals("PAID", onPaid.get("receivedInStatus"));

        Map<String, Object> onCancelled = lastEventFor(cancelled);
        assertEquals("payment.sent", onCancelled.get("type"));
        assertEquals("CANCELLED", onCancelled.get("receivedInStatus"));
    }

    /**
     * <b>X9-HOLD-216</b> — a bank-rail post-commit on a closed QR Code is accepted and publishes nothing (S2.4-AC7).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC7). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> Bank rails never publish payment.sent, and a closed QR Code never reopens.
     */
    @Test
    void aBankRailPostCommitOnAClosedQRCodeIsAcceptedAndPublishesNothing() {
        String paid = createQRCode();
        markPaid(paid);

        assertEquals(HttpStatus.OK.value(), bank(paid, "FedNow", E2E, PAYER_A).statusCode());

        assertEquals("PAID", statusOf(paid));
        assertEquals(List.of("payment.cleared"), eventTypesFor(paid));
    }

    // ------------------------------------------------------------------- S2.4-AC8

    /**
     * <b>X9-HOLD-217</b> — the payee can list, per payer, the post-commits flagged RECEIVED_IN_WRONG_STATUS (S2.4-AC8).
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC8). X9.150 defines no reservation and no phase
     * marker.
     *
     * <p><b>Why:</b> Counting misbehaviour per payer is what lets the payee take the numbers back
     * to the payer so it fixes its side.
     */
    @Test
    void thePayeeCanListPerPayerThePostCommitsFlaggedReceivedInWrongStatus() {
        // A skips the pre-commit; B pays while A holds; A pays again once the bill is PAID.
        String skipped = createQRCode();
        solana(skipped, "SENT", HASH_1, PAYER_A);

        String heldByA = createQRCode();
        solana(heldByA, "PAYMENT_INITIATED", null, PAYER_A);
        solana(heldByA, "SENT", HASH_2, PAYER_B);
        solana(heldByA, "SENT", HASH_1, PAYER_A);

        String paid = createQRCode();
        markPaid(paid);
        solana(paid, "SENT", HASH_2, PAYER_A);

        List<Map<String, Object>> flagged = new ArrayList<>();
        for (String qrCodeId : List.of(skipped, heldByA, paid)) {
            eventsFor(qrCodeId).stream()
                .filter(event -> "payment.sent".equals(event.get("type")))
                .filter(event -> event.get("deviations") instanceof List<?> codes && codes.contains(WRONG_STATUS))
                .forEach(flagged::add);
        }

        Map<Object, List<Object>> statusesByPayer = flagged.stream().collect(Collectors.groupingBy(
            event -> event.get("payerInfo"),
            Collectors.mapping(event -> event.get("receivedInStatus"), Collectors.toList())));

        assertEquals(Map.of(
                PAYER_A, List.of("ACTIVE", "PAID"),
                PAYER_B, List.of("PAYMENT_INITIATED")),
            statusesByPayer,
            "A skipped the pre-commit and paid a closed bill; B paid while A held the QR Code");

        Map<String, Object> holdersOwn = eventsFor(heldByA).get(eventsFor(heldByA).size() - 1);
        assertNull(holdersOwn.get("deviations"), "the holder's own post-commit is not flagged");
        assertEquals("PAYMENT_INITIATED", holdersOwn.get("receivedInStatus"));
        assertTrue(eventsFor(heldByA).stream().allMatch(event -> event.containsKey("receivedInStatus")),
            "every event a notification produced records the status it found");
    }

}
