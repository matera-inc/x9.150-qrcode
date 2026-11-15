// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package decoder

import (
	"encoding/json"
	"regexp"
	"strings"
	"unicode/utf16"
)

// A relayed reason is somebody else's text: the payload location comes out of the scanned QR Code,
// so the refusal was written by whichever host that code names. It is capped, so a hostile payee
// cannot use this service as a megaphone, and reduced to plain sentences (S10.7-AC3; the Java
// build's RestClientQRCodeExternalPayloadService).
const (
	maxRelayedViolations = 10
	// maxRelayedLength is counted in UTF-16 code units, as Java counts a String.
	maxRelayedLength = 300
	// refusedWithoutReason stands for a refusal that gave no reason this service relays.
	refusedWithoutReason = "The payee's service refused to release this payload, without giving a reason."
	linkRemoved          = "[link removed]"
)

var (
	// link is any scheme-ish or bare-domain run. Deliberately broad: a false positive costs a few
	// words. No legitimate reason a payload is unavailable needs a URL to say it, and a crafted QR
	// Code could otherwise have an address of its choosing printed in an error that carries this
	// service's name.
	link = regexp.MustCompile(`(?i)\b(?:[a-z][a-z0-9+.-]*://|www\.|[a-z0-9-]+\.(?:com|net|org|io|co|xyz|top|ru|cn)\b)\S*`)
	// control is what Java's \p{Cntrl} matches: a reason is a sentence, and a sentence that can
	// move the cursor or inject a line is being used for something other than reading.
	control = regexp.MustCompile(`[\x00-\x1f\x7f]`)
	// spaces is two or more of Java's \s.
	spaces = regexp.MustCompile(`[ \t\n\x0b\f\r]{2,}`)
)

// relayed is the payee's refusal reduced to plain strings: its violations (the first ten strings
// of the array, or the one string), else its detail, else the stand-in. A body that is not a JSON
// object, an HTML error page say, is not echoed at all.
func relayed(body string) []string {
	if strings.TrimSpace(body) == "" {
		return []string{refusedWithoutReason}
	}
	var problem map[string]json.RawMessage
	if err := json.Unmarshal([]byte(body), &problem); err != nil {
		return []string{refusedWithoutReason}
	}
	var violations []string
	var list []json.RawMessage
	var one string
	switch raw := problem["violations"]; {
	case json.Unmarshal(raw, &list) == nil && list != nil:
		for _, item := range list {
			var s string
			if len(violations) < maxRelayedViolations && json.Unmarshal(item, &s) == nil && isString(item) {
				violations = append(violations, sanitised(s))
			}
		}
	case isString(raw) && json.Unmarshal(raw, &one) == nil:
		violations = append(violations, sanitised(one))
	}
	var detail string
	if len(violations) == 0 && isString(problem["detail"]) && json.Unmarshal(problem["detail"], &detail) == nil {
		violations = append(violations, sanitised(detail))
	}
	if len(violations) == 0 {
		return []string{refusedWithoutReason}
	}
	return violations
}

// isString reports whether raw is a JSON string (json.Unmarshal into a string also accepts null).
func isString(raw json.RawMessage) bool {
	s := strings.TrimSpace(string(raw))
	return strings.HasPrefix(s, `"`)
}

// sanitised is one relayed reason, safe to render: links removed, control characters made spaces,
// runs of spaces made one, trimmed, and cut at 300 characters with an ellipsis.
func sanitised(violation string) string {
	s := link.ReplaceAllLiteralString(violation, linkRemoved)
	s = control.ReplaceAllLiteralString(s, " ")
	s = spaces.ReplaceAllLiteralString(s, " ")
	s = strings.TrimFunc(s, func(r rune) bool { return r <= ' ' })
	if s == "" {
		return refusedWithoutReason
	}
	units := 0
	for i, r := range s {
		units += utf16.RuneLen(r)
		if units > maxRelayedLength {
			return s[:i] + "…"
		}
	}
	return s
}
