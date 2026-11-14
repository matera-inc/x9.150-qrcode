/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.SignatureOutputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureProblemEnumDTO;
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
import com.matera.x9qrcode.domain.utils.BusinessClock;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;
import com.matera.x9qrcode.infrastructure.service.thirdparty.keystore.TrustManagersFacadeBean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObject;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.PlainObject;
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

    /** Used when {@code x9.public-endpoints.jws-clock-skew-seconds} is not set: one minute. */
    private static final long DEFAULT_CLOCK_SKEW_MILLIS = 60_000L;

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
            ValidatedJws validated = validateJwsToken(input.locationId(), input.jwsToken());

            UUID correlationId = validated.correlationId();

            if (nonNull(input.correlationId())) {
                if (!input.correlationId().equals(correlationId)) {
                    log.error("Correlation ID mismatch. Expected: {}, Found: {}", input.correlationId(), correlationId);

                    return SignatureValidationOutput.invalidSignature(SignatureProblemEnumDTO.CORRELATION_ID_MISMATCH,
                        "correlationId mismatch: expected %s, found %s".formatted(input.correlationId(), correlationId));
                }
            }

            return SignatureValidationOutput.validSignature(
                correlationId, validated.submittedQrCodeContent(), validated.signerSubject());
        } catch (SignatureCheckException e) {
            log.error("JWS signature did not verify ({}): {}", e.problem(), e.getMessage(), e);

            return SignatureValidationOutput.invalidSignature(e.problem(), e.getMessage());
        } catch (Exception e) {
            log.error("Error validating JWS signature: {}", e.getMessage(), e);

            return SignatureValidationOutput.invalidSignature(SignatureProblemEnumDTO.UNVERIFIABLE,
                isBlank(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * A check that failed for a reason with a name: the code from the closed list, and the words.
     *
     * <p>A {@link ServiceException}, so anything that reaches past {@link #validateSignature} reads as
     * before; only the code is new.
     */
    static final class SignatureCheckException extends ServiceException {

        private final transient SignatureProblemEnumDTO problem;

        SignatureCheckException(SignatureProblemEnumDTO problem, String message) {
            super(message);
            this.problem = problem;
        }

        SignatureCheckException(SignatureProblemEnumDTO problem, String message, Throwable cause) {
            super(message, cause);
            this.problem = problem;
        }

        SignatureProblemEnumDTO problem() {
            return problem;
        }
    }

    private static SignatureCheckException check(SignatureProblemEnumDTO problem, String message) {
        return new SignatureCheckException(problem, message);
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

    /**
     * The key to verify with, and who it belongs to.
     *
     * <p>The subject travels out with the key because it is the only identity in a payment
     * notification that the sender did not choose: the chain behind it was validated against the
     * trusted roots. {@code payer.info} is a field in the body. See {@code ReservationHolderVO}.
     */
    private record VerificationKey(JWK jwk, String signerSubject) { }

    private VerificationKey retrieveExternalJwkInformation(JWSHeader header, ExternalCertificateOutput externalCertificateOutput)
        throws JOSEException, ParseException {
        JWSAlgorithm algorithm = header.getAlgorithm();

        log.debug("Retrieving JWK information for signature validation with algorithm: {}", algorithm);

        if (CertificateEndpointTypeEnum.JWK_SET.equals(externalCertificateOutput.endpointTypeEnum())) {
            JWK externalJwk;
            try {
                externalJwk = JWKSet.parse(externalCertificateOutput.jwkSet()).getKeyByKeyId(header.getKeyID());
            } catch (ParseException e) {
                throw new SignatureCheckException(SignatureProblemEnumDTO.CERTIFICATE_UNAVAILABLE,
                    "The signer's JWK set could not be read: %s".formatted(e.getMessage()), e);
            }

            if (isNull(externalJwk)) {
                throw check(SignatureProblemEnumDTO.CERTIFICATE_UNAVAILABLE,
                    "The signer's JWK set has no key with kid %s".formatted(header.getKeyID()));
            }

            X509Certificate signatureExternalCertificate =
                validateExternalCertificate(header, externalJwk.getParsedX509CertChain());

            if (algorithm.equals(externalJwk.getAlgorithm())) {
                return new VerificationKey(externalJwk, subjectOf(signatureExternalCertificate));
            } else {
                log.error("Invalid key algorithm for signature validation: {}", externalJwk.getAlgorithm());
            }
        } else {
            X509Certificate signatureExternalCertificate = validateExternalCertificate(header, externalCertificateOutput.certificates());

            return new VerificationKey(
                toVerificationJwk(signatureExternalCertificate, algorithm), subjectOf(signatureExternalCertificate));
        }

        throw check(SignatureProblemEnumDTO.BAD_HEADER,
            "The header's alg %s is not the algorithm of the signer's key".formatted(algorithm));
    }

    private static String subjectOf(X509Certificate certificate) {
        return isNull(certificate) ? null : certificate.getSubjectX500Principal().getName();
    }

    /**
     * The end-entity certificate's public key, as a JWK to verify with.
     *
     * <p>Built from what the certificate actually holds rather than from an assumption. This called
     * {@code RSAKey.parse} unconditionally, so a payer presenting an EC certificate was refused with
     * "The public key of the X.509 certificate is not RSA" — while {@link #createVerifier} carried a
     * perfectly good {@code ECDSAVerifier} branch that this path could never reach.
     *
     * <p>That was an interoperability failure, not a missing feature. ANSI X9.150-2026 leaves
     * {@code alg} to the X9-approved suite (SD-34) rather than naming one, and its own Annex A
     * examples sign with {@code ES256}; {@code x5c} is an explicitly permitted way to carry the
     * chain. So the refused payer was the conformant one.
     */
    private JWK toVerificationJwk(X509Certificate certificate, JWSAlgorithm algorithm)
        throws JOSEException {

        JWK certificateKey = JWK.parse(certificate);

        return switch (certificateKey) {
            case RSAKey rsaKey -> new RSAKey.Builder(rsaKey)
                .algorithm(algorithm)
                .keyUse(KeyUse.SIGNATURE)
                .keyOperations(Set.of(KeyOperation.VERIFY))
                .build();
            case ECKey ecKey -> new ECKey.Builder(ecKey)
                .algorithm(algorithm)
                .keyUse(KeyUse.SIGNATURE)
                .keyOperations(Set.of(KeyOperation.VERIFY))
                .build();
            default -> throw check(SignatureProblemEnumDTO.UNTRUSTED_CHAIN,
                "Unsupported certificate key type for signature verification: %s"
                    .formatted(certificateKey.getKeyType()));
        };
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
            throw check(SignatureProblemEnumDTO.UNTRUSTED_CHAIN,
                "Invalid external certificate certificateIssuer. Expected to contain: %s, Found: %s".formatted(expectedIssuer, certificateIssuer));
        }

        boolean issuerNotTrusted =
            truststoreRetriever.getAllTrustedCertificates()
                               .stream()
                               .noneMatch(trustedCert ->
                                   trustedCert.getSubjectX500Principal().equals(rootCertificate.getIssuerX500Principal()));

        if (issuerNotTrusted) {
            throw check(SignatureProblemEnumDTO.UNTRUSTED_CHAIN,
                "Certificate certificateIssuer not trusted: %s".formatted(certificateIssuer));
        }

        log.debug("Certificate issuer validation passed: {}", certificateIssuer);
    }

    private X509Certificate validateCertificateChain(List<X509Certificate> externalCertificates) {
        try {
            if (CollectionUtils.isEmpty(externalCertificates)) {
                throw check(SignatureProblemEnumDTO.UNTRUSTED_CHAIN,
                    "External certificate chain is empty. No certificates provided for validation");
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
            throw new SignatureCheckException(SignatureProblemEnumDTO.UNTRUSTED_CHAIN,
                "X9 Certificate chain validation failed: %s.".formatted(e.getMessage()), e);
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
            throw check(SignatureProblemEnumDTO.UNTRUSTED_CHAIN,
                "Certificate does not have digitalSignature key usage, which is required for JWS verification");
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
        builder.customParam(JWS_HEADER_IAT, Instant.now(BusinessClock.clock()).toEpochMilli());
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

    /** What a verified JWS yields: who correlated it, and the QR content it carried (may be null). */
    private record ValidatedJws(UUID correlationId, String submittedQrCodeContent, String signerSubject) { }

    private ValidatedJws validateJwsToken(String locationId, String jwsToken) throws ParseException, JOSEException {
        JWSObject jwsObject = parseJws(jwsToken);
        JWSHeader header = jwsObject.getHeader();

        log.info("Validating JWS token with algorithm: {}", header.getAlgorithm());

        validateCriticalHeaders(header);
        UUID correlationId = validateAndExtractCorrelationId(header);
        validateJwsExpiration(validateAndExtractIat(header), validateAndExtractTtl(header));
        validateStatusCodeIfResponse(header);
        String submittedQrCodeContent = validateLocationId(locationId, jwsObject.getPayload().toJSONObject());

        ExternalCertificateInput externalCertificateInput =
            createExternalCertificateInput(jwsObject.getHeader());

        ExternalCertificateOutput externalCertificateOutput;

        if (CertificateEndpointTypeEnum.NONE.equals(externalCertificateInput.endpointTypeEnum())) {
            List<X509Certificate> x509Certificates = header.getX509CertChain().stream().map(this::convertToX509).toList();

            externalCertificateOutput = new ExternalCertificateOutput(CertificateEndpointTypeEnum.NONE, x509Certificates, Map.of());
        } else {
            try {
                externalCertificateOutput = qrCodeExternalJwkService.retrieveCertificate(externalCertificateInput);
            } catch (RuntimeException e) {
                throw new SignatureCheckException(SignatureProblemEnumDTO.CERTIFICATE_UNAVAILABLE,
                    "The signer's certificate could not be retrieved from %s: %s"
                        .formatted(externalCertificateInput.url(), e.getMessage()), e);
            }
        }

        VerificationKey verificationKey = retrieveExternalJwkInformation(header, externalCertificateOutput);

        try {
            if (!jwsObject.verify(createVerifier(verificationKey.jwk(), header.getAlgorithm()))) {
                throw check(SignatureProblemEnumDTO.BAD_SIGNATURE, "JWS signature verification failed.");
            }
        } catch (JOSEException e) {
            throw new SignatureCheckException(SignatureProblemEnumDTO.BAD_SIGNATURE,
                "Error during JWS signature verification: %s".formatted(e.getMessage()), e);
        }

        log.info("JWS signature successfully validated for correlationId: {}", correlationId);

        return new ValidatedJws(correlationId, submittedQrCodeContent, verificationKey.signerSubject());
    }

    /**
     * Reads the token as a JWS, and says precisely what it is when it is not one: nothing at all, not
     * JOSE, an unsecured object ({@code alg: none}) or an encrypted one.
     */
    private static JWSObject parseJws(String token) {
        if (isBlank(token)) {
            throw check(SignatureProblemEnumDTO.ABSENT_BODY, "There is no JWS: the body is empty");
        }

        JOSEObject jose;
        try {
            jose = JOSEObject.parse(token.trim());
        } catch (ParseException e) {
            throw new SignatureCheckException(SignatureProblemEnumDTO.NOT_JWS,
                "The body is not a JWS: %s".formatted(e.getMessage()), e);
        }

        return switch (jose) {
            case JWSObject jws -> jws;
            case PlainObject plain -> throw check(SignatureProblemEnumDTO.ALG_NONE,
                "The body is an unsecured JOSE object (alg none): it carries no signature");
            case JWEObject jwe -> throw check(SignatureProblemEnumDTO.ENCRYPTED,
                "The body is an encrypted JOSE object (alg %s), not a signed one".formatted(jwe.getHeader().getAlgorithm()));
            default -> throw check(SignatureProblemEnumDTO.NOT_JWS,
                "The body is a JOSE object that is not a JWS: %s".formatted(jose.getClass().getSimpleName()));
        };
    }

    private void validateCriticalHeaders(JWSHeader header) {
        Set<String> criticalHeaders = header.getCriticalParams();

        if (isNull(criticalHeaders) || criticalHeaders.isEmpty()) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "Missing required 'crit' parameter. X9.150 requires the JWS Protected Header to include the 'crit' parameter");
        }

        if (!criticalHeaders.contains(JWS_HEADER_CORRELATION_ID) ||
            !criticalHeaders.contains(JWS_HEADER_IAT) ||
            !criticalHeaders.contains(JWS_HEADER_TTL)) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "The 'crit' parameter must include at least: correlationId, iat, ttl");
        }

        for (String criticalHeader : criticalHeaders) {
            if (!SUPPORTED_CRITICAL_HEADERS.contains(criticalHeader)) {
                throw check(SignatureProblemEnumDTO.BAD_HEADER, "Unrecognized critical header parameter: %s".formatted(criticalHeader));
            }
        }

        log.debug("Critical headers validated: {}", criticalHeaders);
    }

    private UUID validateAndExtractCorrelationId(JWSHeader header) {
        Object correlationIdHeader = header.getCustomParam(JWS_HEADER_CORRELATION_ID);

        if (isNull(correlationIdHeader)) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "Missing required header parameter: correlationId");
        }

        String correlationId = correlationIdHeader.toString();

        log.debug("Validating correlationId: {}", correlationId);

        try {
            return UUID.fromString(correlationId);
        } catch (IllegalArgumentException e) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "Invalid correlationId format. Must be a valid UUID: %s".formatted(correlationId));
        }
    }

    private long validateAndExtractIat(JWSHeader header) {
        Object iatHeader = header.getCustomParam(JWS_HEADER_IAT);

        if (isNull(iatHeader)) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "Missing required header parameter: iat (issued-at)");
        }

        long iat;

        try {
            iat = (Long) iatHeader;
        } catch (ClassCastException e) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "Invalid iat format. Must be a Unix timestamp in milliseconds");
        }

        if (iat <= 0) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "Invalid iat value. Must be a positive Unix timestamp in milliseconds");
        }

        log.debug("Validated iat: {}", iat);

        return iat;
    }

    private long validateAndExtractTtl(JWSHeader header) {
        Object ttlHeader = header.getCustomParam(JWS_HEADER_TTL);

        if (isNull(ttlHeader)) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "Missing required header parameter: ttl (time-to-live)");
        }

        long ttl;

        try {
            ttl = (Long) ttlHeader;
        } catch (ClassCastException e) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "Invalid ttl format. Must be a positive integer in milliseconds");
        }

        if (ttl <= 0) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "Invalid ttl value. Must be a positive integer in milliseconds");
        }

        log.debug("Validated ttl: {} ms", ttl);

        return ttl;
    }

    private void validateStatusCodeIfResponse(JWSHeader header) {
        Object statusCodeHeader = header.getCustomParam(JWS_HEADER_STATUS_CODE);

        Set<String> criticalHeaders = header.getCriticalParams();

        if (nonNull(criticalHeaders) && criticalHeaders.contains(JWS_HEADER_STATUS_CODE)) {
            if (isNull(statusCodeHeader)) {
                throw check(SignatureProblemEnumDTO.BAD_HEADER, "Missing required header parameter: statusCode (required for responses)");
            }

            String statusCode = statusCodeHeader.toString();

            try {
                int code = Integer.parseInt(statusCode);
                HttpStatus httpStatus = HttpStatus.resolve(code);

                if (isNull(httpStatus)) {
                    throw check(SignatureProblemEnumDTO.BAD_HEADER, "Invalid statusCode. Must be a valid HTTP status code: %s".formatted(statusCode));
                }

                log.debug("Validated statusCode: {} ({})", statusCode, httpStatus.getReasonPhrase());
            } catch (NumberFormatException e) {
                throw check(SignatureProblemEnumDTO.BAD_HEADER, "Invalid statusCode format. Must be a numeric HTTP status code: %s".formatted(statusCode));
            }
        }
    }

    /**
     * Decodes the submitted QR content and checks it is self-consistent with the location asked for.
     *
     * <p>This is a cheap early rejection, not the binding. Both the location id and the content come
     * from the same caller, so passing it proves the body is coherent — not that the body is the
     * code this deployment issued. That comparison needs the stored entity and therefore happens in
     * {@code RetrieveQRCodePayloadUseCase}, via
     * {@code QRCodeEntity.requireIssuedQrCodeContent}.
     *
     * @return the decoded EMV string, so the caller can carry it to that comparison
     */
    private String validateLocationId(String locationId, Map<String, Object> payloadJson) {
        if (isBlank(locationId)) {
            return null;
        }

        if (!payloadJson.containsKey(JWS_PAYLOAD_QR_CODE_CONTENT)) {
            throw check(SignatureProblemEnumDTO.CONTENT_MISMATCH, "JWS payload JSON expected to have property qrCodeContent.");
        }

        String qrCodeContent = payloadJson.get(JWS_PAYLOAD_QR_CODE_CONTENT).toString();

        String decodedQrCodeContent = new Base64(qrCodeContent).decodeToString();

        String payloadUrl = qrCodeEMVService.extractPayloadUrl(decodedQrCodeContent);

        if (!Strings.CI.contains(payloadUrl, locationId)) {
            throw check(SignatureProblemEnumDTO.CONTENT_MISMATCH, "JWS payload URL does not contains ID %s".formatted(locationId));
        }

        return decodedQrCodeContent;
    }

    /**
     * The time window: {@code iat + ttl} must not have passed, and {@code iat} must not be ahead of
     * this verifier's clock by more than the allowed skew.
     *
     * <p>The second half is ours (owner decision, Story 2.3 follow-up): ANSI X9.150 defines {@code iat}
     * and {@code ttl} but no skew. A JWS dated ahead stretches its own window past what its signer's
     * real clock allowed, so it could be held back and replayed later. The skew,
     * {@code x9.public-endpoints.jws-clock-skew-seconds} (60 by default), forgives honest drift
     * between two clocks. The rule holds for everything this verifier checks, questions and answers
     * alike: this service's clock judges (INTERPRETATION.md I-13).
     */
    private void validateJwsExpiration(long iat, long ttl) {
        long currentTime = Instant.now(BusinessClock.clock()).toEpochMilli();
        long expirationTime = iat + ttl;
        long skewMillis = clockSkewMillis();

        if (iat > currentTime + skewMillis) {
            throw check(SignatureProblemEnumDTO.FUTURE_IAT,
                String.format("JWS iat is in the future beyond the allowed clock skew. Current time: %d, iat: %d, allowed skew: %d ms",
                    currentTime, iat, skewMillis));
        }

        if (currentTime >= expirationTime) {
            throw check(SignatureProblemEnumDTO.EXPIRED,
                String.format("JWS message has expired. Current time: %d, Expiration time (iat + ttl): %d", currentTime, expirationTime));
        }

        log.debug("Expiration check passed. Message valid until: {}", Instant.ofEpochMilli(expirationTime));
    }

    private long clockSkewMillis() {
        Long skewSeconds = x9Properties.getPublicEndpoints().getJwsClockSkewSeconds();

        return isNull(skewSeconds) ? DEFAULT_CLOCK_SKEW_MILLIS : Math.max(0L, skewSeconds) * SECONDS_TO_MILLIS_VALUE;
    }

    private void validateCertificateValidity(X509Certificate certificate) {
        try {
            certificate.checkValidity();

            log.debug("Certificate validity check passed. Valid until: {}", certificate.getNotAfter().toInstant());
        } catch (CertificateExpiredException e) {
            throw new SignatureCheckException(SignatureProblemEnumDTO.UNTRUSTED_CHAIN,
                "Certificate has expired. Expired at: %s".formatted(certificate.getNotAfter().toInstant()), e);
        } catch (CertificateNotYetValidException e) {
            throw new SignatureCheckException(SignatureProblemEnumDTO.UNTRUSTED_CHAIN,
                "Certificate is not yet valid. Valid from: %s".formatted(certificate.getNotBefore().toInstant()), e);
        }
    }

    private X509Certificate validateAndExtractCertificateByThumbprint(JWSHeader header, List<X509Certificate> externalCertificates) {
        Base64URL thumbprint = header.getX509CertSHA256Thumbprint();

        if (isNull(thumbprint)) {
            throw check(SignatureProblemEnumDTO.BAD_HEADER,
                "Missing required header parameter: x5t#S256 (certificate thumbprint)");
        }

        return externalCertificates
            .stream()
            .map(certificate -> getX509CertificateIfMatch(thumbprint, certificate))
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow(() -> check(SignatureProblemEnumDTO.UNTRUSTED_CHAIN,
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

        throw check(SignatureProblemEnumDTO.BAD_HEADER, "Unsupported signature algorithm: %s".formatted(algName));
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
            throw check(SignatureProblemEnumDTO.BAD_HEADER, "No certificate URL found in JWS header for signature validation.");
        }
    }

    public X509Certificate convertToX509(Base64 base64Certificate) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance(x9Properties.getCertificate().getCertificateSupportedType());

            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(base64Certificate.decode()));
        } catch (Exception e) {
            // Only reached for an x5c header: a certificate in the header that cannot be read.
            throw check(SignatureProblemEnumDTO.BAD_HEADER,
                "Could not convert to X509Certificate. Details: %s".formatted(e.getMessage()));
        }
    }

}
