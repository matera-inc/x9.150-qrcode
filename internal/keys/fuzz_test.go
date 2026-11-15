// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package keys

import (
	"testing"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
)

// A keystore or truststore file, whatever it holds, is read or refused; it never panics.
func FuzzReadStores(f *testing.F) {
	f.Add(bundledKeystore)
	f.Add(bundledTruststore)
	f.Add([]byte{0xFE, 0xED, 0xFE, 0xED})
	f.Add([]byte{0x30, 0x82, 0x01})
	f.Add([]byte("-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----\n"))
	f.Fuzz(func(t *testing.T, data []byte) {
		if key, certs, err := readKeystore(data, config.PrivateKeystore{}, SSLMatePKCS12{}); err == nil && (key == nil || len(certs) == 0) {
			t.Fatal("a keystore read without error has no key or no certificate")
		}
		if certs, err := readTruststore(data, config.Truststore{}, SSLMatePKCS12{}); err == nil && len(certs) == 0 {
			t.Fatal("a truststore read without error is empty")
		}
	})
}
