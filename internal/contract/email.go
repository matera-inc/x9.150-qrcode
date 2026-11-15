// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"regexp"
	"strings"
)

// The Java build checks `format: email` with Hibernate Validator's @Email, which is deliberately
// loose: a@b passes it (and is then refused by the business rules, "'email' is invalid."). This is
// the same reading: a local part of atoms or quoted strings, at most 64 characters, and a domain of
// labels, or an address literal, at most 255 characters with no trailing dot.
const (
	localAtom         = "[a-z0-9!#$%&'*+/=?^_`{|}~\\x{0080}-\\x{FFFF}-]"
	localQuoted       = "(?:[a-z0-9!#$%&'*.(),<>\\[\\]:;  @+/=?^_`{|}~\\x{0080}-\\x{FFFF}-]|\\\\\\\\|\\\\\")"
	localWord         = "(?:" + localAtom + "+|\"" + localQuoted + "+\")"
	domainChar        = "[a-z\\x{0080}-\\x{FFFF}0-9!#$%&'*+/=?^_`{|}~]"
	domainLabel       = "(?:" + domainChar + "-*)*" + domainChar + "+"
	domainName        = domainLabel + "(?:\\." + domainLabel + ")*" // Java's possessive ++ adds nothing to a full match
	ipv4Literal       = "[0-9]{1,3}(?:\\.[0-9]{1,3}){3}"
	ipv6Literal       = "[0-9a-f:.]+"
	maxEmailLocalPart = 64
	maxEmailDomain    = 255
	maxDomainLabel    = 63
)

var (
	emailLocalPart = regexp.MustCompile(`(?i)\A` + localWord + `(?:\.` + localWord + `)*\z`)
	emailDomain    = regexp.MustCompile(`(?i)\A(?:` + domainName + `|\[` + ipv4Literal + `\]|\[IPv6:` + ipv6Literal + `\])\z`)
)

// wellFormedEmail is Hibernate Validator's @Email. The empty string passes, as it does there.
func wellFormedEmail(s string) bool {
	if s == "" {
		return true
	}
	before, after, ok := strings.CutLast(s, "@")
	if !ok {
		return false
	}
	local, domain := before, after
	if javaLength(local) > maxEmailLocalPart || !emailLocalPart.MatchString(local) {
		return false
	}
	if strings.HasSuffix(domain, ".") || !emailDomain.MatchString(domain) || len(domain) > maxEmailDomain {
		return false
	}
	if !strings.HasPrefix(domain, "[") {
		for label := range strings.SplitSeq(domain, ".") {
			if len(label) > maxDomainLabel {
				return false
			}
		}
	}
	return true
}
