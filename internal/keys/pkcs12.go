// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package keys

import (
	"crypto"
	"crypto/x509"
	"errors"
	"fmt"

	gopkcs12 "software.sslmate.com/src/go-pkcs12"
)

// SSLMatePKCS12 reads PKCS#12 with software.sslmate.com/src/go-pkcs12 (BSD-3-Clause, pure Go;
// approved by the owner, 2026-11-15, ADR-0027). It reads what keytool and OpenSSL 3 write today
// (PBES2 with AES, an HMAC-SHA-256 MAC) and the legacy 3DES and RC2 files.
type SSLMatePKCS12 struct{}

// DecodeKeystore reads a keystore of one private key and its certificates. A PKCS#12 file has one
// password, as keytool enforces, so a different private-key password is refused rather than tried.
// The library exposes no entry names, so alias is not checked: the file must hold exactly one
// private key, which is Java's rule without enforced-alias.
func (SSLMatePKCS12) DecodeKeystore(data []byte, password, keyPassword, _ string) (crypto.PrivateKey, []*x509.Certificate, error) {
	if keyPassword != password {
		return nil, nil, fmt.Errorf("%s: a PKCS#12 keystore has one password; "+
			"certificate.private-keystore.private-key-password (X9_CERTIFICATE_PRIVATEKEYSTORE_PRIVATEKEYPASSWORD) "+
			"must be empty or equal to the password", privateKeystoreName)
	}
	key, leaf, chain, err := gopkcs12.DecodeChain(data, password)
	if err != nil {
		return nil, nil, fmt.Errorf("%s: %w", privateKeystoreName, explain(err))
	}
	return key, append([]*x509.Certificate{leaf}, chain...), nil
}

// DecodeTruststore reads a truststore whose certificates Java marks as trusted (keytool
// -importcert writes that mark), as Java's PKCS#12 KeyStore reads only those as trusted entries.
func (SSLMatePKCS12) DecodeTruststore(data []byte, password string) ([]*x509.Certificate, error) {
	certs, err := gopkcs12.DecodeTrustStore(data, password)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", truststoreName, explain(err))
	}
	return certs, nil
}

func explain(err error) error {
	if errors.Is(err, gopkcs12.ErrIncorrectPassword) {
		return fmt.Errorf("the password does not open it: %w", err)
	}
	return err
}
