// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package decoder

import (
	"testing"

	"github.com/google/go-cmp/cmp"
)

// X9-DEC-516 (owner decision, 2026-11-16; ANSI X9.150-2026 §13.1): a bill another bank sent without
// paymentTiming is presented with "immediate" when it has no invoice.dueDate, and without
// paymentTiming when it has one (a deferred intent we do not guess); a paymentTiming that was sent
// is presented as written, whatever its value. The Java build's DecoderResponseMapperTest
// (X9-DEC-027) pins the same cases.
func TestAMissingPaymentTimingIsImmediateOnlyWithoutADueDate(t *testing.T) {
	const due = `"invoice":{"dueDate":"2030-02-01T00:00:00Z"}`
	for _, tc := range []struct{ name, in, want string }{
		{"absent, no invoice", `{"bill": {"description": "d"}}`,
			`{"bill":{"paymentTiming":"immediate","description":"d"}}`},
		{"null, an invoice without a dueDate", `{"bill": {"paymentTiming": null, "invoice": {"number": "I-1"}}}`,
			`{"bill":{"paymentTiming":"immediate","invoice":{"number":"I-1"}}}`},
		{"absent, a null dueDate", `{"bill": {"invoice": {"dueDate": null}}}`,
			`{"bill":{"paymentTiming":"immediate","invoice":{}}}`},
		{"absent, with a dueDate", `{"bill": {` + due + `}}`, `{"bill":{` + due + `}}`},
		{"not a string, with a dueDate", `{"bill": {"paymentTiming": 1, ` + due + `}}`, `{"bill":{` + due + `}}`},
		{"sent, kept as written", `{"bill": {"paymentTiming": "deferred", ` + due + `}}`,
			`{"bill":{"paymentTiming":"deferred",` + due + `}}`},
		{"no bill, nothing added", `{"id": "x"}`, `{"id":"x"}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := readForeign([]byte(tc.in))
			if err != nil {
				t.Fatal(err)
			}
			if diff := cmp.Diff(compact(t, tc.want), string(got)); diff != "" {
				t.Errorf("(-want +got):\n%s", diff)
			}
		})
	}
}
