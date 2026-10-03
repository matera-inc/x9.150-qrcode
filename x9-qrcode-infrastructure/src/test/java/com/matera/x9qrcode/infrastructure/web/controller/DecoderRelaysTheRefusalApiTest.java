/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import io.restassured.module.mockmvc.response.MockMvcResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a payer is told when the payload cannot be released.
 *
 * <p><b>Nothing is stubbed here.</b> The decoder really does call {@code /pub/api/v1/loc/\{id\}}
 * over HTTP, which is the point: the defect these tests pin lived in the hop, not at either end.
 * The refusal was formed correctly, travelled one layer, and arrived as
 * {@code "Error retrieving payload URI: http://localhost:8080/..."} — every reason reduced to the
 * same string, with an internal address attached.
 *
 * <p>Why it matters more than a tidier message. A payer scanning a code gets one of these, and they
 * are not variations on a theme:
 *
 * <ul>
 *   <li><b>already paid</b> — stop; somebody has settled this bill;</li>
 *   <li><b>cancelled</b> — stop; the biller withdrew it, go back and ask;</li>
 *   <li><b>expired</b> — ask for a fresh code;</li>
 *   <li><b>content does not match</b> — stop, and <em>do not pay</em>;</li>
 *   <li><b>unreachable</b> — wait and try again.</li>
 * </ul>
 *
 * <p>Collapsed into "a system failed", four of the five invite a retry, and the one where retrying
 * is dangerous is indistinguishable from the three where it is harmless.
 */
class DecoderRelaysTheRefusalApiTest extends AbstractIntegrationTest {

    private static final String DECODE = "/api/v1/qrcode-emv-decoder";
    private static final String CREATE = "/api/v1/payment-request";

    private record Issued(String id, String emv) { }

    private Issued createQRCode() {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Decoder Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "decode", "amountDue": { "amount": 1250, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1250,
                  "networks": { "ach": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
              ]
            }
            """;

        MockMvcResponse created = given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().response();

        return new Issued(created.jsonPath().getString("id"), created.jsonPath().getString("qrCode"));
    }

    private MockMvcResponse decode(String emv) {
        return given().contentType("application/json")
                .body("{\"qrCode\": \"%s\"}".formatted(emv))
                .when().post(DECODE)
                .then().extract().response();
    }

    private void setStatus(String id, String json) {
        given().contentType("application/json").body(json)
                .when().put(CREATE + "/" + id + "/status-update")
                .then().statusCode(HttpStatus.OK.value());
    }

    /**
     * CRC-16/CCITT-FALSE over the EMV up to and including the {@code 6304} tag, as EMVCo specifies.
     *
     * <p>Present so a tampered code can be made that still <em>parses</em>. Without it the EMV is
     * refused by the decoder before it ever reaches the binding check, and a test would pass while
     * proving something else entirely — which is exactly how this was first got wrong.
     */
    private static String withRecomputedCrc(String emvWithoutCrcValue) {
        int crc = 0xFFFF;

        for (byte b : emvWithoutCrcValue.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;

            for (int i = 0; i < 8; i++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }

        return emvWithoutCrcValue + "%04X".formatted(crc);
    }

    /** The same code, one letter of the merchant name changed, same length, CRC made valid again. */
    private static String tampered(String emv) {
        String upToCrc = emv.substring(0, emv.length() - 4);
        char[] characters = upToCrc.toCharArray();

        // The last lowercase letter before the CRC tag sits in the merchant city or name: altering
        // it keeps every length intact, so the TLV structure is untouched and only the content
        // differs from what was issued.
        for (int i = characters.length - 1; i >= 0; i--) {
            if (Character.isLowerCase(characters[i])) {
                characters[i] = characters[i] == 'a' ? 'b' : 'a';
                break;
            }
        }

        return withRecomputedCrc(new String(characters));
    }

    /**
     * <b>X9-DEC-001</b> — a decode of a paid bill says it was paid.
     *
     * <p><b>Source:</b> Ours. The reason has to survive the hop from {@code /pub/api/v1/loc} to the
     * decoder, and it did not.
     *
     * <p><b>Why:</b> This is the most ordinary refusal in the system — somebody got there first —
     * and it reached the payer as "a system we depend on failed". A payer told that will retry; a
     * payer told the bill is settled will stop.
     */
    @Test
    void aDecodeOfAPaidBillSaysItWasPaid() {
        Issued issued = createQRCode();
        setStatus(issued.id(), "{\"status\":\"PAID\",\"endToEndId\":\"E2E-DEC-1\",\"network\":\"ach\"}");

        MockMvcResponse response = decode(issued.emv());
        String body = response.asString();

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), body);
        assertTrue(body.contains("already been paid"), "the payer must be told it is settled: " + body);
        assertTrue(body.contains("payload-refused"),
                "and told it is a refusal rather than an outage: " + body);
    }

    /**
     * <b>X9-DEC-002</b> — a decode of a cancelled bill says the biller withdrew it.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> Paid and cancelled used to share one message — "is already cancelled or paid" —
     * so even once the reason survived the hop, a consumer still could not route on it. They call
     * for different things: settled means stop, withdrawn means go back to the biller and ask.
     */
    @Test
    void aDecodeOfACancelledBillSaysTheBillerWithdrewIt() {
        Issued issued = createQRCode();
        setStatus(issued.id(), "{\"status\":\"CANCELLED\"}");

        MockMvcResponse response = decode(issued.emv());
        String body = response.asString();

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), body);
        assertTrue(body.contains("cancelled by the biller"), "cancelled must not read as paid: " + body);
        assertFalse(body.contains("already been paid"), "and must not read as both: " + body);
    }

    /**
     * <b>X9-DEC-003</b> — a decode of a tampered code says the content does not match.
     *
     * <p><b>Source:</b> Ours. The binding check on {@code /pub/api/v1/loc/\{id\}}.
     *
     * <p><b>Why:</b> The one case in this list where retrying is the wrong move. Flattened, it was
     * indistinguishable from an outage — so the single refusal that means "do not pay" was the one
     * most likely to be retried.
     */
    @Test
    void aDecodeOfATamperedCodeSaysTheContentDoesNotMatch() {
        Issued issued = createQRCode();

        MockMvcResponse response = decode(tampered(issued.emv()));
        String body = response.asString();

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), body);
        assertTrue(body.contains("not the content issued"),
                "a tampered code must say so, not report an outage: " + body);
    }

    /**
     * <b>X9-DEC-004</b> — no refusal carries this deployment's internal address.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> The old message embedded the resolved payload URI, which on a single-host
     * deployment reads {@code http://localhost:8080/...}. An internal address, returned to an
     * external caller, on a public-facing decode. It belongs in the log.
     */
    @Test
    void noRefusalCarriesTheInternalAddress() {
        Issued issued = createQRCode();
        setStatus(issued.id(), "{\"status\":\"CANCELLED\"}");

        String body = decode(issued.emv()).asString();

        assertFalse(body.contains("localhost"), "an internal address must not reach the caller: " + body);
        assertFalse(body.contains("Error retrieving payload URI"),
                "the flattened message must be gone: " + body);
    }

    /**
     * <b>X9-DEC-005</b> — a payee that cannot be reached is a 502, not a refusal.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> The other half of the same distinction. "They said no" and "we never got an
     * answer" are opposite instructions, and this is the only one of the five where "try again
     * later" is the right advice. The host is swapped for one that cannot resolve, same length so
     * every TLV stays intact, CRC recomputed so the EMV still parses.
     *
     * <p>It also pins the phishing case, which is the same mechanism read the other way round. The
     * host in this message came out of the scanned code, so whoever printed the code chose it. The
     * old message echoed it back inside an error returned under our name — print a QR Code naming
     * {@code verify-your-account.example.com} and this service would render that address to the
     * payer. Nothing taken from the code may appear in the body.
     */
    @Test
    void aPayeeThatCannotBeReachedIsAGatewayError() {
        Issued issued = createQRCode();

        String upToCrc = issued.emv().substring(0, issued.emv().length() - 4);
        String unroutable = upToCrc.replace("x9-150.example.com", "x9-150.invalid.tld");

        org.junit.jupiter.api.Assumptions.assumeTrue(unroutable.length() == upToCrc.length(),
                "the substitution must not change any TLV length");

        MockMvcResponse response = decode(withRecomputedCrc(unroutable));
        String body = response.asString();

        assertEquals(HttpStatus.BAD_GATEWAY.value(), response.statusCode(),
                "an outage must not be reported as the caller's fault: " + body);
        assertTrue(body.contains("payload-unreachable"), body);
        assertFalse(body.contains("localhost"), "and must not leak an address either: " + body);
        assertFalse(body.contains("invalid.tld"),
                "a host chosen by whoever printed the code must not be rendered back: " + body);
    }

}
