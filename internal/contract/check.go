// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package contract holds the request bodies of openapi.yaml as Go types, read as the Java build's
// Jackson reads them, and the hand-written checks of every constraint the contract declares on
// them, refused as the Java build's Bean Validation refuses them: the field path Spring reports
// (creditor.address.city, paymentMethods[0].amount) and Bean Validation's default message (ADR-0028).
// No code is generated from the contract; a test reads openapi.yaml and fails when one of its
// request constraints has no check here.
//
// These are the contract's checks only. What the business rules refuse (an unsupported network, a
// currency outside supported-currencies.json, a date in the past) is decided after them.
package contract

import (
	"regexp"
	"strconv"
	"strings"
	"unicode/utf16"
)

// checker collects violations, each "<path>: <message>", in the order the schema declares them.
type checker struct {
	violations []string
}

func (c *checker) add(path, message string) {
	c.violations = append(c.violations, path+": "+message)
}

// result is the violations, or nil when there are none.
func (c *checker) result() []string {
	return c.violations
}

// closed refuses each member of unknown, found in the object at path, which the contract closes
// (additionalProperties: false; what this service generates is closed at every level, INTERPRETATION
// I-16). object is what the refusal calls it; v is the object, whose members the refusal lists in
// the order the contract declares them. The words are the Java build's (ClosedRequestFields):
//
//	creditor.address.x: is not a field of address, whose fields are line1, line2, city, state, postalCode and country
func (c *checker) closed(path, object string, unknown Members, v any) {
	if len(unknown) == 0 {
		return
	}
	fields := fieldsOf(v)
	message := "is not a field of " + object + ", whose only field is " + fields[0]
	if len(fields) > 1 {
		message = "is not a field of " + object + ", whose fields are " +
			strings.Join(fields[:len(fields)-1], ", ") + " and " + fields[len(fields)-1]
	}
	for _, name := range unknown.Names() {
		if path != "" {
			name = path + "." + name
		}
		c.add(name, message)
	}
}

// Bean Validation's default messages.
const (
	msgNotNull = "must not be null"
	msgEmail   = "must be a well-formed email address"
)

func msgSize(lo, hi int) string {
	return "size must be between " + strconv.Itoa(lo) + " and " + strconv.Itoa(hi)
}

// unbounded is @Size's default max.
const unbounded = 2147483647

// notNull is @NotNull.
func (c *checker) notNull(path string, p presence) bool {
	if p.IsNull() {
		c.add(path, msgNotNull)
		return false
	}
	return true
}

// notExplicitNull is @NotNull on a member that has a default (paymentTiming, tip.allowed): absent
// takes the default, so only an explicit null is refused.
func (c *checker) notExplicitNull(path string, p presence) {
	if p.Present && p.Null {
		c.add(path, msgNotNull)
	}
}

// stringRule is one constraint on a string member.
type stringRule func(c *checker, path, value string)

// size is @Size(min, max) on a string, counted in UTF-16 units as Java counts.
func size(lo, hi int) stringRule {
	return func(c *checker, path, value string) {
		if n := javaLength(value); n < lo || n > hi {
			c.add(path, msgSize(lo, hi))
		}
	}
}

// maxLength is @Size(max = n).
func maxLength(n int) stringRule { return size(0, n) }

// pattern is @Pattern(regexp = source), matched against the whole value.
func pattern(source string) stringRule {
	re := regexp.MustCompile(`\A(?:` + strings.TrimSuffix(strings.TrimPrefix(source, "^"), "$") + `)\z`)
	message := `must match "` + source + `"`
	return func(c *checker, path, value string) {
		if !re.MatchString(value) {
			c.add(path, message)
		}
	}
}

// email is @Email, Hibernate Validator's reading of an address.
func email(c *checker, path, value string) {
	if !wellFormedEmail(value) {
		c.add(path, msgEmail)
	}
}

// str applies rules to a string member that holds a value; a null one passes, as in Bean Validation.
func (c *checker) str(path string, s String, rules ...stringRule) {
	if !s.IsSet() {
		return
	}
	for _, rule := range rules {
		rule(c, path, s.Value)
	}
}

// maxLong and minLong are @Max and @Min on a Long.
func (c *checker) maxLong(path string, l Long, bound int64) {
	if l.IsSet() && l.Value > bound {
		c.add(path, "must be less than or equal to "+strconv.FormatInt(bound, 10))
	}
}

func (c *checker) rangeInt(path string, i Integer, lo, hi int32) {
	if !i.IsSet() {
		return
	}
	if i.Value < lo {
		c.add(path, "must be greater than or equal to "+strconv.Itoa(int(lo)))
	}
	if i.Value > hi {
		c.add(path, "must be less than or equal to "+strconv.Itoa(int(hi)))
	}
}

func (c *checker) minInt(path string, i Integer, lo int32) {
	if i.IsSet() && i.Value < lo {
		c.add(path, "must be greater than or equal to "+strconv.Itoa(int(lo)))
	}
}

// listSize is @Size on a list that holds a value.
func listSize[T any](c *checker, path string, l List[T], lo, hi int) {
	if l.IsSet() && (len(l.Items) < lo || len(l.Items) > hi) {
		c.add(path, msgSize(lo, hi))
	}
}

// item is the path of a list's i-th item, as Spring writes it.
func item(path string, i int) string { return path + "[" + strconv.Itoa(i) + "]" }

// javaLength is Java's String.length: UTF-16 units.
func javaLength(s string) int {
	n := 0
	for _, r := range s {
		n += utf16.RuneLen(r)
	}
	return n
}

// The patterns of openapi.yaml, as Java's annotations carry them (and so as its messages print them).
const (
	patPrintable      = `^[\x20-\x7E]*$`
	patPrintable1     = `^[\x20-\x7E]+$`
	patName           = `^[\x20-\x7E]{1,50}$`
	patPhone          = `^(\+1[2-9]\d{2}[2-9]\d{6}|\+\d{1,3}\d{4,14})$`
	patAddressLine    = `^[\x20-\x7E]{1,60}$`
	patCity           = `^[\x20-\x7E]{1,40}$`
	patState          = `^[\x20-\x7E]{0,30}$`
	patPostalCode     = `^(\d{5}(-\d{4})?|[\x20-\x7E]{0,10})$`
	patCountry        = `^[A-Z]{2}$`
	patMCC            = `^\d{4}$`
	patDocumentNumber = `^[\x20-\x7E]{0,20}$`
	patCurrency       = `^[A-Za-z]{1,32}$`
	patRoutingNumber  = `^\d{9}$`
	patAccountNumber  = `^[0-9A-Za-z]{4,17}$`
	patBase58Address  = `^[1-9A-HJ-NP-Za-km-z]{32,44}$`
)

// maxAmount is the Amount schema's maximum: 18 digits (ADR-0005).
const maxAmount = 999999999999999999

var (
	rulePrintable      = pattern(patPrintable)
	rulePrintable1     = pattern(patPrintable1)
	ruleName           = pattern(patName)
	rulePhone          = pattern(patPhone)
	ruleAddressLine    = pattern(patAddressLine)
	ruleCity           = pattern(patCity)
	ruleState          = pattern(patState)
	rulePostalCode     = pattern(patPostalCode)
	ruleCountry        = pattern(patCountry)
	ruleMCC            = pattern(patMCC)
	ruleDocumentNumber = pattern(patDocumentNumber)
	ruleCurrency       = pattern(patCurrency)
	ruleRoutingNumber  = pattern(patRoutingNumber)
	ruleAccountNumber  = pattern(patAccountNumber)
	ruleBase58Address  = pattern(patBase58Address)
)
