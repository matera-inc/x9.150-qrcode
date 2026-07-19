/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.dto.enumerated.ActionEnumDTO;
import com.matera.x9qrcode.domain.dto.CertificateEndpointTypeEnum;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;
import com.matera.x9qrcode.infrastructure.generated.dto.EthereumDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataBlockchainDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadRequestDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestInformationDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.QRCodeEmvDecoderDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.QRCodeStatusDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.StatusUpdateResponseDTO;

import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import jakarta.validation.Valid;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_CORRELATION_ID;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_IAT;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_TTL;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QRCodesApisFlowTest extends AbstractIntegrationTest {

    private static final String APPLICATION_JOSE = "application/jose";
    private static final int DEFAULT_TTL_SECONDS = 300;
    private static final long DEFAULT_TTL_MILLIS = DEFAULT_TTL_SECONDS * 1000L;

    private static String qrcodeId;
    private static String qrcodeEmv;
    private static String qrcodeB64;
    private static String locationId;

    @Autowired
    private X9Properties x9Properties;

    /**
     * Encapsulates all test configuration for a payment rail, enabling
     * parameterized flow tests.
     */
    record PaymentRailTestConfig(
            String networkName,
            String createRequestJsonPath,
            String statusUpdateJsonPath,
            String notificationJsonPath,
            boolean isCrypto) {
        @Override
        public String toString() {
            return networkName;
        }
    }

    /**
     * Provides payment rail configurations for parameterized tests.
     * Each rail has its own JSON files for creation, status update, and
     * notification.
     */
    static Stream<PaymentRailTestConfig> paymentRailProvider() {
        return Stream.of(
                new PaymentRailTestConfig(
                        "FedNow",
                        "/payment-requests/request/postPaymentRequestCreationWithoutLocation.json",
                        "/payment-requests/request/putPaymentRequestStatusChange.json",
                        "/payment-notification/postPaymentNotificationCreation.json",
                        false),
                new PaymentRailTestConfig(
                        "Ethereum",
                        "/payment-requests/request/postPaymentRequestCreationEthereum.json",
                        "/payment-requests/request/putPaymentRequestStatusChangeEthereum.json",
                        "/payment-notification/postPaymentNotificationEthereumCreation.json",
                        true),
                new PaymentRailTestConfig(
                        "Bitcoin",
                        "/payment-requests/request/postPaymentRequestCreationBitcoin.json",
                        "/payment-requests/request/putPaymentRequestStatusChangeBitcoin.json",
                        "/payment-notification/postPaymentNotificationBitcoinCreation.json",
                        true));
    }

    // ===========================================================================================
    // Core flow: FedNow (original tests, preserved order)
    // ===========================================================================================

    @Test
    @Order(10)
    void testCreatePaymentRequest() {
        PaymentRequestResponseDTO response = createNewPaymentRequest(
                "/payment-requests/request/postPaymentRequestCreation.json");

        assertNotNull(response.getLocation().getEndpoint());
        assertNotNull(response.getQrCode());
    }

    @Test
    @Order(20)
    void testCreatePaymentRequestWithActiveExistingLocation() {
        String json = readJson("/payment-requests/request/postPaymentRequestCreation.json");

        given()
                .contentType("application/json")
                .body(json)
                .when()
                .post("/api/v1/payment-request")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .log().all();
    }

    @Test
    @Order(30)
    void testRetrievePaymentPayloadByLocation() throws Exception {
        String json = readJson("/payloads/paymentPayloadResponse.json");

        PaymentPayloadResponseDTO expectedResponse = objectMapper.readValue(json, PaymentPayloadResponseDTO.class);

        String jwsRequest = createPayloadRequestJws(qrcodeB64);

        String jwsResponse = given()
                .contentType(APPLICATION_JOSE)
                .body(jwsRequest)
                .when()
                .post("/pub/api/v1/loc/" + locationId)
                .then()
                .log().all()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .body().asString();

        PaymentPayloadResponseDTO response = extractPayloadFromJws(jwsResponse);

        assertEquals(qrcodeId, response.getId());
        assertNotNull(response.getBill());
        assertEquals(expectedResponse.getBill().getDescription(), response.getBill().getDescription());
        assertEquals(expectedResponse.getBill().getInvoice(), response.getBill().getInvoice());
        assertEquals(expectedResponse.getBill().getOrder(), response.getBill().getOrder());
        assertEquals(expectedResponse.getBill().getTip(), response.getBill().getTip());
        assertEquals(expectedResponse.getCreditor(), response.getCreditor());
        assertEquals(expectedResponse.getStatus(), response.getStatus());
        assertNotNull(response.getSentAt());
        assertNotNull(response.getValidUntil());
        assertNotNull(response.getPaymentMethods());
        assertEquals(expectedResponse.getRevision(), response.getRevision());
    }

    @Order(35)
    @ParameterizedTest
    @EnumSource(value = CertificateEndpointTypeEnum.class)
    void testDecodeQrCodeEmvWithPemEndpoint(CertificateEndpointTypeEnum endpointTypeEnum) throws IOException {
        x9Properties.getCertificate().setEndpointType(endpointTypeEnum);

        QRCodeEmvDecoderDTO request = new QRCodeEmvDecoderDTO();

        request.setQrCode(qrcodeEmv);
        request.setDateForPayment(LocalDate.now().plusDays(30));
        request.setCorrelationID(UUID.randomUUID());

        String responseJson = readJson("/payloads/paymentPayloadResponse.json");

        PaymentPayloadResponseDTO expectedResponse = objectMapper.readValue(responseJson,
                PaymentPayloadResponseDTO.class);

        PaymentPayloadResponseDTO response = given()
                .contentType("application/json")
                .body(request)
                .when()
                .post("/api/v1/qrcode-emv-decoder")
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all()
                .extract()
                .body().as(PaymentPayloadResponseDTO.class);

        assertEquals(qrcodeId, response.getId());
        assertNotNull(response.getBill());
        assertEquals(expectedResponse.getBill().getDescription(), response.getBill().getDescription());
        assertEquals(expectedResponse.getBill().getInvoice(), response.getBill().getInvoice());
        assertEquals(expectedResponse.getBill().getOrder(), response.getBill().getOrder());
        assertEquals(expectedResponse.getCreditor(), response.getCreditor());
        assertEquals(expectedResponse.getStatus(), response.getStatus());
        assertEquals(expectedResponse.getValidUntil(), response.getValidUntil());
        assertEquals(expectedResponse.getRevision(), response.getRevision());
        assertNotNull(response.getPaymentMethods());
    }

    @Test
    @Order(40)
    void testPatchPaymentRequest() {
        String json = readJson("/payment-requests/request/patchPaymentRequestData.json");

        PaymentRequestResponseDTO response = given()
                .contentType("application/json")
                .body(json)
                .when()
                .patch("/api/v1/payment-request/" + qrcodeId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all()
                .extract()
                .body()
                .as(PaymentRequestResponseDTO.class);

        assertEquals(qrcodeId, response.getId());
        assertNotNull(response.getLocation().getEndpoint());
        assertNotNull(response.getQrCode());
    }

    @Test
    @Order(45)
    void testGetPaymentRequestAfterPatch() throws IOException {
        String json = readJson("/payment-requests/response/getPaymentRequestAfterPatch.json");

        PaymentRequestInformationDTO expectedResponse = objectMapper.readValue(json,
                PaymentRequestInformationDTO.class);

        PaymentRequestInformationDTO response = given()
                .when()
                .get("/api/v1/payment-request/" + qrcodeId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all()
                .extract()
                .body().as(PaymentRequestInformationDTO.class);

        assertEquals(qrcodeId, response.getId());
        assertEquals(expectedResponse.getBill(), response.getBill());
        assertEquals(expectedResponse.getCreditor(), response.getCreditor());
        assertEquals(expectedResponse.getQrCode(), response.getQrCode());
        assertNotNull(response.getLocation().getId());
        assertNotNull(response.getLocation().getEndpoint());
        Assertions.assertNull(response.getPaymentDetails());
        assertEquals(expectedResponse.getRevision(), response.getRevision());
    }

    @Test
    @Order(50)
    void testPutPaymentRequestStatusUpdate() {
        String json = readJson("/payment-requests/request/putPaymentRequestStatusChange.json");

        StatusUpdateResponseDTO response = given()
                .contentType("application/json")
                .body(json)
                .when()
                .put("/api/v1/payment-request/" + qrcodeId + "/status-update")
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all()
                .extract()
                .body().as(StatusUpdateResponseDTO.class);

        assertEquals(QRCodeStatusDTO.PAID, response.getStatus());
    }

    @Test
    @Order(60)
    void testGetPaymentRequestAfterPutStatusUpdate() throws IOException {
        String json = readJson("/payment-requests/response/getPaymentRequestAfterPut.json");

        PaymentRequestInformationDTO expectedResponse = objectMapper.readValue(json,
                PaymentRequestInformationDTO.class);

        PaymentRequestInformationDTO response = given()
                .when()
                .get("/api/v1/payment-request/" + qrcodeId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all()
                .extract()
                .body().as(PaymentRequestInformationDTO.class);

        assertEquals(qrcodeId, response.getId());
        assertEquals(expectedResponse.getBill(), response.getBill());
        assertEquals(expectedResponse.getCreditor(), response.getCreditor());
        assertEquals(expectedResponse.getStatus(), response.getStatus());
        assertNotNull(response.getLocation().getEndpoint());
        assertEquals(expectedResponse.getLocation().getId(), response.getLocation().getId());
        assertNotNull(response.getPaymentDetails());
        assertNotNull(expectedResponse.getPaymentDetails());
        assertEquals(expectedResponse.getPaymentDetails().getNetwork(), response.getPaymentDetails().getNetwork());
        assertEquals(expectedResponse.getPaymentDetails().getEndToEndId(),
                response.getPaymentDetails().getEndToEndId());
        assertEquals(expectedResponse.getRevision(), response.getRevision());
    }

    @Test
    @Order(70)
    void testQRCodeLocationReuse() {
        String json = readJson("/payment-requests/request/postPaymentRequestCreation.json");

        PaymentRequestResponseDTO response = given()
                .contentType("application/json")
                .body(json)
                .when()
                .post("/api/v1/payment-request")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .log().all()
                .extract()
                .body().as(PaymentRequestResponseDTO.class);

        assertNotEquals(qrcodeId, response.getId());
        assertNotNull(response.getLocation().getEndpoint());

        qrcodeId = response.getId();
    }

    @Test
    @Order(80)
    void testPostPaymentNotification() throws Exception {
        String json = readJson("/payment-notification/postPaymentNotificationCreation.json")
                .replace("{{qrcodeId}}", qrcodeId);

        PaymentNotificationDataDTO expected = objectMapper.readValue(json, PaymentNotificationDataDTO.class);

        String signedPayload = signPayload(json);

        given()
                .contentType(APPLICATION_JOSE)
                .body(signedPayload)
                .when()
                .post("/pub/api/v1/payment-notification")
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all();

        PaymentNotificationDataDTO result = getPaymentRequest(qrcodeId).getPaymentNotification().getData();

        assertNotNull(result);
        assertEquals(expected.getPayment().getAmount(), result.getPayment().getAmount());
        assertEquals(expected.getPayment().getTipAmount(), result.getPayment().getTipAmount());
        assertEquals(expected.getPayment().getCurrency(), result.getPayment().getCurrency());
        assertEquals(expected.getPayment().getNetwork(), result.getPayment().getNetwork());
        assertEquals(expected.getPayment().getTransactionId(), result.getPayment().getTransactionId());
        assertEquals(expected.getExpectedDate(), result.getExpectedDate());
    }

    @ParameterizedTest
    @EnumSource(value = ActionEnumDTO.class, names = { "SENT", "NOT_SENT" })
    @Order(81)
    void testPostPaymentNotificationBlockchain(ActionEnumDTO action) throws Exception {
        createNewPaymentRequest("/payment-requests/request/postPaymentRequestCreationWithoutLocation.json");

        String jsonInitiated = readJson("/payment-notification/postPaymentNotificationBlockchainCreation.json")
                .replace("{{qrcodeId}}", qrcodeId)
                .replace("{{action}}", ActionEnumDTO.PAYMENT_INITIATED.value());

        String jsonPost = readJson("/payment-notification/postPaymentNotificationBlockchainCreation.json")
                .replace("{{qrcodeId}}", qrcodeId)
                .replace("{{action}}", action.value());

        PaymentNotificationDataBlockchainDTO expected = objectMapper
                .readValue(jsonPost, PaymentNotificationDataDTO.class).getBlockchain();

        // The first notification must be PAYMENT_INITIATED
        createPaymentNotification(jsonInitiated);

        // The second notification must be SENT or NOT_SENT
        createPaymentNotification(jsonPost);

        PaymentNotificationDataBlockchainDTO result = getPaymentRequest(qrcodeId).getPaymentNotification().getData()
                .getBlockchain();

        assertNotNull(expected);
        assertNotNull(result);
        assertEquals(expected.getAction(), result.getAction());
        assertEquals(expected.getFrom(), result.getFrom());
        assertEquals(expected.getTo(), result.getTo());
    }

    // ===========================================================================================
    // Parameterized full-flow tests for each payment rail (FedNow, Ethereum,
    // Bitcoin)
    // ===========================================================================================

    @ParameterizedTest(name = "Full flow: {0}")
    @MethodSource("paymentRailProvider")
    @Order(90)
    void testFullFlowCreateAndStatusUpdateByRail(PaymentRailTestConfig config) {
        // Step 1: Create QR Code with the rail-specific JSON
        PaymentRequestResponseDTO createResponse = createNewPaymentRequest(config.createRequestJsonPath());

        assertNotNull(createResponse.getLocation().getEndpoint());
        assertNotNull(createResponse.getQrCode());
        assertNotNull(createResponse.getId());

        String railQrcodeId = createResponse.getId();

        // Step 2: Retrieve (GET) the payment request
        PaymentRequestInformationDTO getResponse = getPaymentRequest(railQrcodeId);

        assertEquals(railQrcodeId, getResponse.getId());
        assertEquals(QRCodeStatusDTO.ACTIVE, getResponse.getStatus());
        assertNotNull(getResponse.getBill());
        assertNotNull(getResponse.getPaymentMethods());

        // Step 3: Status update (PAID)
        String statusJson = readJson(config.statusUpdateJsonPath());

        StatusUpdateResponseDTO statusResponse = given()
                .contentType("application/json")
                .body(statusJson)
                .when()
                .put("/api/v1/payment-request/" + railQrcodeId + "/status-update")
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all()
                .extract()
                .body().as(StatusUpdateResponseDTO.class);

        assertEquals(QRCodeStatusDTO.PAID, statusResponse.getStatus());

        // Step 4: Verify payment details after status update
        PaymentRequestInformationDTO afterStatusUpdate = getPaymentRequest(railQrcodeId);

        assertEquals(QRCodeStatusDTO.PAID, afterStatusUpdate.getStatus());
        assertNotNull(afterStatusUpdate.getPaymentDetails());
        assertEquals(config.networkName(), afterStatusUpdate.getPaymentDetails().getNetwork().getValue());
    }

    @ParameterizedTest(name = "Signature payload flow: {0}")
    @MethodSource("paymentRailProvider")
    @Order(92)
    @SneakyThrows
    void testSignatureGenerateAndRetrievePayloadByRail(PaymentRailTestConfig config) {
        // Step 1: Create a new QR code for this rail
        PaymentRequestResponseDTO createResponse = createNewPaymentRequest(config.createRequestJsonPath());

        assertNotNull(createResponse.getQrCode(), "QR code EMV should be present");
        assertNotNull(createResponse.getLocation().getId(), "Location ID should be present");

        String railQrcodeId = createResponse.getId();
        String railQrcodeB64 = createResponse.getQrCodeB64();
        String railLocationId = createResponse.getLocation().getId();

        // Step 2: Generate a JWS-signed payload request using the Base64-encoded QR
        // code content
        String jwsRequest = createPayloadRequestJws(railQrcodeB64);
        assertNotNull(jwsRequest, "JWS signed request should not be null");

        // Step 3: Retrieve the payload via the public location endpoint
        String jwsResponse = given()
                .contentType(APPLICATION_JOSE)
                .body(jwsRequest)
                .when()
                .post("/pub/api/v1/loc/" + railLocationId)
                .then()
                .log().all()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .body().asString();

        assertNotNull(jwsResponse, "JWS response should not be null");

        // Step 4: Extract the payload from the signed JWS response
        PaymentPayloadResponseDTO payloadResponse = extractPayloadFromJws(jwsResponse);

        // Step 5: Validate the payload content
        assertEquals(railQrcodeId, payloadResponse.getId(),
                "Payload ID should match the created QR code ID");
        assertEquals(QRCodeStatusDTO.ACTIVE, payloadResponse.getStatus(),
                "Payload status should be ACTIVE");
        assertNotNull(payloadResponse.getBill(),
                "Payload bill should be present");
        assertNotNull(payloadResponse.getCreditor(),
                "Payload creditor should be present");
        assertNotNull(payloadResponse.getPaymentMethods(),
                "Payload payment methods should be present");
        assertNotNull(payloadResponse.getSentAt(),
                "Payload sentAt should be present");
        assertNotNull(payloadResponse.getValidUntil(),
                "Payload validUntil should be present");

        // Step 6: Validate the JWS signature via the validate endpoint
        given()
                .contentType("application/json")
                .body("{\"content\":\"" + jwsResponse + "\"}")
                .when()
                .post("/api/v1/signature/validate")
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all();
    }

    @ParameterizedTest(name = "Notification flow: {0}")
    @MethodSource("paymentRailProvider")
    @Order(92)
    void testPaymentNotificationByRail(PaymentRailTestConfig config) throws Exception {
        // Create a new QR code for this rail
        createNewPaymentRequest(config.createRequestJsonPath());

        String notificationJson = readJson(config.notificationJsonPath())
                .replace("{{qrcodeId}}", qrcodeId);

        if (config.isCrypto()) {
            // For crypto rails, replace the action placeholder
            notificationJson = notificationJson.replace("{{action}}", ActionEnumDTO.PAYMENT_INITIATED.value());
        }

        PaymentNotificationDataDTO expected = objectMapper.readValue(notificationJson,
                PaymentNotificationDataDTO.class);

        // Send notification
        createPaymentNotification(notificationJson);

        // Verify notification was stored
        PaymentNotificationDataDTO result = getPaymentRequest(qrcodeId).getPaymentNotification().getData();

        assertNotNull(result);
        assertEquals(expected.getPayment().getAmount(), result.getPayment().getAmount());
        assertEquals(expected.getPayment().getCurrency(), result.getPayment().getCurrency());
        assertEquals(expected.getPayment().getNetwork(), result.getPayment().getNetwork());
        assertEquals(expected.getPayment().getTransactionId(), result.getPayment().getTransactionId());
        assertEquals(expected.getExpectedDate(), result.getExpectedDate());

        // For crypto rails, verify blockchain info is present
        if (config.isCrypto()) {
            PaymentNotificationDataBlockchainDTO blockchain = result.getBlockchain();
            assertNotNull(blockchain, "Blockchain info should be present for crypto rail: " + config.networkName());
            assertNotNull(blockchain.getAction());
            assertNotNull(blockchain.getFrom());
            assertNotNull(blockchain.getTo());
        }
    }

    // ===========================================================================================
    // Ethereum additional properties tests — unknown networks and fields must be persisted and
    // returned as well
    // ===========================================================================================

    @Test
    @Order(93)
    @SneakyThrows
    void testEthereumAdditionalPropertiesOnCreate() {
        // Create Ethereum QR code with additional "Tron" network
        PaymentRequestResponseDTO createResponse = createNewPaymentRequest(
                "/payment-requests/request/postPaymentRequestCreationEthereum.json");

        String ethQrcodeId = createResponse.getId();
        assertNotNull(ethQrcodeId);

        // GET and verify "Tron" additional property is present in the networks
        PaymentRequestInformationDTO getResponse = getPaymentRequest(ethQrcodeId);
        assertNotNull(getResponse.getPaymentMethods());

        EthereumDTO ethereumDTO = getResponse.getPaymentMethods().get(0)
                                             .getNetworks().getEthereum();

        assertNotNull(ethereumDTO, "Ethereum should be present on GET");

        Map<String, Object> additionalProps = getResponse.getPaymentMethods().get(0)
                .getNetworks().getAdditionalProperties();

        assertNotNull(additionalProps, "Additional properties should be present on GET");
        assertTrue(additionalProps.containsKey("Tron"), "Tron should be present as additional property");

        @SuppressWarnings("unchecked")
        Map<String, Object> tronMap = (Map<String, Object>) additionalProps.get("Tron");
        assertEquals("TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7", tronMap.get("address"),
                "Tron address should match the one sent in the creation request");
    }

    @Test
    @Order(94)
    @SneakyThrows
    void testEthereumAdditionalPropertiesOnPayload() {
        // Use the QR code created in Order 93
        String jwsRequest = createPayloadRequestJws(qrcodeB64);

        String jwsResponse = given()
                .contentType(APPLICATION_JOSE)
                .body(jwsRequest)
                .when()
                .post("/pub/api/v1/loc/" + locationId)
                .then()
                .log().all()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .body().asString();

        PaymentPayloadResponseDTO payloadResponse = extractPayloadFromJws(jwsResponse);

        Map<String, Object> payloadAdditionalProps = payloadResponse.getPaymentMethods().get(0)
                .getNetworks().getAdditionalProperties();

        assertNotNull(payloadAdditionalProps, "Additional properties should be present in payload");
        assertTrue(payloadAdditionalProps.containsKey("Tron"),
                "Tron should be present in payload additional properties");

        @SuppressWarnings("unchecked")
        Map<String, Object> tronMap = (Map<String, Object>) payloadAdditionalProps.get("Tron");
        assertEquals("TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7", tronMap.get("address"));

        EthereumDTO payloadEthereum = payloadResponse.getPaymentMethods().get(0)
                .getNetworks().getEthereum();
        assertNotNull(payloadEthereum, "Ethereum should be present in payload");
    }

    @Test
    @Order(95)
    @SneakyThrows
    void testEthereumAdditionalPropertiesOnDecoding() {
        // Decode the EMV QR code and verify additional properties
        QRCodeEmvDecoderDTO request = new QRCodeEmvDecoderDTO();
        request.setQrCode(qrcodeEmv);
        request.setDateForPayment(LocalDate.now().plusDays(30));
        request.setCorrelationID(UUID.randomUUID());

        PaymentPayloadResponseDTO response = given()
                .contentType("application/json")
                .body(request)
                .when()
                .post("/api/v1/qrcode-emv-decoder")
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all()
                .extract()
                .body().as(PaymentPayloadResponseDTO.class);

        Map<String, Object> decodingAdditionalProps = response.getPaymentMethods().get(0)
                .getNetworks().getAdditionalProperties();

        assertNotNull(decodingAdditionalProps, "Additional properties should be present in decoded payload");
        assertTrue(decodingAdditionalProps.containsKey("Tron"),
                "Tron should be present in decoded additional properties");

        @SuppressWarnings("unchecked")
        Map<String, Object> tronMap = (Map<String, Object>) decodingAdditionalProps.get("Tron");
        assertEquals("TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7", tronMap.get("address"));

        EthereumDTO decodingEthereum = response.getPaymentMethods().get(0)
                .getNetworks().getEthereum();
        assertNotNull(decodingEthereum, "Ethereum should be present in decoded payload");
    }

    @Test
    @Order(96)
    @SneakyThrows
    void testEthereumAdditionalPropertiesOnPatch() {
        // Create a fresh Ethereum QR code for the PATCH test
        PaymentRequestResponseDTO freshCreate = createNewPaymentRequest(
                "/payment-requests/request/postPaymentRequestCreationEthereum.json");

        String patchTargetId = freshCreate.getId();

        // Patch with an additional "Avalanche" network, keeping "Tron"
        String patchJson = readJson("/payment-requests/request/patchPaymentRequestDataEthereum.json");

        PaymentRequestResponseDTO patchResponse = given()
                .contentType("application/json")
                .body(patchJson)
                .when()
                .patch("/api/v1/payment-request/" + patchTargetId)
                .then()
                .log().all()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .body().as(PaymentRequestResponseDTO.class);

        assertNotNull(patchResponse.getId());

        // GET and verify both "Tron" and "Avalanche" are present
        PaymentRequestInformationDTO getAfterPatch = getPaymentRequest(patchTargetId);

        Map<String, Object> patchAdditionalProps = getAfterPatch.getPaymentMethods().get(0)
                .getNetworks().getAdditionalProperties();

        assertNotNull(patchAdditionalProps, "Additional properties should be present after PATCH");
        assertTrue(patchAdditionalProps.containsKey("Tron"),
                "Tron should be present after PATCH");
        assertTrue(patchAdditionalProps.containsKey("Avalanche"),
                "Avalanche should be present after PATCH");

        @SuppressWarnings("unchecked")
        Map<String, Object> tronMap = (Map<String, Object>) patchAdditionalProps.get("Tron");
        assertEquals("TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7", tronMap.get("address"));

        @SuppressWarnings("unchecked")
        Map<String, Object> avalancheMap = (Map<String, Object>) patchAdditionalProps.get("Avalanche");
        assertEquals("0xABCd35Cc6634C0539Ff82c466ae367A6097dEFFF", avalancheMap.get("address"));

        EthereumDTO patchEthereum = getAfterPatch.getPaymentMethods().get(0)
                .getNetworks().getEthereum();
        assertNotNull(patchEthereum, "Ethereum should be present after PATCH");
    }

    // ===========================================================================================
    // Tip-specific tests (rail-independent)
    // ===========================================================================================

    @Test
    @Order(105)
    void testRejectCreatePaymentRequestWithInvalidTip() {
        ProblemDetail response = createNewPaymentRequestWithError(
                "/payment-requests/request/postPaymentRequestCreationInvalidTip.json");

        List<String> errors = (List<String>) response.getProperties().getOrDefault("violations", List.of());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("cannot provided range or presets when tip is not allowed"));
    }

    @Test
    @Order(110)
    void testCreatePaymentRequestWithoutTip() {
        PaymentRequestResponseDTO response = createNewPaymentRequest(
                "/payment-requests/request/postPaymentRequestCreationWithoutTip.json");

        assertNotNull(response.getLocation().getEndpoint());
        assertNotNull(response.getQrCode());
    }

    @Test
    @Order(120)
    void testGetPaymentRequestWithoutTip() {
        PaymentRequestInformationDTO response = given()
                .when()
                .get("/api/v1/payment-request/" + qrcodeId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all()
                .extract()
                .body().as(PaymentRequestInformationDTO.class);

        assertEquals(qrcodeId, response.getId());
        assertEquals(false, response.getBill().getTip().getAllowed());
        Assertions.assertNull(response.getBill().getTip().getRange());
        assertTrue(response.getBill().getTip().getPresets().isEmpty());
    }

    // ===========================================================================================
    // Helper methods
    // ===========================================================================================

    private PaymentRequestResponseDTO createNewPaymentRequest(String jsonPath) {
        PaymentRequestResponseDTO response = given()
                .contentType("application/json")
                .body(readJson(jsonPath))
                .when()
                .post("/api/v1/payment-request")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .log().all()
                .extract()
                .body().as(PaymentRequestResponseDTO.class);

        qrcodeId = response.getId();
        qrcodeEmv = response.getQrCode();
        qrcodeB64 = response.getQrCodeB64();
        locationId = response.getLocation().getId();

        return response;
    }

    private ProblemDetail createNewPaymentRequestWithError(String jsonPath) {
        return given()
                .contentType("application/json")
                .body(readJson(jsonPath))
                .when()
                .post("/api/v1/payment-request")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .log().all()
                .extract()
                .body().as(ProblemDetail.class);
    }

    private void createPaymentNotification(String json) {
        String signedPayload = signPayload(json);

        given()
                .contentType(APPLICATION_JOSE)
                .body(signedPayload)
                .when()
                .post("/pub/api/v1/payment-notification")
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all();
    }

    private PaymentRequestInformationDTO getPaymentRequest(String qrcodeId) {
        return given()
                .when()
                .get("/api/v1/payment-request/" + qrcodeId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .log().all()
                .extract()
                .body().as(PaymentRequestInformationDTO.class);
    }

    @SneakyThrows
    private String readJson(String resourcePath) {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        }
    }

    private String createPayloadRequestJws(String qrCodeB64) {
        PaymentPayloadRequestDTO requestDTO = new PaymentPayloadRequestDTO().qrCodeContent(qrCodeB64);
        return signPayload(requestDTO);
    }

    @SneakyThrows
    private PaymentPayloadResponseDTO extractPayloadFromJws(String jwsToken) {
        JWSObject jwsObject = JWSObject.parse(jwsToken);
        String payload = jwsObject.getPayload().toString();
        return objectMapper.readValue(payload, PaymentPayloadResponseDTO.class);
    }

    @SneakyThrows
    private String signPayload(Object payload) {
        String correlationId = UUID.randomUUID().toString();

        String jwsToken = given()
                .contentType("application/json")
                .header("Correlation-Id", correlationId)
                .header("TTL-Seconds", DEFAULT_TTL_SECONDS)
                .body(payload instanceof String ? payload : objectMapper.writeValueAsString(payload))
                .when()
                .post("/api/v1/signature/generate")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .body().asString();

        JWSObject jwsObject = JWSObject.parse(jwsToken);
        JWSHeader header = jwsObject.getHeader();

        String headerCorrelationId = header.getCustomParam(JWS_HEADER_CORRELATION_ID).toString();
        assertEquals(correlationId, headerCorrelationId,
                "JWS correlationId should match the one sent in the request");

        Long headerTtl = (Long) header.getCustomParam(JWS_HEADER_TTL);
        assertEquals(DEFAULT_TTL_MILLIS, headerTtl,
                "JWS ttl should match the TTL sent in the request (converted to milliseconds)");

        Long headerIat = (Long) header.getCustomParam(JWS_HEADER_IAT);
        assertNotNull(headerIat, "JWS iat (issued at) should be present");

        long expirationTime = headerIat + headerTtl;
        long currentTime = Instant.now().toEpochMilli();
        assertTrue(expirationTime > currentTime,
                "JWS should not be expired (iat + ttl should be greater than current time)");

        return jwsToken;
    }

}
