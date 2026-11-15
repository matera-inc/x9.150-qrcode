// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"slices"
	"unicode/utf16"
)

// javaGroupingOrder is the order in which Java iterates the keys of
// Collectors.groupingBy(…, counting()) over keys, a sequence that may repeat them. Only the
// duplicate-currency refusal needs it, to name the currency Java names when several are repeated.
//
// groupingBy fills a default java.util.HashMap with computeIfAbsent, which, at the start of every
// call, doubles the table (from 16) when the map already holds more than three quarters of it, and
// puts a new key at the head of its bucket. The map iterates bucket by bucket, (h ^ h>>>16) & (n−1)
// with h the key's String.hashCode, and within a bucket from the newest key to the oldest; a
// resize keeps that order.
func javaGroupingOrder(keys []string) []string {
	n, size := 16, 0
	var distinct []string
	for _, k := range keys {
		if size > n*3/4 {
			n *= 2
		}
		if !slices.Contains(distinct, k) {
			distinct = append(distinct, k)
			size++
		}
	}
	bucket := func(k string) int {
		h := javaStringHash(k)
		return int((h ^ (h >> 16)) & uint32(n-1)) //nolint:gosec // G115: n − 1 < 2^31, so the masked value fits an int.
	}
	slices.Reverse(distinct)
	slices.SortStableFunc(distinct, func(a, b string) int { return bucket(a) - bucket(b) })
	return distinct
}

// javaStringHash is java.lang.String.hashCode: s[0]·31^(n−1) + … + s[n−1] over UTF-16 code units,
// wrapping as a 32-bit integer. It is returned unsigned, so that >> is Java's >>>.
func javaStringHash(s string) uint32 {
	var h uint32
	for _, u := range utf16.Encode([]rune(s)) {
		h = 31*h + uint32(u)
	}
	return h
}
