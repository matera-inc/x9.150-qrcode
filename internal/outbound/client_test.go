// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package outbound

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"
	"time"
)

// X9-DEC-511 (S11.4-AC1; ADR-0028, known difference 17, "no proxy"): the client that fetches a
// payee's payload for the decoder, and delivers a payer's notification, reads no proxy from the
// environment. The address came out of a QR Code or a request this service cannot vouch for; a
// proxy set for the rest of the host must not see where it goes, nor be able to answer for the payee.
//
// The payee's host is a name that is not loopback (Go never proxies loopback), resolved by the
// test's dialer to a local server: with a proxy read from the environment, the dialer would be
// asked for the proxy's address instead, and the proxy would receive the request.
func TestNoProxyIsReadFromTheEnvironment(t *testing.T) {
	proxied := 0
	var mu sync.Mutex
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		proxied++
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
	}))
	defer proxy.Close()
	payee := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		_, _ = io.WriteString(w, "from the payee")
	}))
	defer payee.Close()
	for _, name := range []string{"HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"} {
		t.Setenv(name, proxy.URL)
	}
	t.Setenv("NO_PROXY", "")
	t.Setenv("no_proxy", "")

	var dialed []string
	c := NewClient(Config{AnswerTimeout: 5 * time.Second,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			mu.Lock()
			dialed = append(dialed, addr)
			mu.Unlock()
			return (&net.Dialer{}).DialContext(ctx, network, payee.Listener.Addr().String())
		}})
	defer c.CloseIdleConnections()

	if c.transport.Proxy != nil {
		t.Fatal("the transport has a proxy function; it must read none")
	}
	endpoint, err := url.Parse("http://payee.example:8080/pub/api/v1/loc/A")
	if err != nil {
		t.Fatal(err)
	}
	status, body, err := c.Post(t.Context(), endpoint, http.Header{}, "{}")
	if err != nil {
		t.Fatal(err)
	}

	mu.Lock()
	defer mu.Unlock()
	if status != http.StatusOK || body != "from the payee" {
		t.Errorf("answer %d %q, want the payee's", status, body)
	}
	if len(dialed) != 1 || dialed[0] != "payee.example:8080" || proxied != 0 {
		t.Errorf("dialed %v, the proxy saw %d requests; want payee.example:8080 dialed directly", dialed, proxied)
	}
}
