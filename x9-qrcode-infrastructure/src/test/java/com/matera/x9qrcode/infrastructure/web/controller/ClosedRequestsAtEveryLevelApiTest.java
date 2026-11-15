/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.stream.Stream;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What we generate is closed at every level (INTERPRETATION I-16, owner 2026-11-15).
 *
 * <p>A create, a PATCH and an outbound payment notification refuse an unknown field wherever it is:
 * at the top, in the creditor, the bill and every object under it, a payment method, its editable
 * range, a rail. Each refusal names the field's path and the fields the object does have, in the same
 * words the Go build uses. The only open object is {@code networks}, which receives an unknown network
 * in order to refuse it by name (I-9).
 *
 * <p>Each case starts from a body with every optional object present, so every level has a parent to
 * carry the unknown field, and that body unbroken is accepted (the control of each family).
 */
class ClosedRequestsAtEveryLevelApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String PRE_PAYMENT = "/api/v1/payment-notification/pre-payment";
    private static final String NOBODY = "http://127.0.0.1:9/pub/api/v1/payment-notification";
    private static final String UNKNOWN = "zzUnknown";

    private static final String ADDRESS = "line1, line2, city, state, postalCode and country";
    private static final String PARTY = "name, phone, email and address";
    private static final String BILL = "paymentTiming, description, order, invoice, tip and amountDue";
    private static final String METHOD = "currency, validUntil, amount, editable and networks";
    private static final String BANK = "routingNumber, accountNumber and protectionType";

    private static final String PARTY_JSON = """
        { "name": "Closed Party", "phone": "+15552223333", "email": "party@example.com",
          "address": { "line1": "1 A St", "line2": "Suite 2", "city": "Los Angeles", "state": "CA",
                       "postalCode": "90012", "country": "US" } }""";

    private static final String BANK_JSON =
        "{ \"routingNumber\": \"021000021\", \"accountNumber\": \"1234567890\", \"protectionType\": \"plaintext\" }";

    /** A create with every optional object present. */
    private static final String FULL_CREATE = """
        {
          "validUntil": "2030-12-31T23:59:59Z",
          "creditor": { "name": "Closed Co", "phone": "+15552223333", "email": "party@example.com",
            "address": { "line1": "1 A St", "line2": "Suite 2", "city": "Los Angeles", "state": "CA",
                         "postalCode": "90012", "country": "US" },
            "MCC": "4900",
            "ultimateCreditor": { "name": "Closed Party", "phone": "+15552223333", "email": "party@example.com",
              "address": { "line1": "1 A St", "city": "Los Angeles", "country": "US" },
              "account": { "id": "ACC-1", "schemaName": "internal" } } },
          "bill": { "description": "closed at every level", "paymentTiming": "immediate",
            "order": { "number": "ORD-1", "date": "2030-01-10" },
            "invoice": { "number": "INV-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z",
                         "invoicee": %s },
            "tip": { "allowed": true, "range": { "min": 0, "max": 25 }, "presets": [10, 15] },
            "amountDue": { "amount": 1000, "currency": "USD", "adjustments": {
              "formula": "FixedDiscountLateFeeLinearInterest",
              "parameters": {
                "discounts": [ { "daysBefore": 5, "discount": 50, "explanation": "Early payment" } ],
                "lateFees": { "fixed": 100, "perDay": 10, "explanation": "Late fee" } } } } },
          "unstructured": "closed",
          "additionalInformation": [ { "key": "Note", "value": "closed" } ],
          "paymentNotification": { "kind": "DEFAULT" },
          "paymentMethods": [
            { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
              "editable": { "range": { "min": 500, "max": 2000 } },
              "networks": { "fednow": %s, "rtp": %s, "ach": %s } },
            { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
              "networks": { "solana": { "recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "memo": "m" } } }
          ]
        }
        """.formatted(PARTY_JSON, BANK_JSON, BANK_JSON, BANK_JSON);

    /** A PATCH restating everything a PATCH may carry (amountDue has no currency on PATCH). */
    private static final String FULL_PATCH = """
        {
          "validUntil": "2030-12-31T23:59:59Z",
          "bill": { "description": "closed, patched", "paymentTiming": "immediate",
            "order": { "number": "ORD-1", "date": "2030-01-10" },
            "invoice": { "number": "INV-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z",
                         "invoicee": %s },
            "tip": { "allowed": true, "range": { "min": 0, "max": 25 }, "presets": [10, 15] },
            "amountDue": { "amount": 1000, "adjustments": {
              "formula": "FixedDiscountLateFeeLinearInterest",
              "parameters": {
                "discounts": [ { "daysBefore": 5, "discount": 50, "explanation": "Early payment" } ],
                "lateFees": { "fixed": 100, "perDay": 10, "explanation": "Late fee" } } } } },
          "unstructured": "closed",
          "additionalInformation": [ { "key": "Note", "value": "closed" } ],
          "paymentMethods": [
            { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
              "editable": { "range": { "min": 500, "max": 2000 } },
              "networks": { "fednow": %s, "rtp": %s, "ach": %s } },
            { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
              "networks": { "solana": { "recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "memo": "m" } } }
          ]
        }
        """.formatted(PARTY_JSON, BANK_JSON, BANK_JSON, BANK_JSON);

    /** An outbound pre-payment with every optional object present, to a payee nobody answers for. */
    private static final String FULL_OUTBOUND = """
        {
          "endpoint": "%s",
          "correlationId": "8a652794-9e73-4fd7-a628-4d525b638cbc",
          "notification": {
            "payment": { "qrcodeId": "01A0E3A12805CB382EF4687F18CDC43A", "amount": 1000, "tipAmount": 0,
                         "currency": "USDC", "network": "solana" },
            "payer": { "info": "p_7f3a9c21e45b" },
            "expectedDate": "2030-10-08T06:59:59Z",
            "blockchain": { "action": "PAYMENT_INITIATED",
                            "from": "5xoBq7f7CDgZwqHrDBdRWM84ExRetg4gZq93dyJtoSwp",
                            "to": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM" } }
        }
        """.formatted(NOBODY);

    /** Every object of a create: where it is (a JSON pointer), what a refusal calls it, and its fields. */
    static Stream<Arguments> createLevels() {
        return Stream.of(
            Arguments.of("", "the payment request",
                "validUntil, creditor, bill, locationId, unstructured, additionalInformation, paymentNotification and paymentMethods"),
            Arguments.of("/creditor", "creditor", "name, phone, email, address, ultimateCreditor and MCC"),
            Arguments.of("/creditor/address", "address", ADDRESS),
            Arguments.of("/creditor/ultimateCreditor", "ultimateCreditor", "account, name, phone, email and address"),
            Arguments.of("/creditor/ultimateCreditor/account", "account", "id and schemaName"),
            Arguments.of("/creditor/ultimateCreditor/address", "address", ADDRESS),
            Arguments.of("/bill", "bill", BILL),
            Arguments.of("/bill/order", "order", "number and date"),
            Arguments.of("/bill/invoice", "invoice", "number, date, dueDate and invoicee"),
            Arguments.of("/bill/invoice/invoicee", "invoicee", PARTY),
            Arguments.of("/bill/invoice/invoicee/address", "address", ADDRESS),
            Arguments.of("/bill/tip", "tip", "allowed, range and presets"),
            Arguments.of("/bill/tip/range", "range", "min and max"),
            Arguments.of("/bill/amountDue", "amountDue", "amount, currency and adjustments"),
            Arguments.of("/bill/amountDue/adjustments", "adjustments", "formula and parameters"),
            Arguments.of("/bill/amountDue/adjustments/parameters", "parameters", "discounts and lateFees"),
            Arguments.of("/bill/amountDue/adjustments/parameters/discounts/0", "a discount",
                "daysBefore, discount and explanation"),
            Arguments.of("/bill/amountDue/adjustments/parameters/lateFees", "lateFees", "fixed, perDay and explanation"),
            Arguments.of("/additionalInformation/0", "an additionalInformation entry", "key and value"),
            Arguments.of("/paymentNotification", "paymentNotification", "kind and endpoint"),
            Arguments.of("/paymentMethods/0", "a payment method", METHOD),
            Arguments.of("/paymentMethods/0/editable", "editable", null),
            Arguments.of("/paymentMethods/0/editable/range", "range", "min and max"),
            Arguments.of("/paymentMethods/0/networks/fednow", "fednow", BANK),
            Arguments.of("/paymentMethods/1/networks/solana", "solana", "recipient and memo"));
    }

    /** Every object of a PATCH. */
    static Stream<Arguments> patchLevels() {
        return Stream.of(
            Arguments.of("", "the payment request",
                "locationId, validUntil, bill, unstructured, additionalInformation and paymentMethods"),
            Arguments.of("/bill", "bill", BILL),
            Arguments.of("/bill/order", "order", "number and date"),
            Arguments.of("/bill/invoice", "invoice", "number, date, dueDate and invoicee"),
            Arguments.of("/bill/invoice/invoicee", "invoicee", PARTY),
            Arguments.of("/bill/invoice/invoicee/address", "address", ADDRESS),
            Arguments.of("/bill/tip", "tip", "allowed, range and presets"),
            Arguments.of("/bill/tip/range", "range", "min and max"),
            Arguments.of("/bill/amountDue", "amountDue", "amount and adjustments"),
            Arguments.of("/bill/amountDue/adjustments", "adjustments", "formula and parameters"),
            Arguments.of("/bill/amountDue/adjustments/parameters", "parameters", "discounts and lateFees"),
            Arguments.of("/bill/amountDue/adjustments/parameters/discounts/0", "a discount",
                "daysBefore, discount and explanation"),
            Arguments.of("/bill/amountDue/adjustments/parameters/lateFees", "lateFees", "fixed, perDay and explanation"),
            Arguments.of("/additionalInformation/0", "an additionalInformation entry", "key and value"),
            Arguments.of("/paymentMethods/0", "a payment method", METHOD),
            Arguments.of("/paymentMethods/0/editable", "editable", null),
            Arguments.of("/paymentMethods/0/editable/range", "range", "min and max"),
            Arguments.of("/paymentMethods/0/networks/ach", "ach", BANK),
            Arguments.of("/paymentMethods/1/networks/solana", "solana", "recipient and memo"));
    }

    /** Every object of an outbound payment notification. */
    static Stream<Arguments> outboundLevels() {
        return Stream.of(
            Arguments.of("", "the outbound notification", "endpoint, correlationId and notification"),
            Arguments.of("/notification", "notification", "payment, payer, expectedDate and blockchain"),
            Arguments.of("/notification/payment", "payment",
                "qrcodeId, amount, tipAmount, currency, network and transactionId"),
            Arguments.of("/notification/payer", "payer", null),
            Arguments.of("/notification/blockchain", "blockchain", "action, from and to"));
    }

    /** The violation an unknown field at {@code pointer} gets. {@code fields} null: the object's one field. */
    private static String expected(String pointer, String name, String fields) {
        String path = pointer.isEmpty() ? UNKNOWN
            : pointer.substring(1).replaceAll("/(\\d+)", "[$1]").replace('/', '.') + "." + UNKNOWN;
        String only = switch (name) {
            case "editable" -> "range";
            case "payer" -> "info";
            default -> null;
        };
        return path + ": " + (fields == null
            ? "is not a field of %s, whose only field is %s".formatted(name, only)
            : "is not a field of %s, whose fields are %s".formatted(name, fields));
    }

    private String withUnknown(String json, String pointer) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(json);
        ((ObjectNode) body.at(pointer)).put(UNKNOWN, true);
        return objectMapper.writeValueAsString(body);
    }

    private MockMvcResponse post(String path, String body) {
        return given().contentType("application/json").body(body).when().post(path);
    }

    private String create() {
        MockMvcResponse created = post(CREATE, FULL_CREATE);
        assertEquals(HttpStatus.CREATED.value(), created.statusCode(), created.asString());
        return created.jsonPath().getString("id");
    }

    private static List<String> violations(MockMvcResponse response) {
        return JsonPath.from(response.asString()).getList("violations");
    }

    /**
     * <b>X9-RAIL-058</b> — an unknown field at any level of a create is refused, naming its path and
     * the fields the object does have.
     *
     * <p><b>Source:</b> Ours. INTERPRETATION I-16 (owner, 2026-11-15): what we generate is closed at
     * every level, and {@code openapi.yaml} declares every object of a create
     * {@code additionalProperties: false} except {@code networks}. X9.150 fixes these objects' members
     * and says nothing about an extra one; refusing it is our reading.
     *
     * <p><b>Why:</b> Until now only the rails refused one; everywhere else the field was dropped with a
     * 201. A biller who wrote {@code bill.invoice.dueDte}, or put a {@code reference} on the creditor
     * expecting a payer to see it, printed a QR Code without it and was never told. The body is
     * otherwise the full sample, which the first case of each family proves is accepted.
     */
    @ParameterizedTest(name = "create: an unknown field in {1} at \"{0}\" is refused")
    @MethodSource("createLevels")
    void anUnknownFieldAtAnyLevelOfACreateIsRefused(String pointer, String name, String fields) throws Exception {
        MockMvcResponse response = post(CREATE, withUnknown(FULL_CREATE, pointer));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertEquals(List.of(expected(pointer, name, fields)), violations(response), response.asString());
    }

    /**
     * <b>X9-RAIL-059</b> — an unknown field at any level of a PATCH is refused, naming its path, and the
     * QR Code is left as it was: same revision, same ETag.
     *
     * <p><b>Source:</b> Ours. INTERPRETATION I-16: a PATCH restates the request, and what it restates
     * is closed as a create is.
     *
     * <p><b>Why:</b> A refusal at create that a PATCH lets through is no refusal. The full PATCH, which
     * the control accepts, is broken at one level per case. Note what it does NOT carry:
     * {@code bill.amountDue.currency}, which a PATCH cannot change and which is now refused rather than
     * dropped.
     */
    @ParameterizedTest(name = "PATCH: an unknown field in {1} at \"{0}\" is refused")
    @MethodSource("patchLevels")
    void anUnknownFieldAtAnyLevelOfAPatchIsRefusedAndChangesNothing(String pointer, String name, String fields)
        throws Exception {
        String id = create();
        MockMvcResponse before = given().when().get(CREATE + "/" + id);

        MockMvcResponse response = given().contentType("application/json")
            .body(withUnknown(FULL_PATCH, pointer))
            .when().patch(CREATE + "/" + id);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertEquals(List.of(expected(pointer, name, fields)), violations(response), response.asString());

        MockMvcResponse after = given().when().get(CREATE + "/" + id);
        assertEquals(before.jsonPath().getInt("revision"), after.jsonPath().getInt("revision"));
        assertEquals(before.header("ETag"), after.header("ETag"), "a refused PATCH moved the ETag");
        assertEquals("closed at every level", after.jsonPath().getString("bill.description"));
    }

    /**
     * <b>X9-RAIL-065</b> — an unknown field at any level of an outbound payment notification is refused,
     * naming its path, before anything is sent.
     *
     * <p><b>Source:</b> Ours. INTERPRETATION I-16: the body of {@code pre-payment} and
     * {@code post-payment} is what this service signs and sends to a payee, so it is something we
     * generate, and closed ({@code OutboundPaymentNotification}, {@code OutboundPaymentNotificationData}).
     * What a payee READS from another bank ({@code PaymentNotificationData}) stays open.
     *
     * <p><b>Why:</b> The field was dropped and the notification signed and sent without it: the payer's
     * software believed the payee had received something it never did. The control sends the unbroken
     * body to a payee nobody answers for and gets 502, so a 400 here is the refusal, not the transport.
     */
    @ParameterizedTest(name = "outbound: an unknown field in {1} at \"{0}\" is refused")
    @MethodSource("outboundLevels")
    void anUnknownFieldAtAnyLevelOfAnOutboundNotificationIsRefused(String pointer, String name, String fields)
        throws Exception {
        MockMvcResponse response = post(PRE_PAYMENT, withUnknown(FULL_OUTBOUND, pointer));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertEquals(List.of(expected(pointer, name, fields)), violations(response), response.asString());
    }

    /**
     * <b>X9-RAIL-066</b> — the controls: each full body, unbroken, is accepted (a create 201, a PATCH
     * 200, an outbound notification 502 because nobody answers at its endpoint).
     *
     * <p><b>Source:</b> Mechanism. Without these, a service that refused everything would pass every
     * case of X9-RAIL-058, -059 and -065.
     *
     * <p><b>Why:</b> The one difference between a control and a case is the unknown field.
     */
    @Test
    void theFullBodiesUnbrokenAreAccepted() {
        String id = create();

        MockMvcResponse patched = given().contentType("application/json").body(FULL_PATCH)
            .when().patch(CREATE + "/" + id);
        assertEquals(HttpStatus.OK.value(), patched.statusCode(), patched.asString());

        MockMvcResponse sent = post(PRE_PAYMENT, FULL_OUTBOUND);
        assertEquals(HttpStatus.BAD_GATEWAY.value(), sent.statusCode(), sent.asString());
    }

    /**
     * <b>X9-RAIL-067</b> — unknown fields at several levels are all named in one answer, beside the
     * body's other violations.
     *
     * <p><b>Source:</b> Mechanism. The refusal is a list (#162): an unknown field is recorded while
     * the body is read and added to the same validation result, rather than stopping the read.
     *
     * <p><b>Why:</b> A biller who fixes one mistake and then meets the next, one round trip at a time,
     * stops trusting the answer. Each is reported once, in document order.
     */
    @Test
    void unknownFieldsAtSeveralLevelsAreNamedTogetherBesideOtherViolations() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(FULL_CREATE);
        body.put("reference", "x");
        ((ObjectNode) body.at("/creditor/address")).put("county", "LA");
        ((ObjectNode) body.at("/paymentMethods/0")).put("network", "fednow");
        ((ObjectNode) body.at("/creditor")).put("MCC", "49");

        MockMvcResponse response = post(CREATE, objectMapper.writeValueAsString(body));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        List<String> violations = violations(response);
        assertTrue(violations.contains(
            "creditor.address.county: is not a field of address, whose fields are " + ADDRESS), violations.toString());
        assertTrue(violations.contains("paymentMethods[0].network: is not a field of a payment method, whose fields are "
            + METHOD), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.startsWith("reference: is not a field of the payment request")),
            violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.startsWith("creditor.MCC: ")), violations.toString());
        assertEquals(3, violations.stream().filter(v -> v.contains(": is not a field of ")).count(), violations.toString());
    }

}
