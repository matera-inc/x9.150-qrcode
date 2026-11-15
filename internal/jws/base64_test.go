// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"encoding/base64"
	"testing"
)

// X9-LIFE-126: a payload request's qrCodeContent is read as Java's Nimbus Base64 reads it, the
// standard or the URL-safe alphabet, with or without padding; anything else is not base64.
func TestLenientBase64(t *testing.T) {
	raw := []byte("00020101021226>>??x9.example/pub/api/v1/loc/ABC")
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		got, err := lenientBase64(enc.EncodeToString(raw))
		if err != nil || string(got) != string(raw) {
			t.Errorf("%v: %q, %v", enc, got, err)
		}
	}
	if _, err := lenientBase64("not base64!"); err == nil {
		t.Error("a string that is not base64 decoded")
	}
}
