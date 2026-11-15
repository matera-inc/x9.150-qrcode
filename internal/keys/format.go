// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package keys

import (
	"bytes"
	"crypto"
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/x509"
	"encoding/binary"
	"encoding/pem"
	"fmt"
	"strconv"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
)

const (
	privateKeystoreName = "certificate.private-keystore.location (X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION)"
	truststoreName      = "certificate.truststore.location (X9_CERTIFICATE_TRUSTSTORE_LOCATION)"
)

// PKCS12 decodes a PKCS#12 file. SSLMatePKCS12 is the one this build uses; the interface keeps the
// library at one place, and lets a test stand in for it.
type PKCS12 interface {
	// DecodeKeystore returns the private key and every certificate of the file. alias is Java's
	// enforced-alias; a decoder that cannot select an entry by name requires exactly one key.
	DecodeKeystore(data []byte, password, keyPassword, alias string) (crypto.PrivateKey, []*x509.Certificate, error)
	// DecodeTruststore returns the file's trusted certificates.
	DecodeTruststore(data []byte, password string) ([]*x509.Certificate, error)
}

type format int

const (
	formatUnknown format = iota
	formatPEM
	formatPKCS12
	formatJKS
)

// detect reads the file's content, not its configured type: Java's default type "JKS" reads PKCS#12
// files too, so the type an operator configured says little.
func detect(data []byte) format {
	switch {
	case len(data) >= 4 && (binary.BigEndian.Uint32(data) == 0xFEEDFEED || binary.BigEndian.Uint32(data) == 0xCECECECE):
		return formatJKS // JKS, or JCEKS
	case len(data) > 0 && data[0] == 0x30:
		return formatPKCS12 // a DER SEQUENCE: PFX
	case bytes.Contains(data, []byte("-----BEGIN ")):
		return formatPEM // possibly after openssl's "Bag Attributes" lines
	default:
		return formatUnknown
	}
}

func refuseFormat(f format, name string) error {
	switch f {
	case formatJKS:
		return fmt.Errorf("%w: %s is a JKS keystore, which the Go build does not read: convert it to PKCS#12 with "+
			"x9-config-convert, or by hand with `keytool -importkeystore -srcstoretype JKS -deststoretype PKCS12` "+
			"(ADR-0031)", ErrUnsupportedFormat, name)
	case formatUnknown:
		return fmt.Errorf("%w: %s is neither PEM nor PKCS#12", ErrUnsupportedFormat, name)
	case formatPEM, formatPKCS12:
	}
	return nil
}

// readKeystore returns the one private key of the file, which must be RSA or EC, and its
// certificates.
func readKeystore(data []byte, cfg config.PrivateKeystore, p PKCS12) (crypto.Signer, []*x509.Certificate, error) {
	var (
		key   crypto.PrivateKey
		certs []*x509.Certificate
		err   error
	)
	switch f := detect(data); f {
	case formatPEM:
		key, certs, err = readPEMKeystore(data)
	case formatPKCS12:
		key, certs, err = p.DecodeKeystore(data, cfg.Password, keyPassword(cfg), cfg.EnforcedAlias)
	case formatJKS, formatUnknown:
		err = refuseFormat(f, privateKeystoreName)
	}
	if err != nil {
		return nil, nil, err
	}
	switch key.(type) {
	case *rsa.PrivateKey, *ecdsa.PrivateKey:
	default:
		return nil, nil, fmt.Errorf("%s: the private key is %T; it must be RSA or EC", privateKeystoreName, key)
	}
	signer, ok := key.(crypto.Signer)
	if !ok {
		return nil, nil, fmt.Errorf("%s: the private key %T cannot sign", privateKeystoreName, key)
	}
	if len(certs) == 0 {
		return nil, nil, fmt.Errorf("%s: holds no certificate", privateKeystoreName)
	}
	return signer, certs, nil
}

// keyPassword is Java's private-key-password, which defaults to the store's password.
func keyPassword(cfg config.PrivateKeystore) string {
	if cfg.PrivateKeyPassword != "" {
		return cfg.PrivateKeyPassword
	}
	return cfg.Password
}

func readTruststore(data []byte, cfg config.Truststore, p PKCS12) ([]*x509.Certificate, error) {
	var (
		certs []*x509.Certificate
		err   error
	)
	switch f := detect(data); f {
	case formatPEM:
		certs, err = readPEMTruststore(data)
	case formatPKCS12:
		certs, err = p.DecodeTruststore(data, cfg.Password)
	case formatJKS, formatUnknown:
		err = refuseFormat(f, truststoreName)
	}
	if err != nil {
		return nil, err
	}
	if len(certs) == 0 {
		return nil, fmt.Errorf("%s: holds no certificate", truststoreName)
	}
	return certs, nil
}

// readPEMKeystore reads one private key, unencrypted (PKCS#8, PKCS#1 or SEC 1), and certificates,
// in any order.
func readPEMKeystore(data []byte) (crypto.PrivateKey, []*x509.Certificate, error) {
	var (
		keys  []crypto.PrivateKey
		certs []*x509.Certificate
	)
	for i := 1; ; i++ {
		var block *pem.Block
		block, data = pem.Decode(data)
		if block == nil {
			break
		}
		at := func(err error) error {
			return fmt.Errorf("%s: PEM block %d (%s): %w", privateKeystoreName, i, block.Type, err)
		}
		if _, encrypted := block.Headers["Proc-Type"]; encrypted || block.Type == "ENCRYPTED PRIVATE KEY" {
			return nil, nil, at(errEncryptedPEM)
		}
		switch block.Type {
		case "CERTIFICATE":
			c, err := x509.ParseCertificate(block.Bytes)
			if err != nil {
				return nil, nil, at(err)
			}
			certs = append(certs, c)
		case "PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY":
			k, err := parsePrivateKey(block)
			if err != nil {
				return nil, nil, at(err)
			}
			keys = append(keys, k)
		case "EC PARAMETERS":
			// openssl writes the curve's name before an EC key; the key carries it too.
		default:
			return nil, nil, at(errUnexpectedBlock)
		}
	}
	switch len(keys) {
	case 0:
		return nil, nil, fmt.Errorf("%s: holds no private key", privateKeystoreName)
	case 1:
		return keys[0], certs, nil
	default:
		return nil, nil, fmt.Errorf("%s: holds %s private keys, but it can hold only the signing key",
			privateKeystoreName, strconv.Itoa(len(keys)))
	}
}

func parsePrivateKey(block *pem.Block) (crypto.PrivateKey, error) {
	switch block.Type {
	case "RSA PRIVATE KEY":
		return x509.ParsePKCS1PrivateKey(block.Bytes)
	case "EC PRIVATE KEY":
		return x509.ParseECPrivateKey(block.Bytes)
	default:
		return x509.ParsePKCS8PrivateKey(block.Bytes)
	}
}

func readPEMTruststore(data []byte) ([]*x509.Certificate, error) {
	var certs []*x509.Certificate
	for i := 1; ; i++ {
		var block *pem.Block
		block, data = pem.Decode(data)
		if block == nil {
			return certs, nil
		}
		if block.Type != "CERTIFICATE" {
			return nil, fmt.Errorf("%s: PEM block %d is a %s; a truststore holds certificates only",
				truststoreName, i, block.Type)
		}
		c, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil, fmt.Errorf("%s: PEM block %d: %w", truststoreName, i, err)
		}
		certs = append(certs, c)
	}
}

var (
	errEncryptedPEM = fmt.Errorf("%w: an encrypted PEM key cannot be read (the standard library has no PKCS#8 "+
		"decryption); mount the key unencrypted, as a secret file, or as PKCS#12", ErrUnsupportedFormat)
	errUnexpectedBlock = fmt.Errorf("%w: a keystore holds one private key and certificates only", ErrUnsupportedFormat)
)

func keyType(k crypto.Signer) string {
	switch pub := k.Public().(type) {
	case *rsa.PublicKey:
		return "RSA-" + strconv.Itoa(pub.N.BitLen())
	case *ecdsa.PublicKey:
		return "EC-" + pub.Curve.Params().Name
	default:
		return fmt.Sprintf("%T", pub)
	}
}
