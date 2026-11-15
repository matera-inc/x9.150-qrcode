// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"
)

// S10.2-AC1: the service starts with one signer and one verifier; a JWS it signs names its JWK set
// on the public host (jku), and its verifier reads that set from this process, as the Java build
// reads its own host, so what it generates it validates. No database is needed for either.
func TestRunSignsAndVerifiesThroughItsOwnJWKSet(t *testing.T) {
	port := freePort(t.Context(), t)
	env := []string{
		"DATABASE_HOST=127.0.0.1", "DATABASE_PORT=1", "DATABASE_NAME=bank", "DATABASE_USER=app", "DATABASE_PASSWORD=pw",
		"DATABASE_SSLMODE=disable", "X9_SERVER_PORT=" + strconv.Itoa(port), "X9_SERVER_SHUTDOWNDELAYSECONDS=0",
	}
	ctx, cancel := context.WithCancel(t.Context())
	var out syncBuffer
	done := make(chan error, 1)
	go func() { done <- run(ctx, nil, env, &out) }()
	defer func() {
		cancel()
		if err := <-done; err != nil {
			t.Errorf("run returned %v after SIGTERM", err)
		}
	}()

	base := "http://127.0.0.1:" + strconv.Itoa(port)
	client := &http.Client{Timeout: 5 * time.Second}
	post := func(path string, header map[string]string, body string) (int, http.Header, string, error) {
		req, err := http.NewRequestWithContext(t.Context(), http.MethodPost, base+path, strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Content-Type", "application/json")
		for k, v := range header {
			req.Header.Set(k, v)
		}
		resp, err := client.Do(req)
		if err != nil {
			return 0, nil, "", err
		}
		defer func() { _ = resp.Body.Close() }()
		b, err := io.ReadAll(resp.Body)
		return resp.StatusCode, resp.Header, string(b), err
	}

	const cid = "8a652794-9e73-4fd7-a628-4d525b638cbc"
	var status int
	var token string
	var err error
	for range 100 {
		if status, _, token, err = post("/api/v1/signature/generate",
			map[string]string{"Correlation-Id": cid, "TTL-Seconds": "60"}, `{"qrCodeContent":"x"}`); err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err != nil || status != http.StatusOK {
		t.Fatalf("generate: %d %v %s", status, err, token)
	}
	body, _ := json.Marshal(map[string]string{"content": token})
	status, header, answer, err := post("/api/v1/signature/validate", nil, string(body))
	if err != nil || status != http.StatusOK || header.Get("Correlation-Id") != cid {
		t.Fatalf("validate: %d %v %s\n%s", status, err, answer, out.String())
	}
}
