/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deployment whose <em>own</em> X9-issued certificate is EC.
 *
 * <p>Verifying a payer's EC signature and signing with an EC key of our own are different problems,
 * and only the first was fixed by the previous change. This one is about identity: the key this
 * service signs payloads, QR content and responses with.
 *
 * <p>It could not work before. {@code JwkSetFacadeBean} parsed our certificate with
 * {@code RSAKey.parse} and built an {@code RSASSASigner} unconditionally, so a deployment issued an
 * EC certificate could not start, let alone sign. Since ANSI X9.150-2026 names no algorithm — it
 * defers to the X9-approved suite (SD-34) and its own Annex A examples use {@code ES256} — an EC
 * identity is an ordinary thing for X9 to issue, not an exotic one.
 *
 * <p>The fixture is the EC leaf from the committed test PKI, pressed into service as this
 * deployment's own identity. See {@code src/test/resources/certificate/README.md}.
 */
@TestPropertySource(properties = {
    "x9.certificate.jwk-algorithm=ES256",
    "x9.certificate.private-keystore.alias=x9-test-payer-ec",
    "x9.certificate.private-keystore.location=classpath:/certificate/x9-test-payer-ec.p12",
    "x9.certificate.private-keystore.password=x9test123",
    "x9.certificate.private-keystore.private-key-password=x9test123",
    "x9.certificate.truststore.location=classpath:/certificate/x9-test-truststore.jks",
    "x9.certificate.truststore.password=x9test123",
    "x9.certificate.truststore.alias=x9-test-ca"
})
class EcSigningIdentityApiTest extends AbstractIntegrationTest {

    private static final String SIGN = "/api/v1/signature/generate";

    /** Declared to the verifier so Nimbus does not silently refuse the JWS. */
    private static final Set<String> CRITICAL_HEADERS = Set.of("correlationId", "iat", "ttl");

    private String sign(String payload) {
        return given().contentType("application/json")
                .header("Correlation-Id", UUID.randomUUID().toString())
                .header("TTL-Seconds", "300")
                .body(payload)
                .when().post(SIGN)
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();
    }

    private static ECKey ownPublicKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = EcSigningIdentityApiTest.class
                .getResourceAsStream("/certificate/x9-test-payer-ec.p12")) {
            keyStore.load(in, "x9test123".toCharArray());
        }

        X509Certificate certificate = (X509Certificate) keyStore.getCertificate("x9-test-payer-ec");

        return ECKey.parse(certificate);
    }

    @Test
    void theServiceSignsWithItsEcKey() throws Exception {
        String jws = sign("{\"hello\":\"world\"}");

        JWSObject parsed = JWSObject.parse(jws);

        assertEquals(JWSAlgorithm.ES256, parsed.getHeader().getAlgorithm(),
                "an EC identity must advertise an EC algorithm");
    }

    /**
     * Not merely "it returned something shaped like a JWS": the signature must actually verify
     * against the public key of the certificate we claim to be.
     *
     * <p>The critical headers have to be declared to the verifier. X9.150 requires
     * {@code correlationId}, {@code iat} and {@code ttl} in {@code crit}, and Nimbus refuses to
     * verify a JWS carrying critical parameters a verifier was not told to expect — by returning
     * {@code false} rather than raising, which is an easy afternoon to lose.
     */
    @Test
    void thatSignatureVerifiesAgainstOurCertificate() throws Exception {
        JWSObject parsed = JWSObject.parse(sign("{\"hello\":\"world\"}"));

        ECDSAVerifier verifier = new ECDSAVerifier(ownPublicKey().toECPublicKey(), CRITICAL_HEADERS);

        assertTrue(parsed.verify(verifier),
                "the JWS must verify against the EC certificate this deployment publishes");
    }

    /** What a counterparty fetches to verify us: it has to describe an EC key, not an RSA one. */
    @Test
    void thePublishedJwkSetDescribesAnEcKey() {
        JsonPath jwks = given().when().get("/pub/.well-known/jwks")
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath();

        assertEquals("EC", jwks.getString("keys[0].kty"), jwks.prettify());
        assertEquals("ES256", jwks.getString("keys[0].alg"), jwks.prettify());
    }

}
