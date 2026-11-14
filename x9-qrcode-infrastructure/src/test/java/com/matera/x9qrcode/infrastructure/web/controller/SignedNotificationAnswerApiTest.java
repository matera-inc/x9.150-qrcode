/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.domain.utils.BusinessClock;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every answer to a payment notification is signed (Story 2.3, ANSI X9.150 A.4.5 note).
 *
 * <p>The note under A.4.5 asks that the answer's JWS body be {@code {"statusCode": integer}}, so the
 * payer holds the payee's decision signed. Before this, an accepted notification was answered with an
 * empty, unsigned 200 and a refusal with plain JSON: the payer held a signed question and nothing
 * signed in reply.
 *
 * <p>The notifications here use Solana, the rail whose pre-commit and post-commit are told apart by
 * the transaction hash on every build of this service, and they carry {@code payer.info} so that the
 * holder is identified the same way whatever the holder rules become.
 */
class SignedNotificationAnswerApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String APPLICATION_JOSE = "application/jose";

    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String PAYER_WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU";
    private static final String TX_HASH = "5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f";
    private static final String PAYER = "Jane Payer, Springfield Savings";
    private static final long AMOUNT = 25_000_000L;
    private static final long DISCOUNT = 1_000_000L;

    private static final Set<String> CRITICAL_HEADERS = Set.of("correlationId", "iat", "ttl", "statusCode");

    // ------------------------------------------------------------------------------- fixtures

    private static String utc(OffsetDateTime moment) {
        // Instant.toString always prints the seconds; OffsetDateTime.toString drops ":00", which the API refuses.
        return moment.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    private static String qrCodeBody(String dueDate, String discounts) {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Signed Answer", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": {
                "description": "signed answers",
                "invoice": { "number": "INV-ANS-1", "date": "%s", "dueDate": "%s" },
                "amountDue": {
                  "amount": %d, "currency": "USDC",
                  "adjustments": {
                    "formula": "FixedDiscountLateFeeLinearInterest",
                    "parameters": {
                      "discounts": %s,
                      "lateFees": { "fixed": 0, "perDay": 0, "explanation": "none" }
                    }
                  }
                }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(OffsetDateTime.now(ZoneOffset.UTC).toLocalDate(), dueDate, AMOUNT, discounts, AMOUNT,
                          RECIPIENT);
    }

    private String createQRCode() {
        return create(qrCodeBody("2030-12-31T23:59:59Z", "[]"));
    }

    private String create(String body) {
        return given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    private static String notification(String qrCodeId, long amount, String action, String transactionId,
                                       String payerInfo) {
        String transactionField = transactionId == null ? "" : "\"transactionId\": \"%s\",".formatted(transactionId);

        return """
            {
              "payment": { "qrcodeId": "%s", %s "amount": %d, "currency": "USDC", "network": "Solana" },
              "payer": { "info": "%s" },
              "expectedDate": "2030-10-08T06:59:59Z",
              "blockchain": { "action": "%s", "to": "%s", "from": "%s" }
            }
            """.formatted(qrCodeId, transactionField, amount, payerInfo, action, RECIPIENT, PAYER_WALLET);
    }

    /** A signed question, and the correlationId it was signed under. */
    private record Question(String jws, UUID correlationId) { }

    private Question sign(String payload) {
        UUID correlationId = UUID.randomUUID();
        String jws = given().contentType("application/json")
                .header("Correlation-Id", correlationId.toString())
                .header("TTL-Seconds", "300")
                .body(payload)
                .when().post("/api/v1/signature/generate")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();

        return new Question(jws, correlationId);
    }

    /** A question signed while this deployment's clock reads {@code seconds} ahead: its iat is in the future. */
    private Question signAhead(String payload, long seconds) {
        BusinessClock.setOffsetSeconds(seconds);
        try {
            return sign(payload);
        } finally {
            BusinessClock.reset();
        }
    }

    private MockMvcResponse post(String body) {
        return given().contentType(APPLICATION_JOSE).body(body).when().post(NOTIFY);
    }

    private String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    /** Verifies with the public JWK set only, as a third party holding no secret would. */
    private static JWSVerifier publicVerifier() throws Exception {
        String jwks = given().when().get("/pub/.well-known/jwks")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();

        JWK key = JWKSet.parse(jwks).getKeys().getFirst();

        return key instanceof ECKey ec
            ? new ECDSAVerifier(ec.toECPublicKey(), CRITICAL_HEADERS)
            : new RSASSAVerifier(((RSAKey) key).toRSAPublicKey(), CRITICAL_HEADERS);
    }

    private JsonNode bodyOf(JWSObject jws) throws Exception {
        return objectMapper.readTree(jws.getPayload().toString());
    }

    private static UUID correlationIdOf(JWSObject jws) {
        return UUID.fromString(String.valueOf(jws.getHeader().getCustomParam("correlationId")));
    }

    /** Asserts the shape every answer has, whatever it says, and returns it parsed. */
    private JWSObject assertSignedAnswer(MockMvcResponse response, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, response.statusCode(), response.asString());
        assertTrue(String.valueOf(response.contentType()).startsWith(APPLICATION_JOSE),
                "every answer is application/jose: " + response.contentType());

        JWSObject answer = JWSObject.parse(response.asString());

        assertTrue(answer.verify(publicVerifier()), "the answer must verify against the published key");
        assertEquals(expectedStatus, bodyOf(answer).path("statusCode").asInt(-1),
                "the signed body carries the status (A.4.5 note): " + answer.getPayload());

        return answer;
    }

    // ----------------------------------------------------------------------------- the yes

    /**
     * <b>X9-SIG-120</b> — an accepted pre-commit is answered with a signed {@code {"statusCode": 200}}.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 A.4.5 note; §10.3.2 (the responder echoes correlationId).
     * S2.3-AC1.
     *
     * <p><b>Why:</b> An empty 200 left the payer holding nothing that proves the payee said yes. The
     * answer's iat is the payee's clock at the decision, so it must fall inside the call.
     */
    @Test
    void anAcceptedPreCommitIsAnsweredWithASignedYes() throws Exception {
        String qrCodeId = createQRCode();
        Question question = sign(notification(qrCodeId, AMOUNT, "PAYMENT_INITIATED", null, PAYER));

        long before = Instant.now().toEpochMilli();
        MockMvcResponse response = post(question.jws());
        long after = Instant.now().toEpochMilli();

        JWSObject answer = assertSignedAnswer(response, HttpStatus.OK.value());

        assertEquals(question.correlationId(), correlationIdOf(answer));
        assertEquals("{\"statusCode\":200}", bodyOf(answer).toString(), "the yes is exactly the A.4.5 body");

        long iat = ((Number) answer.getHeader().getCustomParam("iat")).longValue();
        assertTrue(iat >= before && iat <= after, "iat is the payee's clock at the decision: " + iat);
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    /**
     * <b>X9-SIG-121</b> — an accepted post-commit is answered with a signed yes too.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 A.4.5 note. S2.3-AC1.
     *
     * <p><b>Why:</b> The report that the money moved is the one a payer most needs acknowledged in a
     * form a third party can check.
     */
    @Test
    void anAcceptedPostCommitIsAnsweredWithASignedYes() throws Exception {
        String qrCodeId = createQRCode();
        assertSignedAnswer(post(sign(notification(qrCodeId, AMOUNT, "PAYMENT_INITIATED", null, PAYER)).jws()),
                HttpStatus.OK.value());

        Question report = sign(notification(qrCodeId, AMOUNT, "SENT", TX_HASH, PAYER));
        JWSObject answer = assertSignedAnswer(post(report.jws()), HttpStatus.OK.value());

        assertEquals(report.correlationId(), correlationIdOf(answer));
    }

    // ------------------------------------------------------------------------------ the no

    /**
     * <b>X9-SIG-122</b> — a refusal keeps its status and error fields, signed, correlationId echoed.
     *
     * <p><b>Source:</b> Ours (owner decision, 2026-11-13), extending the A.4.5 note to refusals.
     * S2.3-AC2.
     *
     * <p><b>Why:</b> A payer told "no" needs that no as evidence as much as a yes. The HTTP status and
     * the fields a client already reads are kept, so only the encoding changes.
     */
    @Test
    void aRefusalKeepsItsStatusAndErrorFieldsSigned() throws Exception {
        String qrCodeId = createQRCode();
        Question question = sign(notification(qrCodeId, AMOUNT - 1, "PAYMENT_INITIATED", null, PAYER));

        JWSObject answer = assertSignedAnswer(post(question.jws()), HttpStatus.BAD_REQUEST.value());
        JsonNode body = bodyOf(answer);

        assertEquals(question.correlationId(), correlationIdOf(answer));
        assertFalse(body.path("type").asText().isBlank(), body.toString());
        assertFalse(body.path("title").asText().isBlank(), body.toString());
        assertFalse(body.path("detail").asText().isBlank(), body.toString());
        assertTrue(body.path("violations").isArray() && !body.path("violations").isEmpty(), body.toString());
        assertEquals("ACTIVE", statusOf(qrCodeId), "a refusal changes nothing");
    }

    /**
     * <b>X9-SIG-123</b> — another holder's 409 is signed as well.
     *
     * <p><b>Source:</b> Ours. ADR-0021 (another payer gets 409). S2.3-AC2.
     *
     * <p><b>Why:</b> The status a payer acts on ("come back later") must survive the signing.
     */
    @Test
    void anotherHoldersConflictIsSigned() throws Exception {
        String qrCodeId = createQRCode();
        assertSignedAnswer(post(sign(notification(qrCodeId, AMOUNT, "PAYMENT_INITIATED", null, PAYER)).jws()),
                HttpStatus.OK.value());

        Question second = sign(notification(qrCodeId, AMOUNT, "PAYMENT_INITIATED", null, "John Other, Elsewhere Bank"));
        JWSObject answer = assertSignedAnswer(post(second.jws()), HttpStatus.CONFLICT.value());

        assertEquals(second.correlationId(), correlationIdOf(answer));
    }

    /**
     * <b>X9-SIG-124</b> — a question that cannot be read is refused, signed, under a fresh correlationId.
     *
     * <p><b>Source:</b> Ours. S2.3-AC2.
     *
     * <p><b>Why:</b> A tampered or malformed question carries no correlationId this payee can trust, so
     * echoing one would sign a claim nobody verified. The refusal is still signed, so the sender can
     * tell it came from this payee.
     */
    @Test
    void aQuestionThatCannotBeReadIsRefusedSignedUnderAFreshCorrelationId() throws Exception {
        String qrCodeId = createQRCode();
        Question question = sign(notification(qrCodeId, AMOUNT, "PAYMENT_INITIATED", null, PAYER));
        String[] parts = question.jws().split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();

        JWSObject tamperedAnswer = assertSignedAnswer(post(tampered), HttpStatus.UNAUTHORIZED.value());
        assertNotEquals(question.correlationId(), correlationIdOf(tamperedAnswer),
                "an unverified correlationId is never echoed");

        JWSObject garbageAnswer = assertSignedAnswer(post("not a jws"), HttpStatus.UNAUTHORIZED.value());
        assertNotEquals(correlationIdOf(tamperedAnswer), correlationIdOf(garbageAnswer));
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

    // -------------------------------------------------------------------- after the edge

    /**
     * <b>X9-SIG-125</b> — a SENT report after the discount edge is recorded and answered with a signed yes.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (post-commit is recorded, never re-judged). S2.3-AC3.
     *
     * <p><b>Why:</b> The payee approved the discounted amount before the edge; the money moved on that
     * approval. Re-checking the report against the clock would refuse a payment the payee itself
     * authorized. The story states this at 23:59:50 and 00:00:05; here the edge is a few seconds
     * after creation, which is the same rule without waiting for midnight.
     */
    @Test
    void aSentReportAfterTheDiscountEdgeIsRecordedAndAnsweredWithASignedYes() throws Exception {
        // The discount applies until dueDate - 1 day, which is set a few seconds from now.
        OffsetDateTime edge = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(8).truncatedTo(ChronoUnit.SECONDS);
        String discounts = "[ { \"daysBefore\": 1, \"discount\": %d, \"explanation\": \"early\" } ]".formatted(DISCOUNT);
        String qrCodeId = create(qrCodeBody(utc(edge.plusDays(1)), discounts));
        long discounted = AMOUNT - DISCOUNT;

        assertSignedAnswer(post(sign(notification(qrCodeId, discounted, "PAYMENT_INITIATED", null, PAYER)).jws()),
                HttpStatus.OK.value());

        Thread.sleep(Math.max(0, edge.toInstant().toEpochMilli() - Instant.now().toEpochMilli()) + 1_000L);

        Question report = sign(notification(qrCodeId, discounted, "SENT", TX_HASH, PAYER));
        JWSObject answer = assertSignedAnswer(post(report.jws()), HttpStatus.OK.value());

        assertEquals(report.correlationId(), correlationIdOf(answer));
    }

    // -------------------------------------------------------------------- the evidence

    /**
     * <b>X9-SIG-126</b> — with public keys only, a verifier links question and answer and reads what
     * was authorized and when.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §10.3.2, A.4.5 note. S2.3-AC4.
     *
     * <p><b>Why:</b> This is what the signed answer is for: evidence a third party can check without
     * any secret, and without asking either party.
     */
    @Test
    void aVerifierWithPublicKeysOnlyLinksQuestionAndAnswer() throws Exception {
        String qrCodeId = createQRCode();
        Question question = sign(notification(qrCodeId, AMOUNT, "PAYMENT_INITIATED", null, PAYER));
        MockMvcResponse response = post(question.jws());

        JWSVerifier verifier = publicVerifier();
        JWSObject signedQuestion = JWSObject.parse(question.jws());
        JWSObject signedAnswer = JWSObject.parse(response.asString());

        assertTrue(signedQuestion.verify(verifier), "the question verifies with public keys only");
        assertTrue(signedAnswer.verify(verifier), "the answer verifies with public keys only");
        assertEquals(correlationIdOf(signedQuestion), correlationIdOf(signedAnswer), "linked by correlationId");

        JsonNode payment = bodyOf(signedQuestion).path("payment");
        assertEquals(AMOUNT, payment.path("amount").asLong());
        assertEquals("USDC", payment.path("currency").asText());
        assertEquals(200, bodyOf(signedAnswer).path("statusCode").asInt());
        assertTrue(signedAnswer.getHeader().getCustomParam("iat") instanceof Number,
                "the decision instant is the answer's iat");
    }

    // ----------------------------------------------------------------- a question from the future

    /**
     * <b>X9-SIG-145</b> — a question whose iat is in the future beyond the allowed skew is refused,
     * signed, as unverifiable; within the skew it is accepted.
     *
     * <p><b>Source:</b> Ours (owner decision, Story 2.3 follow-up). ANSI X9.150-2026 §10.3.2 defines
     * {@code iat} and {@code ttl} but no skew. The same rule as for the answers we check (X9-SIG-140).
     *
     * <p><b>Why:</b> {@code iat + ttl} is the window in which a signature counts. A question dated
     * ahead stretches that window past what its signer's real clock allowed, so a captured question
     * could be held back and replayed later. The payee's clock judges (I-13); the skew
     * ({@code x9.public-endpoints.jws-clock-skew-seconds}, 60 by default) forgives honest drift.
     */
    @Test
    void aQuestionFromTheFutureIsRefusedSignedAndWithinTheSkewItIsAccepted() throws Exception {
        String qrCodeId = createQRCode();
        Question ahead = signAhead(notification(qrCodeId, AMOUNT, "PAYMENT_INITIATED", null, PAYER), 300);

        JWSObject refusal = assertSignedAnswer(post(ahead.jws()), HttpStatus.UNAUTHORIZED.value());
        assertNotEquals(ahead.correlationId(), correlationIdOf(refusal), "an unverified correlationId is never echoed");
        assertEquals("ACTIVE", statusOf(qrCodeId), "a refused question changes nothing");

        Question drifting = signAhead(notification(qrCodeId, AMOUNT, "PAYMENT_INITIATED", null, PAYER), 30);
        JWSObject yes = assertSignedAnswer(post(drifting.jws()), HttpStatus.OK.value());
        assertEquals(drifting.correlationId(), correlationIdOf(yes));
    }
}
