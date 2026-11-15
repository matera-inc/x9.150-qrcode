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
import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The payload is priced at one instant: the later of the payer's {@code dateForPayment} and now
 * (INTERPRETATION I-14). The payment methods it shows are chosen at that same instant, so a method
 * shown is valid at the instant its amount, and its late fee, are computed.
 */
class PayloadPricingInstantApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String APPLICATION_JOSE = "application/jose";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String ACH =
        "{ \"routingNumber\": \"021000021\", \"accountNumber\": \"1234567890\", \"protectionType\": \"tokenized\" }";
    private static final long BILL = 10_000_000L;
    private static final long FIXED_FEE = 500_000L;
    private static final long PER_DAY = 1_000L;

    @AfterEach
    void resetTheClock() {
        BusinessClock.reset();
    }

    private static String utc(OffsetDateTime moment) {
        return moment.toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** USD on ACH until {@code usdEnds}, USDC on Solana until the QR Code's own validUntil. */
    private JsonPath create(OffsetDateTime dueDate, OffsetDateTime usdEnds, OffsetDateTime validUntil) {
        String body = """
            {
              "validUntil": "%s",
              "creditor": {
                "name": "Pricing Instant Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": {
                "description": "pricing instant",
                "invoice": { "number": "INV-1", "date": "%s", "dueDate": "%s" },
                "amountDue": {
                  "amount": %d, "currency": "USD",
                  "adjustments": {
                    "formula": "FixedDiscountLateFeeLinearInterest",
                    "parameters": { "lateFees": { "fixed": %d, "perDay": %d, "explanation": "late" } }
                  }
                }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "%s", "amount": %d, "networks": { "ach": %s } },
                { "currency": "USDC", "validUntil": "%s", "amount": %d, "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(utc(validUntil), LocalDate.now(ZoneOffset.UTC), utc(dueDate), BILL, FIXED_FEE, PER_DAY,
                          utc(usdEnds), BILL, ACH, utc(validUntil), BILL, RECIPIENT);

        String created = given().contentType("application/json").body(body)
            .when().post(CREATE)
            .then().statusCode(HttpStatus.CREATED.value())
            .extract().body().asString();

        return JsonPath.from(created);
    }

    private String sign(String payload) {
        return given().contentType("application/json")
            .header("Correlation-Id", UUID.randomUUID().toString())
            .header("TTL-Seconds", "300")
            .body(payload)
            .when().post("/api/v1/signature/generate")
            .then().statusCode(HttpStatus.OK.value())
            .extract().body().asString();
    }

    private JsonNode payload(JsonPath created, LocalDate dateForPayment) throws Exception {
        MockMvcResponse response = given().contentType(APPLICATION_JOSE)
            .header("dateForPayment", dateForPayment.toString())
            .body(sign("{\"qrCodeContent\": \"%s\"}".formatted(created.getString("qrCodeB64"))))
            .when().post("/pub/api/v1/loc/" + created.getString("location.id"));

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());

        return objectMapper.readTree(JWSObject.parse(response.asString()).getPayload().toString());
    }

    private static List<String> currencies(JsonNode payload) {
        List<String> currencies = new ArrayList<>();
        payload.at("/paymentMethods").forEach(method -> currencies.add(method.at("/currency").asText()));
        return currencies;
    }

    /**
     * <b>X9-TIME-020</b> — a {@code dateForPayment} already in the past does not bring back a payment
     * method that has expired: methods are chosen at the instant the late fee is computed, the later
     * of the payer's date and now.
     *
     * <p><b>Source:</b> Ours. INTERPRETATION I-14 ("one instant"); owner decision 2026-11-14. ANSI
     * X9.150-2026 gives each payment method its own {@code validUntil} and leaves the rest to us.
     *
     * <p><b>Why:</b> The header is a calendar date, read as that day's UTC midnight. The fee was
     * computed at now, but methods were chosen at midnight, so all day long a method that expired
     * that morning was still offered, with a fee counted past its own {@code validUntil}.
     */
    @Test
    void aPastDateForPaymentDoesNotBringBackAnExpiredMethod() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime dueDate = now.plusMinutes(30);
        OffsetDateTime usdEnds = now.plusHours(1);
        JsonPath created = create(dueDate, usdEnds, now.plusHours(3));

        BusinessClock.setAt(usdEnds.plus(1, ChronoUnit.MILLIS).toInstant());
        JsonNode payload = payload(created, usdEnds.toLocalDate());

        assertEquals(List.of("USDC"), currencies(payload),
            "the USD method expired a millisecond ago; its day's midnight must not bring it back");
        assertEquals(BILL + FIXED_FEE + PER_DAY, payload.at("/paymentMethods/0/amount").asLong(),
            "priced at the same instant: one day late");
    }

    /**
     * <b>X9-TIME-021</b> — a future {@code dateForPayment} still chooses the methods by that date:
     * the instant moves forward to the payer's date, never back from now.
     *
     * <p><b>Source:</b> Ours. INTERPRETATION I-14.
     *
     * <p><b>Why:</b> The control for X9-TIME-020: the fix must not stop a payer from asking what will
     * be on offer on a later day.
     */
    @Test
    void aFutureDateForPaymentChoosesTheMethodsValidThatDay() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime usdEnds = now.plusDays(2);
        JsonPath created = create(now.plusDays(1), usdEnds, now.plusDays(10));

        assertEquals(List.of("USD", "USDC"), currencies(payload(created, now.toLocalDate().plusDays(1))).stream()
            .sorted().toList());
        assertEquals(List.of("USDC"), currencies(payload(created, usdEnds.toLocalDate().plusDays(1))));
    }

}
