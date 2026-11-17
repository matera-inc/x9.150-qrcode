// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package testclock

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
)

// bundledFingerprints are the SHA-256 of the DER of every certificate whose private key is
// committed to this repository: a key anyone can read is a test key, whatever its certificate says
// (ADR-0033, note of 2026-11-14). They are compiled in, so no subject name or configuration value
// can satisfy the guard. Compare with
//
//	openssl pkcs12 -in <file> -passin pass:x9test123 -nokeys -clcerts | openssl x509 -outform DER | sha256sum
//
// The list was the Java build's (TestClockCertificateGuard), entry for entry; TestTheTestPKIIsBundled
// keeps it equal to the certificates committed under testdata/pki.
var bundledFingerprints = map[string]string{
	// The demo identity: x9-demo.jks, bundled byte for byte as internal/keys/bundled/x9-demo.p12.
	"67e9eae59581e99c81d3744fe086042f7363e13c2d15e05113011d2d76e5ed1b": "x9-demo (CN=x9.matera.com, OU=Demo NonProduction)",
	// testdata/pki/:
	"795ff346e9689636e9ba68917fe9f12db9e5e3a43a0b9fe2a39026275859292a": "x9-test-ca.p12, the test root CA",
	"e08b164ebd84a0a03701d20071bfdd4d7cd8f3185958201c577e6015d51e671a": "x9-test-payer.p12, RSA leaf of the test CA",
	"19241b109b737421705d65e7d6cfc2fca7bc9425f0f263fe04643daa70bcf518": "x9-test-payer-ec.p12, EC leaf of the test CA",
	"f449774d768f3324ac23b99ce858663073fe455041494754075151e3b437f875": "x9-test-payer-revoked.p12, leaf listed in the test CA's CRL",
	"e95c47df5b6489ebfd1507e8b569369d92771a07d905a1865c0e653966fc676d": "x9-test-untrusted-ca.p12, a root outside the test truststore",
	"c9e0987d26423d15eb173ea9f40e06f64a6b8434a70c781fea774603d839ea85": "x9-test-untrusted-payer.p12, leaf of the untrusted root",
	"f38f6315e0b4292e4b18ef7ab41275d67376c231102cf2f90a9c93f2c0b255f3": "x9-test-payer-no-revocation.p12, leaf naming no CRL or OCSP",
	"8f8313bb8f5e244ea5da488d494bfa36c526fc1026d7c587da8dad11858382c9": "x9-test-payer-crl-unreachable.p12, leaf with an unreachable CRL",
	"c8a9cbc56445d06186396fe4f06487a125dc40b1dc626168c1edd4f320be1afd": "x9-test-payer-ocsp-unreachable.p12, leaf with an unreachable OCSP responder",
	"a08335e6d2da20d12050cc50bec23bd866aec5e961ec37890bb4ed7bf4bcfce1": "x9-test-payer-both-unreachable.p12, leaf with both unreachable",
	"932511c2e41a398f833082dc382397214c56190821a07d727c6f18e54f2c1987": "x9-test-payer-crl-unreadable.p12, leaf whose CRL is not a CRL",
	"33f87ee4a486580f29b3206e26485c23416944a9efa1c5290a6398674383a8a6": "x9-test-payer-crl-unreadable-payload.p12, the same, for the payload endpoint",
	"2367e5f868d04bfc6c95c8e7ed47b894add82c20d1e11bf88c49ae3aa6e7c9ab": "x9-test-payer-crl-unreadable-ocsp-unreachable.p12, that CRL plus an unreachable OCSP responder",
	"62c5a14884abcf0c7af25e335b669b62966ecac688bc01951a7f4c89adc469cd": "x9-test-payer-crl-unreadable-ocsp-unreachable-payload.p12, the same, for the payload endpoint",
	"8a93437dd246941e1cf6ad761808b87188fb69d52bd4caecc649e2b34bdc73cf": "x9-test-ca-digital-signature.pem, the test CA re-issued with digitalSignature (x9-test-ca.p12's key)",
}

// Fingerprint is the SHA-256 of cert's DER, in lowercase hex.
func Fingerprint(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.Raw)
	return hex.EncodeToString(sum[:])
}

// IsBundled reports whether fingerprint (lowercase hex SHA-256 of a DER certificate) is one of a
// certificate bundled with this repository.
func IsBundled(fingerprint string) bool {
	_, ok := bundledFingerprints[fingerprint]
	return ok
}

// RequireBundled refuses the test clock unless cert is one bundled with this repository. Its
// message is the Java build's.
func RequireBundled(cert *x509.Certificate) error {
	if cert == nil {
		return errors.New("X9_TESTING_CLOCKCONTROL is on, but this deployment has no signing certificate to check")
	}
	fp := Fingerprint(cert)
	if IsBundled(fp) {
		return nil
	}
	return fmt.Errorf("X9_TESTING_CLOCKCONTROL is on, but the signing certificate (%s, SHA-256 %s) is not a bundled "+
		"certificate (one whose private key ships with this repository). A deployment with real keys cannot move its "+
		"clock: turn X9_TESTING_CLOCKCONTROL off", cert.Subject.String(), fp)
}
