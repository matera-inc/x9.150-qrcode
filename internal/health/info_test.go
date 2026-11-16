// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package health

import (
	"net/http"
	"testing"
)

// X9-OPS-006 (S14.1-AC3): /actuator/info answers the name, the version and the commit the binary
// was stamped with, in the shape of Spring Boot's build and git contributors, with the actuator's
// media type; nothing else.
func TestInfoAnswersTheVersionAndCommit(t *testing.T) {
	mux := http.NewServeMux()
	RegisterInfo(mux, "3.0.0", `abc"123`)
	code, body, contentType := get(t, mux, InfoPath, "")
	want := `{"build":{"name":"x9-qrcode","version":"3.0.0"},"git":{"commit":{"id":"abc\"123"}}}`
	if code != http.StatusOK || body != want || contentType != actuatorV3 {
		t.Fatalf("got %d %s %q, want 200 %s %q", code, body, contentType, want, actuatorV3)
	}
	if _, _, contentType := get(t, mux, InfoPath, "application/json"); contentType != "application/json" {
		t.Fatalf("Content-Type for application/json = %q", contentType)
	}
}
