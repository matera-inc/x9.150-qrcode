// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package decoder

import (
	"fmt"
	"net/url"
	"regexp"
	"strings"
)

// scheme is a URL that starts with one: scheme "://" (RFC 3986 §3.1). A location URL in an EMV
// string is written without one, and "host:8080/…" must not be read as the scheme "host".
var scheme = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9+.-]*://`)

// targets resolves a location URL to the URL fetched (S10.7-AC2, ADR-0027 "Certificate fetching";
// the Java build's DefaultQRCodeLocationService.normalizeHost):
//
//   - a URL whose host, port included, is this deployment's public host, ignoring case, is read
//     internally from the own base URL, keeping its path and query;
//   - any other URL is fetched over https from the host it names, whatever scheme was written, or
//     none. A URL that only mentions the public host (in its path, its query, its user
//     information, or as part of a longer host name) is another host's;
//   - user information and the fragment are dropped, so no credential is ever sent;
//   - a scheme other than http or https, or no host, is refused.
type targets struct {
	ownHost string
	ownBase *url.URL
}

func newTargets(ownHost, ownBaseURL string) (*targets, error) {
	base, err := url.Parse(ownBaseURL)
	if err != nil || (base.Scheme != "http" && base.Scheme != "https") || base.Host == "" {
		return nil, fmt.Errorf("decoder: the own base URL %q is not an absolute http(s) URL", ownBaseURL)
	}
	return &targets{ownHost: strings.TrimSpace(ownHost), ownBase: base}, nil
}

// resolve returns the URL to fetch for raw, or "" and why it cannot be fetched, in the Java build's words.
func (t *targets) resolve(raw string) (*url.URL, string) {
	written := strings.TrimSpace(raw)
	if !scheme.MatchString(written) {
		written = "https://" + written
	}
	u, err := url.Parse(written)
	if err != nil {
		return nil, "Invalid URI syntax: " + raw
	}
	s := strings.ToLower(u.Scheme)
	if s != "http" && s != "https" {
		return nil, "The scheme " + s + " is not http or https: " + raw
	}
	if u.Host == "" {
		return nil, "The URL has no host: " + raw
	}
	target := &url.URL{Scheme: "https", Host: u.Host, Path: u.Path, RawPath: u.RawPath, RawQuery: u.RawQuery}
	if t.ownHost != "" && strings.EqualFold(u.Host, t.ownHost) {
		target.Scheme, target.Host = t.ownBase.Scheme, t.ownBase.Host
		target.Path = strings.TrimSuffix(t.ownBase.Path, "/") + u.Path
		target.RawPath = ""
	}
	return target, ""
}
