/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.payload;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.SignatureOutputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.service.QRCodeExternalPayloadService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.exception.PayloadRefusedException;
import com.matera.x9qrcode.app.exception.PayloadUnreachableException;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.decodeemv.DecodeEmvOutput;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadRequestDTO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
public class RestClientQRCodeExternalPayloadService implements QRCodeExternalPayloadService {

    private static final Encoder ENCODER = Base64.getEncoder();
    private static final String HEADER_DATE_FOR_PAYMENT = "dateForPayment";
    private static final String APPLICATION_JOSE = "application/jose";

    /** Relayed text is somebody else's. Capped so a hostile payee cannot use us as a megaphone. */
    private static final int MAX_RELAYED_VIOLATIONS = 10;
    private static final int MAX_RELAYED_LENGTH = 300;

    /** Any scheme-ish or bare-domain run. Deliberately broad: a false positive costs a few words. */
    private static final Pattern LINK = Pattern.compile(
        "(?i)\\b(?:[a-z][a-z0-9+.-]*://|www\\.|[a-z0-9-]+\\.(?:com|net|org|io|co|xyz|top|ru|cn)\\b)\\S*");

    private static final String REFUSED_WITHOUT_A_REASON =
        "The payee's service refused to release this payload, without giving a reason.";

    private final RestClient restClient;
    private final QRCodeLocationService qrCodeLocationService;
    private final QRCodeSignatureService qrCodeSignatureService;
    private final ObjectMapper objectMapper;

    @Override
    public DecodeEmvOutput retrievePayload(String emv,
            String payloadLocation,
            LocalDate dateForPayment,
            UUID correlationId) {
        log.info("Retrieving payload with dateForPayment {} from location {}", dateForPayment, payloadLocation);

        URI payloadUri = qrCodeLocationService.parseLocation(payloadLocation, true);

        Consumer<HttpHeaders> headersConsumer = headers -> {
            headers.setContentType(MediaType.parseMediaType(APPLICATION_JOSE));

            if (nonNull(dateForPayment)) {
                headers.add(HEADER_DATE_FOR_PAYMENT, dateForPayment.toString());
            }
        };

        try {
            String requestJws = createPayloadRequestJws(emv, correlationId);

            ResponseEntity<String> response = restClient.post()
                    .uri(payloadUri)
                    .headers(headersConsumer)
                    .body(requestJws)
                    .retrieve()
                    .toEntity(String.class);

            return new DecodeEmvOutput(
                    qrCodeLocationService.parseLocation(payloadLocation, false),
                    response.getStatusCode().value(),
                    response.getBody());
        } catch (RestClientResponseException e) {
            // The payee's service answered. Whatever it said is the most useful thing we have, and
            // it is the only thing that tells a payer whether to stop, to wait, or to ask for a
            // fresh code. Flattening it here is what made every refusal look like an outage.
            if (e.getStatusCode().is5xxServerError()) {
                throw unreachable(payloadUri, e);
            }

            List<String> violations = relayedViolations(e);
            log.info("Payload refused upstream with {} for {}: {}", e.getStatusCode(), payloadUri, violations);

            throw new PayloadRefusedException(e.getStatusCode().value(), violations, e);
        } catch (Exception e) {
            throw unreachable(payloadUri, e);
        }
    }

    private PayloadUnreachableException unreachable(URI payloadUri, Exception cause) {
        // The URI goes in the log and not in the exception: it has been resolved against this
        // deployment's internals and would otherwise be handed to an external caller.
        log.warn("Could not retrieve the payload from {}", payloadUri, cause);

        return new PayloadUnreachableException(
            "The payee's service could not be reached, so this QR Code could not be decoded.", cause);
    }

    /**
     * The upstream refusal, reduced to plain strings.
     *
     * <p>Treated as untrusted input throughout: the payload location comes out of the scanned QR
     * Code, so this body was produced by whichever host that code names. Only strings survive, only
     * the first few, each truncated. A body that is not a problem detail — an HTML error page, say —
     * is not echoed at all.
     */
    private List<String> relayedViolations(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();

        if (body.isBlank()) {
            return List.of(REFUSED_WITHOUT_A_REASON);
        }

        try {
            JsonNode problem = objectMapper.readTree(body);
            List<String> violations = new ArrayList<>();

            JsonNode reported = problem.path("violations");

            if (reported.isArray()) {
                reported.forEach(violation -> {
                    if (violation.isTextual() && violations.size() < MAX_RELAYED_VIOLATIONS) {
                        violations.add(sanitised(violation.asText()));
                    }
                });
            } else if (reported.isTextual()) {
                violations.add(sanitised(reported.asText()));
            }

            if (violations.isEmpty() && problem.path("detail").isTextual()) {
                violations.add(sanitised(problem.path("detail").asText()));
            }

            return violations.isEmpty() ? List.of(REFUSED_WITHOUT_A_REASON) : List.copyOf(violations);
        } catch (Exception parseFailure) {
            log.info("Upstream refusal was not a problem detail, so its body is not relayed", parseFailure);

            return List.of(REFUSED_WITHOUT_A_REASON);
        }
    }

    /**
     * One relayed reason, made safe to render.
     *
     * <p><b>Links are removed.</b> The host that wrote this text is whichever one the scanned code
     * names, so a crafted QR Code could otherwise have an address of its choosing printed inside an
     * error message that carries our name — a phishing surface built out of an error handler. No
     * legitimate reason a payload is unavailable needs a URL to say it, so nothing of value is
     * lost by refusing to carry one.
     *
     * <p>Control characters go the same way: a reason is a sentence, and a sentence that can move
     * the cursor or inject a line is being used for something other than reading.
     */
    static String sanitised(String violation) {
        String withoutLinks = LINK.matcher(violation).replaceAll("[link removed]");
        String printable = withoutLinks.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s{2,}", " ").trim();

        if (printable.isEmpty()) {
            return REFUSED_WITHOUT_A_REASON;
        }

        return printable.length() <= MAX_RELAYED_LENGTH
            ? printable
            : printable.substring(0, MAX_RELAYED_LENGTH) + "…";
    }

    private String createPayloadRequestJws(String emv, UUID correlationId) {
        String qrCodeB64 = ENCODER.encodeToString(emv.getBytes());

        PaymentPayloadRequestDTO requestDTO = new PaymentPayloadRequestDTO().qrCodeContent(qrCodeB64);

        SignatureOutputDataDTO signatureOutput = qrCodeSignatureService.signData(
                new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, requestDTO, getCorrelationId(correlationId), null,
                        null));

        return signatureOutput.jwsToken();
    }

    private static UUID getCorrelationId(UUID correlationId) {
        return nonNull(correlationId) ? correlationId : UUID.randomUUID();
    }

}
