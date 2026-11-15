// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package signature

import (
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/sha1" //nolint:gosec // x5t is the SHA-1 thumbprint by definition (RFC 7517 §4.8); nothing is secured by it
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"math/big"

	"github.com/matera-inc/x9.150-qrcode/internal/jws"
)

// JWKSetDocument is the JWK set the Java build publishes (RFC 7517 §5): one key.
type JWKSetDocument struct {
	Keys []JWK `json:"keys"`
}

// JWK is the signing key's public half as the Java build publishes it (Nimbus's JWK of the
// certificate, with Java's additions): the key's parameters, kid x9-key, the configured alg,
// use sig and key_ops [verify], both thumbprints, the chain in x5c, and the certificate's validity
// as nbf and exp (seconds). An RSA key carries n and e, an EC key crv, x and y.
type JWK struct {
	KTY     string   `json:"kty"`
	KID     string   `json:"kid"`
	Alg     string   `json:"alg"`
	Use     string   `json:"use"`
	KeyOps  []string `json:"key_ops"`
	X5T     string   `json:"x5t"`
	X5TS256 string   `json:"x5t#S256"`
	X5C     []string `json:"x5c"`
	NBF     int64    `json:"nbf"`
	Exp     int64    `json:"exp"`
	N       string   `json:"n,omitzero"`
	E       string   `json:"e,omitzero"`
	Crv     string   `json:"crv,omitzero"`
	X       string   `json:"x,omitzero"`
	Y       string   `json:"y,omitzero"`
}

// Identity is the signing identity a JWK is made of: *keys.Identity.
type Identity interface {
	Chain() []*x509.Certificate
}

var errNoCertificate = errors.New("signature: the signing identity has no certificate")

// JWKSet is the JWK set of id's certificate, published for algorithm alg (S10.2-AC2).
func JWKSet(id Identity, alg string) (JWKSetDocument, error) {
	chain := id.Chain()
	if len(chain) == 0 || chain[0] == nil {
		return JWKSetDocument{}, errNoCertificate
	}
	leaf := chain[0]
	sha1Sum := sha1.Sum(leaf.Raw) //nolint:gosec // x5t, as above
	sha256Sum := sha256.Sum256(leaf.Raw)
	key := JWK{
		KID:     jws.KeyID,
		Alg:     alg,
		Use:     "sig",
		KeyOps:  []string{"verify"},
		X5T:     b64url(sha1Sum[:]),
		X5TS256: b64url(sha256Sum[:]),
		X5C:     make([]string, len(chain)),
		NBF:     leaf.NotBefore.Unix(),
		Exp:     leaf.NotAfter.Unix(),
	}
	for i, c := range chain {
		key.X5C[i] = base64.StdEncoding.EncodeToString(c.Raw)
	}
	switch pub := leaf.PublicKey.(type) {
	case *rsa.PublicKey:
		key.KTY = "RSA"
		key.N = b64url(pub.N.Bytes())
		key.E = b64url(big.NewInt(int64(pub.E)).Bytes())
	case *ecdsa.PublicKey:
		point, err := pub.Bytes() // 0x04 || X || Y, each padded to the curve's size (RFC 7518 §6.2.1.2)
		if err != nil {
			return JWKSetDocument{}, fmt.Errorf("signature: the signing key: %w", err)
		}
		size := (len(point) - 1) / 2
		key.KTY = "EC"
		key.Crv = pub.Curve.Params().Name
		key.X = b64url(point[1 : 1+size])
		key.Y = b64url(point[1+size:])
	default:
		return JWKSetDocument{}, fmt.Errorf("signature: the signing key is %T, not RSA or EC", pub)
	}
	return JWKSetDocument{Keys: []JWK{key}}, nil
}

func b64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }
