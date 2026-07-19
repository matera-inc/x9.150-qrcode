/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.SignatureOutputDataDTO;
import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.app.service.JwkService;
import com.matera.x9qrcode.app.service.PEMService;
import com.matera.x9qrcode.app.service.PrivateKeyRetriever;
import com.matera.x9qrcode.app.service.QRCodeEMVService;
import com.matera.x9qrcode.app.service.QRCodeExternalJwkService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.service.TruststoreRetriever;
import com.matera.x9qrcode.app.usecase.validatesignature.ExternalCertificateInput;
import com.matera.x9qrcode.app.usecase.validatesignature.ExternalCertificateOutput;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationInput;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationOutput;
import com.matera.x9qrcode.domain.dto.CertificateEndpointTypeEnum;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;
import com.matera.x9qrcode.infrastructure.service.thirdparty.keystore.TrustManagersFacadeBean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jose.util.Base64URL;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.MessageDigestAlgorithms;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.matera.x9qrcode.app.service.SignatureConstants.DEFAULT_PKIX_ALGORITHM;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_CORRELATION_ID;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_IAT;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_STATUS_CODE;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_HEADER_TTL;
import static com.matera.x9qrcode.app.service.SignatureConstants.JWS_PAYLOAD_QR_CODE_CONTENT;
import static com.matera.x9qrcode.app.service.SignatureConstants.PEM_FILE_EXTENSION;
import static com.matera.x9qrcode.app.service.SignatureConstants.SECONDS_TO_MILLIS_VALUE;
import static com.matera.x9qrcode.app.service.SignatureConstants.X9_KEY_ID;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
public class JwsQRCodeSignatureService implements QRCodeSignatureService {

    private static final Set<String> SUPPORTED_CRITICAL_HEADERS =
        Set.of(JWS_HEADER_CORRELATION_ID, JWS_HEADER_IAT, JWS_HEADER_TTL, JWS_HEADER_STATUS_CODE);

    private static final List<String> RSA_ALGORITHMS = List.of(
        JWSAlgorithm.RS256.getName(), JWSAlgorithm.RS384.getName(), JWSAlgorithm.RS512.getName(),
        JWSAlgorithm.PS256.getName(), JWSAlgorithm.PS384.getName(), JWSAlgorithm.PS512.getName());
    private static final List<String> EC_ALGORITHMS = List.of(
        JWSAlgorithm.ES256.getName(), JWSAlgorithm.ES384.getName(), JWSAlgorithm.ES512.getName());

    private final X9Properties x9Properties;
    private final ObjectMapper objectMapper;
    private final QRCodeLocationService qrCodeLocationService;
    private final JwkService<JWK, JWSSigner> jwkService;
    private final PrivateKeyRetriever privateKeyRetriever;
    private final QRCodeExternalJwkService qrCodeExternalJwkService;
    private final TruststoreRetriever truststoreRetriever;
    private final TrustManagersFacadeBean trustManagersFacadeBean;
    private final PEMService pemService;
    private final QRCodeEMVService qrCodeEMVService;

    @Override
    public SignatureOutputDataDTO signData(SignatureInputDataDTO signatureInputDataDTO) {
        String signature = generateJws(signatureInputDataDTO);

        return new SignatureOutputDataDTO(signature, signatureInputDataDTO.correlationId());
    }

    @Override
    public SignatureValidationOutput validateSignature(SignatureValidationInput input) throws ServiceException {
        log.info("Validating signature with input: {}", input);

        try {
            UUID correlationId = validateJwsToken(input.locationId(), input.jwsToken());

            if (nonNull(input.correlationId())) {
                if (!input.correlationId().equals(correlationId)) {
                    log.error("Correlation ID mismatch. Expected: {}, Found: {}", input.correlationId(), correlationId);

                    return SignatureValidationOutput.invalidSignature();
                }
            }

            return SignatureValidationOutput.validSignature(correlationId);
        } catch (Exception e) {
            log.error("Error validating JWS signature: {}", e.getMessage(), e);

            return SignatureValidationOutput.invalidSignature();
        }
    }

    @Override
    public byte[] retrieveDigitalSignatureCertificate() throws ServiceException {
        try {
            return pemService.generate(privateKeyRetriever.getCertificateChain());
        } catch (Exception e) {
            throw new ServiceException("Error obtaining local certificate", e);
        }
    }

    @Override
    public Map<String, Object> retrieveDigitalSignatureJwkSet() throws ServiceException {
        return jwkService.retrieveJwkSet();
    }

    private JWK retrieveExternalJwkInformation(JWSHeader header, ExternalCertificateOutput externalCertificateOutput)
        throws JOSEException, ParseException {
        JWSAlgorithm algorithm = header.getAlgorithm();

        log.debug("Retrieving JWK information for signature validation with algorithm: {}", algorithm);

        if (CertificateEndpointTypeEnum.JWK_SET.equals(externalCertificateOutput.endpointTypeEnum())) {
            JWK externalJwk = JWKSet.parse(externalCertificateOutput.jwkSet()).getKeyByKeyId(header.getKeyID());

            validateExternalCertificate(header, externalJwk.getParsedX509CertChain());

            if (algorithm.equals(externalJwk.getAlgorithm())) {
                return externalJwk;
            } else {
                log.error("Invalid key algorithm for signature validation: {}", externalJwk.getAlgorithm());
            }
        } else {
            X509Certificate signatureExternalCertificate = validateExternalCertificate(header, externalCertificateOutput.certificates());

            return new RSAKey
                .Builder(RSAKey.parse(signatureExternalCertificate))
                .algorithm(algorithm)
                .keyUse(KeyUse.SIGNATURE)
                .keyOperations(Set.of(KeyOperation.VERIFY))
                .build();
        }

        throw new ServiceException("Could not retrieve JWK information for signature validation");
    }

    private void fillCertificateData(JWSHeader.Builder headerBuilder) throws ServiceException {
        CertificateEndpointTypeEnum endpointType = x9Properties.getCertificate().getEndpointType();

        if (isNull(endpointType)) {
            throw new ServiceException("Missing certificate type configuration");
        }

        log.debug("Using certificate endpoint type {}: {}", endpointType.name(), endpointType.getDescription());

        try {
            switch (endpointType) {
                case NONE -> {
                    log.info("Filling JWS header with Certificate chain...");

                    headerBuilder.x509CertChain(getLocalCertificateChain());
                }
                case PEM -> {
                    URI x5c = qrCodeLocationService.parseLocation(getCertificateEndpoint(), false);

                    log.info("Filling JWS header with certificate URL: {}", x5c.toString());

                    headerBuilder.x509CertURL(x5c);
                }
                case JWK_SET -> {
                    URI jwkSetUrl = qrCodeLocationService.parseLocation(x9Properties.getPublicEndpoints().getJwkSetUri().toString(), false);

                    log.info("Filling JWS header with JWK Set URL: {}", jwkSetUrl.toString());

                    headerBuilder.jwkURL(jwkSetUrl);
                }
            }
        } catch (Exception ex) {
            throw new ServiceException("Error obtaining certificate URL: %s".formatted(ex.getMessage()), ex);
        }

        fillCertificateThumbprint(headerBuilder);
    }

    private List<Base64> getLocalCertificateChain() throws KeyStoreException {
        Certificate[] certificateChain = privateKeyRetriever.getCertificateChain();

        return Arrays.stream(certificateChain).map(cert -> {
            try {
                return Base64.encode(cert.getEncoded());
            } catch (CertificateEncodingException e) {
                throw new ServiceException("Error encoding certificates to Base64", e);
            }
        }).toList();
    }

    private String getCertificateEndpoint() {
        return StringUtils.join(x9Properties.getPublicEndpoints().getCertificateUriPrefix(), X9_KEY_ID, PEM_FILE_EXTENSION);
    }

    private X509Certificate validateExternalCertificate(JWSHeader header, List<X509Certificate> externalCertificates) {
        X509Certificate x509CertificateRoot = validateCertificateChain(externalCertificates);
        validateRootCertificateIssuer(x509CertificateRoot);

        X509Certificate signatureExternalCertificate = validateAndExtractCertificateByThumbprint(header, externalCertificates);

        validateCertificateValidity(signatureExternalCertificate);
        validateKeyUsage(signatureExternalCertificate);

        log.debug("External certificate validation passed for subject: {}",
            signatureExternalCertificate.getSubjectX500Principal().getName());

        return signatureExternalCertificate;
    }

    private void validateRootCertificateIssuer(X509Certificate rootCertificate) {
        String expectedIssuer = x9Properties.getCertificate().getIssuerName();

        String certificateIssuer = rootCertificate.getIssuerX500Principal().getName();

        if (isNotBlank(expectedIssuer) && !Strings.CI.contains(certificateIssuer, expectedIssuer)) {
            throw new ServiceException(
                "Invalid external certificate certificateIssuer. Expected to contain: %s, Found: %s".formatted(expectedIssuer, certificateIssuer));
        }

        boolean issuerNotTrusted =
            truststoreRetriever.getAllTrustedCertificates()
                               .stream()
                               .noneMatch(trustedCert ->
                                   trustedCert.getSubjectX500Principal().equals(rootCertificate.getIssuerX500Principal()));

        if (issuerNotTrusted) {
            throw new ServiceException("Certificate certificateIssuer not trusted: %s".formatted(certificateIssuer));
        }

        log.debug("Certificate issuer validation passed: {}", certificateIssuer);
    }

    private X509Certificate validateCertificateChain(List<X509Certificate> externalCertificates) {
        try {
            if (CollectionUtils.isEmpty(externalCertificates)) {
                throw new ServiceException("External certificate chain is empty. No certificates provided for validation");
            }

            Set<TrustAnchor> trustAnchorSet =
                Arrays.stream(trustManagersFacadeBean.getTrustManager().getAcceptedIssuers())
                      .map(x509Certificate -> new TrustAnchor(x509Certificate, null))
                      .collect(Collectors.toSet());

            CertPath certPath = CertificateFactory
                .getInstance((x9Properties.getCertificate().getCertificateSupportedType()))
                .generateCertPath(externalCertificates);

            CertPathValidator certPathValidator = CertPathValidator.getInstance(DEFAULT_PKIX_ALGORITHM);

            PKIXParameters pkixParameters = new PKIXParameters(trustAnchorSet);

            // Revocation is a CA function: the issuing CA publishes a CRL (via a CRL Distribution
            // Point) or runs an OCSP responder (via AIA). A self-signed end-entity certificate is its
            // own issuer, so there is no authority to consult and its revocation status is
            // undeterminable by definition — not even SOFT_FAIL covers "no revocation mechanism at
            // all", so the check would always fail. We therefore skip revocation for self-signed
            // certificates (e.g. the shipped non-production demo keystore) while CA-issued
            // certificates ALWAYS undergo full PKIX revocation checking. This is decided by the
            // certificate itself — there is no config flag to forget or to leave disabled in prod.
            if (isSelfSigned(externalCertificates.getFirst())) {
                pkixParameters.setRevocationEnabled(false);
                log.warn("Skipping certificate revocation check: end-entity certificate is self-signed "
                        + "(no CRL/OCSP authority to consult). Subject: {}",
                    externalCertificates.getFirst().getSubjectX500Principal().getName());
            } else {
                PKIXRevocationChecker pkixRevocationChecker = (PKIXRevocationChecker) certPathValidator.getRevocationChecker();
                pkixRevocationChecker.setOptions(EnumSet.of(
                    PKIXRevocationChecker.Option.PREFER_CRLS,
                    PKIXRevocationChecker.Option.ONLY_END_ENTITY,
                    PKIXRevocationChecker.Option.SOFT_FAIL
                ));

                pkixParameters.setRevocationEnabled(true);
                pkixParameters.addCertPathChecker(pkixRevocationChecker);
            }

            PKIXCertPathValidatorResult validatorResult =
                (PKIXCertPathValidatorResult) certPathValidator.validate(certPath, pkixParameters);

            X509Certificate trustedRootCertificate = validatorResult.getTrustAnchor().getTrustedCert();

            log.debug("Certificate path validation passed. Chain validated against Root CA: {}",
                trustedRootCertificate.getSubjectX500Principal().getName());

            return trustedRootCertificate;
        } catch (CertPathValidatorException | CertificateException e) {
            throw new ServiceException("X9 Certificate chain validation failed: %s.".formatted(e.getMessage()), e);
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new ServiceException("Unexpected error during external certificate chain validation: %s".formatted(e.getMessage()), e);
        }
    }

    /**
     * A certificate is self-signed when its signature verifies with its own public key and its
     * subject equals its issuer. Such certificates have no issuing CA and therefore no revocation
     * mechanism (CRL/OCSP), so their revocation status cannot be determined.
     */
    private static boolean isSelfSigned(X509Certificate certificate) {
        try {
            certificate.verify(certificate.getPublicKey());
            return certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal());
        } catch (SignatureException | InvalidKeyException e) {
            // Not verifiable with its own public key => signed by a different (CA) key => not self-signed.
            return false;
        } catch (CertificateException | NoSuchAlgorithmException | NoSuchProviderException e) {
            return false;
        }
    }

    private void validateKeyUsage(X509Certificate certificate) {
        boolean[] keyUsage = certificate.getKeyUsage();

        if (nonNull(keyUsage) && !keyUsage[0]) {
            throw new ServiceException("Certificate does not have digitalSignature key usage, which is required for JWS verification");
        }

        log.debug("Certificate key usage validation passed");
    }

    private String generateJws(SignatureInputDataDTO signatureInputDataDTO) {
        log.debug("Signing json: {}", signatureInputDataDTO.body());

        JWSHeader.Builder jwsHeaderBuilder = buildJwsHeader(signatureInputDataDTO);

        fillCertificateData(jwsHeaderBuilder);

        try {
            JWSObject jwsObject = new JWSObject(jwsHeaderBuilder.build(),
                new Payload(objectMapper.writeValueAsString(signatureInputDataDTO.body())));

            jwsObject.sign(jwkService.retrieveSigner());

            String serializedJws = jwsObject.serialize();

            log.debug("Returning the serialized object: {}", serializedJws);

            return serializedJws;
        } catch (JOSEException | JsonProcessingException e) {
            log.error("Unexpected error signing the Dynamic QR code response. Detail: ", e);

            throw new ServiceException(e.getMessage());
        }
    }

    private JWSHeader.Builder buildJwsHeader(SignatureInputDataDTO signatureInputDataDTO) {
        JWK jwk = jwkService.retrieveJwk();

        JWSHeader.Builder builder = new JWSHeader.Builder(JWSAlgorithm.parse(jwk.getAlgorithm().getName()))
            .keyID(jwk.getKeyID())
            .type(JOSEObjectType.JOSE);

        UUID correlationId = signatureInputDataDTO.correlationId();

        if (isNull(correlationId)) {
            throw new ServiceException("correlationId is required for JWS signing");
        }

        long ttl = nonNull(signatureInputDataDTO.ttlMillis()) ?
            signatureInputDataDTO.ttlMillis() :
            x9Properties.getPublicEndpoints().getJwsTtlSeconds() * SECONDS_TO_MILLIS_VALUE;

        if (ttl <= 0) {
            throw new ServiceException("ttl must be a positive value for JWS signing");
        }

        builder.customParam(JWS_HEADER_CORRELATION_ID, correlationId);
        builder.customParam(JWS_HEADER_IAT, Instant.now(Clock.systemUTC()).toEpochMilli());
        builder.customParam(JWS_HEADER_TTL, ttl);

        if (nonNull(signatureInputDataDTO.statusCode())) {
            builder.customParam(JWS_HEADER_STATUS_CODE, signatureInputDataDTO.statusCode());
            builder.criticalParams(Set.of(JWS_HEADER_CORRELATION_ID, JWS_HEADER_IAT, JWS_HEADER_TTL, JWS_HEADER_STATUS_CODE));
        } else {
            builder.criticalParams(Set.of(JWS_HEADER_CORRELATION_ID, JWS_HEADER_IAT, JWS_HEADER_TTL));
        }

        return builder;
    }

    private void fillCertificateThumbprint(JWSHeader.Builder headerBuilder) {
        try {
            X509Certificate certificate = privateKeyRetriever.getCertificate();
            Base64URL thumbprint = getDigestThumbprint(certificate);
            headerBuilder.x509CertSHA256Thumbprint(thumbprint);

            log.debug("Certificate thumbprint (x5t#S256) added to JWS header: {}", thumbprint);
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new ServiceException("Error computing certificate thumbprint: %s".formatted(e.getMessage()), e);
        } catch (Exception e) {
            throw new ServiceException("Error retrieving certificate for thumbprint: %s".formatted(e.getMessage()), e);
        }
    }

    private Base64URL getDigestThumbprint(X509Certificate certificate) throws NoSuchAlgorithmException, CertificateEncodingException {
        MessageDigest sha256 = MessageDigest.getInstance(MessageDigestAlgorithms.SHA_256);
        byte[] certDigest = sha256.digest(certificate.getEncoded());
        return Base64URL.encode(certDigest);
    }

    private UUID validateJwsToken(String locationId, String jwsToken) throws ParseException, JOSEException {
        JWSObject jwsObject = JWSObject.parse(jwsToken);
        JWSHeader header = jwsObject.getHeader();

        log.info("Validating JWS token with algorithm: {}", header.getAlgorithm());

        validateCriticalHeaders(header);
        UUID correlationId = validateAndExtractCorrelationId(header);
        validateJwsExpiration(validateAndExtractIat(header), validateAndExtractTtl(header));
        validateStatusCodeIfResponse(header);
        validateLocationId(locationId, jwsObject.getPayload().toJSONObject());

        ExternalCertificateInput externalCertificateInput =
            createExternalCertificateInput(jwsObject.getHeader());

        ExternalCertificateOutput externalCertificateOutput;

        if (CertificateEndpointTypeEnum.NONE.equals(externalCertificateInput.endpointTypeEnum())) {
            List<X509Certificate> x509Certificates = header.getX509CertChain().stream().map(this::convertToX509).toList();

            externalCertificateOutput = new ExternalCertificateOutput(CertificateEndpointTypeEnum.NONE, x509Certificates, Map.of());
        } else {
            externalCertificateOutput = qrCodeExternalJwkService.retrieveCertificate(externalCertificateInput);
        }

        JWK jwk = retrieveExternalJwkInformation(header, externalCertificateOutput);

        try {
            if (!jwsObject.verify(createVerifier(jwk, header.getAlgorithm()))) {
                throw new ServiceException("JWS signature verification failed.");
            }
        } catch (JOSEException e) {
            throw new ServiceException("Error during JWS signature verification: %s".formatted(e.getMessage()), e);
        }

        log.info("JWS signature successfully validated for correlationId: {}", correlationId);

        return correlationId;
    }

    private void validateCriticalHeaders(JWSHeader header) {
        Set<String> criticalHeaders = header.getCriticalParams();

        if (isNull(criticalHeaders) || criticalHeaders.isEmpty()) {
            throw new ServiceException("Missing required 'crit' parameter. X9.150 requires the JWS Protected Header to include the 'crit' parameter");
        }

        if (!criticalHeaders.contains(JWS_HEADER_CORRELATION_ID) ||
            !criticalHeaders.contains(JWS_HEADER_IAT) ||
            !criticalHeaders.contains(JWS_HEADER_TTL)) {
            throw new ServiceException("The 'crit' parameter must include at least: correlationId, iat, ttl");
        }

        for (String criticalHeader : criticalHeaders) {
            if (!SUPPORTED_CRITICAL_HEADERS.contains(criticalHeader)) {
                throw new ServiceException("Unrecognized critical header parameter: %s".formatted(criticalHeader));
            }
        }

        log.debug("Critical headers validated: {}", criticalHeaders);
    }

    private UUID validateAndExtractCorrelationId(JWSHeader header) {
        Object correlationIdHeader = header.getCustomParam(JWS_HEADER_CORRELATION_ID);

        if (isNull(correlationIdHeader)) {
            throw new ServiceException("Missing required header parameter: correlationId");
        }

        String correlationId = correlationIdHeader.toString();

        log.debug("Validating correlationId: {}", correlationId);

        try {
            return UUID.fromString(correlationId);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("Invalid correlationId format. Must be a valid UUID: %s".formatted(correlationId));
        }
    }

    private long validateAndExtractIat(JWSHeader header) {
        Object iatHeader = header.getCustomParam(JWS_HEADER_IAT);

        if (isNull(iatHeader)) {
            throw new ServiceException("Missing required header parameter: iat (issued-at)");
        }

        long iat;

        try {
            iat = (Long) iatHeader;
        } catch (ClassCastException e) {
            throw new ServiceException("Invalid iat format. Must be a Unix timestamp in milliseconds");
        }

        if (iat <= 0) {
            throw new ServiceException("Invalid iat value. Must be a positive Unix timestamp in milliseconds");
        }

        log.debug("Validated iat: {}", iat);

        return iat;
    }

    private long validateAndExtractTtl(JWSHeader header) {
        Object ttlHeader = header.getCustomParam(JWS_HEADER_TTL);

        if (isNull(ttlHeader)) {
            throw new ServiceException("Missing required header parameter: ttl (time-to-live)");
        }

        long ttl;

        try {
            ttl = (Long) ttlHeader;
        } catch (ClassCastException e) {
            throw new ServiceException("Invalid ttl format. Must be a positive integer in milliseconds");
        }

        if (ttl <= 0) {
            throw new ServiceException("Invalid ttl value. Must be a positive integer in milliseconds");
        }

        log.debug("Validated ttl: {} ms", ttl);

        return ttl;
    }

    private void validateStatusCodeIfResponse(JWSHeader header) {
        Object statusCodeHeader = header.getCustomParam(JWS_HEADER_STATUS_CODE);

        Set<String> criticalHeaders = header.getCriticalParams();

        if (nonNull(criticalHeaders) && criticalHeaders.contains(JWS_HEADER_STATUS_CODE)) {
            if (isNull(statusCodeHeader)) {
                throw new ServiceException("Missing required header parameter: statusCode (required for responses)");
            }

            String statusCode = statusCodeHeader.toString();

            try {
                int code = Integer.parseInt(statusCode);
                HttpStatus httpStatus = HttpStatus.resolve(code);

                if (isNull(httpStatus)) {
                    throw new ServiceException("Invalid statusCode. Must be a valid HTTP status code: %s".formatted(statusCode));
                }

                log.debug("Validated statusCode: {} ({})", statusCode, httpStatus.getReasonPhrase());
            } catch (NumberFormatException e) {
                throw new ServiceException("Invalid statusCode format. Must be a numeric HTTP status code: %s".formatted(statusCode));
            }
        }
    }

    private void validateLocationId(String locationId, Map<String, Object> payloadJson) {
        if (isBlank(locationId)) {
            return;
        }

        if (!payloadJson.containsKey(JWS_PAYLOAD_QR_CODE_CONTENT)) {
            throw new ServiceException("JWS payload JSON expected to have property qrCodeContent.");
        }

        String qrCodeContent = payloadJson.get(JWS_PAYLOAD_QR_CODE_CONTENT).toString();

        String payloadUrl = qrCodeEMVService.extractPayloadUrl(new Base64(qrCodeContent).decodeToString());

        if (!Strings.CI.contains(payloadUrl, locationId)) {
            throw new ServiceException("JWS payload URL does not contains ID %s".formatted(locationId));
        }
    }

    private void validateJwsExpiration(long iat, long ttl) {
        long currentTime = Instant.now(Clock.systemUTC()).toEpochMilli();
        long expirationTime = iat + ttl;

        if (currentTime >= expirationTime) {
            throw new ServiceException(
                String.format("JWS message has expired. Current time: %d, Expiration time (iat + ttl): %d", currentTime, expirationTime));
        }

        log.debug("Expiration check passed. Message valid until: {}", Instant.ofEpochMilli(expirationTime));
    }

    private void validateCertificateValidity(X509Certificate certificate) {
        try {
            certificate.checkValidity();

            log.debug("Certificate validity check passed. Valid until: {}", certificate.getNotAfter().toInstant());
        } catch (CertificateExpiredException e) {
            throw new ServiceException("Certificate has expired. Expired at: %s".formatted(certificate.getNotAfter().toInstant()), e);
        } catch (CertificateNotYetValidException e) {
            throw new ServiceException("Certificate is not yet valid. Valid from: %s".formatted(certificate.getNotBefore().toInstant()), e);
        }
    }

    private X509Certificate validateAndExtractCertificateByThumbprint(JWSHeader header, List<X509Certificate> externalCertificates) {
        Base64URL thumbprint = header.getX509CertSHA256Thumbprint();

        if (isNull(thumbprint)) {
            throw new ServiceException("Missing required header parameter: x5t#S256 (certificate thumbprint)");
        }

        return externalCertificates
            .stream()
            .map(certificate -> getX509CertificateIfMatch(thumbprint, certificate))
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow(() -> new ServiceException(
                String.format("No matching certificate found for thumbprint: %s", thumbprint)));
    }

    private Optional<X509Certificate> getX509CertificateIfMatch(Base64URL thumbprint, X509Certificate certificate) {
        try {
            Base64URL computedThumbprint = getDigestThumbprint(certificate);

            if (thumbprint.equals(computedThumbprint)) {
                log.debug("Certificate thumbprint validation matched with thumbprint: {}", thumbprint);
                return Optional.of(certificate);
            }
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new ServiceException("Error computing certificate thumbprint: %s".formatted(e.getMessage()), e);
        }
        return Optional.empty();
    }

    private JWSVerifier createVerifier(JWK jwk, Algorithm algorithm) throws JOSEException {
        String algName = algorithm.getName();

        if (RSA_ALGORITHMS.contains(algName)) {
            return new RSASSAVerifier(jwk.toRSAKey().toRSAPublicKey(), SUPPORTED_CRITICAL_HEADERS);
        }

        if (EC_ALGORITHMS.contains(algName)) {
            return new ECDSAVerifier(((ECKey) jwk).toECPublicKey(), SUPPORTED_CRITICAL_HEADERS);
        }

        throw new ServiceException("Unsupported signature algorithm: %s".formatted(algName));
    }

    private ExternalCertificateInput createExternalCertificateInput(JWSHeader jwsHeader) {
        if (nonNull(jwsHeader.getJWKURL())) {
            String jwkUrl = jwsHeader.getJWKURL().toString();

            log.info("JWK URL found in JWS header: {}", jwkUrl);

            return new ExternalCertificateInput(CertificateEndpointTypeEnum.JWK_SET, jwkUrl);
        } else if (nonNull(jwsHeader.getX509CertURL())) {
            String certificateUrl = jwsHeader.getX509CertURL().toString();

            log.info("X5u found in JWS header as URL: {}", certificateUrl);

            return new ExternalCertificateInput(CertificateEndpointTypeEnum.PEM, certificateUrl);
        } else if (CollectionUtils.isNotEmpty(jwsHeader.getX509CertChain())) {
            log.info("Found a certificate chain in JWS header, no certificate URL should be used.");

            return new ExternalCertificateInput(CertificateEndpointTypeEnum.NONE, null);
        } else {
            throw new ServiceException("No certificate URL found in JWS header for signature validation.");
        }
    }

    public X509Certificate convertToX509(Base64 base64Certificate) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance(x9Properties.getCertificate().getCertificateSupportedType());

            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(base64Certificate.decode()));
        } catch (Exception e) {
            throw new ServiceException("Could not convert to X509Certificate. Details: %s".formatted(e.getMessage()));
        }
    }

}
