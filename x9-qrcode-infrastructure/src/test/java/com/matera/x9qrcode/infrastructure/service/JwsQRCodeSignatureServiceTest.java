/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.SignatureOutputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.app.service.PEMService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationInput;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;
import com.matera.x9qrcode.infrastructure.generated.api.PaymentRequestApi;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestInputDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestResponseDTO;
import com.matera.x9qrcode.infrastructure.testing.fixture.DTOFixtures;

import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_CORRELATION_ID;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_IAT;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_STATUS_CODE;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_TTL;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_PAYLOAD_QR_CODE_CONTENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.util.Objects.nonNull;

class JwsQRCodeSignatureServiceTest extends AbstractIntegrationTest {

    private static final long TTL = 300000L;

    @Autowired
    private QRCodeSignatureService signatureService;

    @Autowired
    private PaymentRequestApi paymentRequestApi;

    @Autowired
    private PEMService pemService;

    private DTOFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = DTOFixtures.create();
    }

    @Test
    void shouldSignDataWithAllX9Headers() throws Exception {
        PaymentRequestInputDTO payload = fixtures.paymentRequest().paymentRequestInput();
        UUID correlationId = UUID.randomUUID();

        SignatureInputDataDTO input = new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, payload, correlationId, TTL, null);
        SignatureOutputDataDTO result = signatureService.signData(input);

        assertNotNull(result);
        assertNotNull(result.jwsToken());

        String[] parts = result.jwsToken().split("\\.");
        assertEquals(3, parts.length, "JWS should have 3 parts");

        JWSObject jwsObject = JWSObject.parse(result.jwsToken());
        JWSHeader header = jwsObject.getHeader();

        assertNotNull(header.getAlgorithm(), "alg header should be present");
        assertNotNull(header.getKeyID(), "kid header should be present");
        assertNotNull(header.getType(), "typ header should be present");
        assertTrue(nonNull(header.getX509CertURL()) || nonNull(header.getJWKURL()), "x5u or jku header should be present");
        assertNotNull(header.getX509CertSHA256Thumbprint(), "x5t#S256 header should be present");

        assertEquals(correlationId.toString(), header.getCustomParam(JWS_HEADER_CORRELATION_ID));
        assertNotNull(header.getCustomParam(JWS_HEADER_IAT), "iat header should be present");
        assertEquals(TTL, header.getCustomParam(JWS_HEADER_TTL));

        Set<String> criticalHeaders = header.getCriticalParams();
        assertNotNull(criticalHeaders);
        assertTrue(criticalHeaders.contains(JWS_HEADER_CORRELATION_ID));
        assertTrue(criticalHeaders.contains(JWS_HEADER_IAT));
        assertTrue(criticalHeaders.contains(JWS_HEADER_TTL));

        String payloadString = jwsObject.getPayload().toString();
        assertTrue(payloadString.contains(payload.getCreditor().getName()));
        assertTrue(payloadString.contains(payload.getBill().getAmountDue().getAmount().toString()));
        assertTrue(payloadString.contains("USD"));
    }

    @Test
    void shouldValidateSignature() {
        PaymentRequestInputDTO paymentRequestInputDTO = fixtures.paymentRequest().paymentRequestInput();
        String qrCode = Objects.requireNonNull(paymentRequestApi.createPaymentRequest(paymentRequestInputDTO).getBody()).getQrCode();
        String base64QrCode = Base64.encode(qrCode).toString();
        UUID correlationId = UUID.randomUUID();

        SignatureInputDataDTO signInput =
            new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, Map.of(JWS_PAYLOAD_QR_CODE_CONTENT, base64QrCode), correlationId, TTL, null);

        SignatureOutputDataDTO signResult = signatureService.signData(signInput);

        SignatureValidationInput validationInput =
            new SignatureValidationInput(SignatureTypeEnumDTO.X9, correlationId, signResult.jwsToken(),
                paymentRequestInputDTO.getLocationId());

        assertTrue(signatureService.validateSignature(validationInput).isValid());
    }

    @Test
    void shouldSignResponseDataWithStatusCode() throws Exception {
        PaymentRequestResponseDTO payload = fixtures.paymentRequest().paymentRequestResponse();
        UUID correlationId = UUID.randomUUID();
        int statusCode = 200;

        SignatureInputDataDTO input = new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, payload, correlationId, TTL, statusCode);

        SignatureOutputDataDTO result = signatureService.signData(input);

        assertNotNull(result);
        assertNotNull(result.jwsToken());

        JWSObject jwsObject = JWSObject.parse(result.jwsToken());
        JWSHeader header = jwsObject.getHeader();

        assertEquals((long) statusCode, header.getCustomParam(JWS_HEADER_STATUS_CODE));
        assertTrue(header.getCriticalParams().contains(JWS_HEADER_STATUS_CODE));
    }

    @Test
    void shouldSignDataThrowsExceptionWhenCorrelationIdMissing() {
        PaymentRequestInputDTO payload = fixtures.paymentRequest().paymentRequestInput();

        SignatureInputDataDTO input = new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, payload, null, 300000L, null);

        ServiceException exception = assertThrows(ServiceException.class, () -> signatureService.signData(input));

        assertEquals("correlationId is required for JWS signing", exception.getMessage());
    }

    @Test
    void shouldSignDataThrowsExceptionWhenInvalidTtl() {
        PaymentRequestInputDTO payload = fixtures.paymentRequest().paymentRequestInput();

        SignatureInputDataDTO input =
            new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, payload, UUID.randomUUID(), 0L, null);

        ServiceException exception = assertThrows(ServiceException.class, () -> signatureService.signData(input));

        assertEquals("ttl must be a positive value for JWS signing", exception.getMessage());
    }

    @Test
    void shouldFailValidateSignatureWhenLocationIdNotMatch() {
        String qrCode = Objects.requireNonNull(paymentRequestApi.createPaymentRequest(fixtures.paymentRequest().paymentRequestInput()).getBody()).getQrCode();
        String base64QrCode = Base64.encode(qrCode).toString();
        UUID correlationId = UUID.randomUUID();

        SignatureInputDataDTO signInput =
            new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, Map.of(JWS_PAYLOAD_QR_CODE_CONTENT, base64QrCode), correlationId, TTL, null);

        SignatureOutputDataDTO signResult = signatureService.signData(signInput);

        SignatureValidationInput validationInput =
            new SignatureValidationInput(SignatureTypeEnumDTO.X9, correlationId, signResult.jwsToken(),
                UUID.randomUUID().toString());

        assertFalse(signatureService.validateSignature(validationInput).isValid());
    }

    @Test
    void shouldRetrieveDigitalSignatureCertificate() {
        byte[] pemByteArray = signatureService.retrieveDigitalSignatureCertificate();

        List<X509Certificate> certificates = pemService.parse(pemByteArray);

        assertFalse(certificates.isEmpty(), "Should have at least one certificate");

        X509Certificate certificate = certificates.get(0);

        String subject = certificate.getSubjectX500Principal().getName();
        assertTrue(subject.contains("matera.com"), "Subject should contain matera.com");
        assertTrue(subject.contains("Matera Systems"), "Subject should contain Matera Systems organization");

        String issuer = certificate.getIssuerX500Principal().getName();
        // Accept either a real X9-issued certificate (issuer = X9 Financial PKI) or a self-signed
        // certificate (issuer == subject) so the shipped non-production demo keystore is valid here too.
        boolean realX9Issued = issuer.contains("X9 Financial PKI");
        boolean selfSigned = issuer.equals(subject);
        assertTrue(realX9Issued || selfSigned,
            "Issuer should be X9 Financial PKI (real cert) or equal to the subject (self-signed cert)");

        assertEquals("RSA", certificate.getPublicKey().getAlgorithm(), "Public key should be RSA");
        assertEquals("X.509", certificate.getType(), "Certificate type should be X.509");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRetrieveDigitalSignatureJwkSet() {
        Map<String, Object> jwkSet = signatureService.retrieveDigitalSignatureJwkSet();

        assertNotNull(jwkSet);
        assertTrue(jwkSet.containsKey("keys"), "JWK Set should contain 'keys' property");

        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwkSet.get("keys");
        assertFalse(keys.isEmpty(), "JWK Set should have at least one key");

        Map<String, Object> firstKey = keys.get(0);
        assertEquals("x9-key", firstKey.get("kid"), "Key ID should be 'x9-key'");
        assertEquals("PS512", firstKey.get("alg"), "Algorithm should be PS512");
        assertEquals("RSA", firstKey.get("kty"), "Key type should be RSA");
        assertEquals("sig", firstKey.get("use"), "Key use should be 'sig' (signature)");
    }

}
