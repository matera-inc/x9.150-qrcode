// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"strconv"
	"strings"
	"testing"
	"time"
)

const recipient = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

// due is the rule tests' invoice due date: ten days from now, well inside valid()'s thirty.
var due = now.Add(10 * day)

// S9.4-AC1, AC5: every rule refuses what Java refuses, naming the same field with Java's message,
// word for word, and the same kind of refusal.
func TestCheckCreate(t *testing.T) {
	cases := []struct {
		name   string
		change func(r *Request)
		want   string
		kind   Kind
	}{
		{"a valid request", func(*Request) {}, "", 0},

		// Networks (I-1, I-2, I-9; ADR-0010, ADR-0012; X9-RAIL-420).
		{"an unsupported network, by name", func(r *Request) {
			r.PaymentMethods[0].Networks.Other = []string{"tron"}
		}, "paymentMethods.networks: Unsupported network(s): tron. This service supports fednow, rtp, ach and solana.", BusinessRule},
		{"every unsupported network beside a supported one", func(r *Request) {
			r.PaymentMethods[0].Networks = Networks{Solana: &SolanaAddress{Recipient: recipient}, Other: []string{"tron", "zelle"}}
		}, "paymentMethods.networks: Unsupported network(s): tron, zelle. This service supports fednow, rtp, ach and solana.", BusinessRule},
		{"a rail in another case", func(r *Request) {
			r.PaymentMethods[0].Networks = Networks{Other: []string{"FedNow"}}
		}, "paymentMethods.networks: Unsupported network(s): FedNow. This service supports fednow, rtp, ach and solana.", BusinessRule},
		{"a network is refused before any value is read", func(r *Request) {
			r.Bill.AmountDue.Amount = -1
			r.PaymentMethods[0].Networks.Other = []string{"tron"}
		}, "paymentMethods.networks: Unsupported network(s): tron. This service supports fednow, rtp, ach and solana.", BusinessRule},
		{"no network at all", func(r *Request) {
			r.PaymentMethods[0].Networks = Networks{}
		}, "At least one network must be provided.", Invalid},

		// Amounts (ADR-0005; X9-AMT-420, -421).
		{"a negative bill", func(r *Request) { r.Bill.AmountDue.Amount = -1 }, "monetary values should not be negative.", Invalid},
		{"a zero bill", func(r *Request) { r.Bill.AmountDue.Amount = 0 }, "Amount due currency amount value must be greater than zero", Invalid},
		{"a 19-digit bill", func(r *Request) { r.Bill.AmountDue.Amount = MaxAmount + 1 }, msgTooManyDigits, Invalid},
		{"a negative method", func(r *Request) { r.PaymentMethods[0].Amount = -1 }, "monetary values should not be negative.", Invalid},
		{"a 19-digit method", func(r *Request) { r.PaymentMethods[0].Amount = MaxAmount + 1 }, msgTooManyDigits, Invalid},
		{"18 nines", func(r *Request) {
			r.Bill.AmountDue.Amount = eighteenNines
			r.PaymentMethods[0].Amount = eighteenNines
		}, "", 0},
		{"a 19-digit editable maximum", func(r *Request) {
			r.PaymentMethods[0].Editable = &Editable{Range: &AmountRange{Min: 1, Max: MaxAmount + 1}}
		}, msgTooManyDigits, Invalid},
		{"a negative editable minimum", func(r *Request) {
			r.PaymentMethods[0].Editable = &Editable{Range: &AmountRange{Min: -1, Max: 1}}
		}, "monetary values should not be negative.", Invalid},
		{"an editable range upside down", func(r *Request) {
			r.PaymentMethods[0].Editable = &Editable{Range: &AmountRange{Min: 2, Max: 1}}
		}, "Minimum amount cannot be greater than maximum amount.", Invalid},
		{"an editable amount without a range", func(r *Request) {
			r.PaymentMethods[0].Editable = &Editable{}
		}, "Editable amount range is required when the amount is editable.", Invalid},
		{"a 19-digit discount", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0, Discount{DaysBefore: 1, Discount: MaxAmount + 1, Explanation: "x"})
		}, msgTooManyDigits, Invalid},
		{"a 19-digit fixed fee", func(r *Request) { *r = withInvoice(*r, due, MaxAmount+1, 0) }, msgTooManyDigits, Invalid},
		{"a 19-digit daily fee", func(r *Request) { *r = withInvoice(*r, due, 0, MaxAmount+1) }, msgTooManyDigits, Invalid},
		{"a negative fixed fee", func(r *Request) { *r = withInvoice(*r, due, -1, 0) },
			"bill.amountDue.adjustments.parameters.lateFees.fixed: must be greater than or equal to 0", BusinessRule},
		{"a negative daily fee", func(r *Request) { *r = withInvoice(*r, due, 0, -1) },
			"bill.amountDue.adjustments.parameters.lateFees.perDay: must be greater than or equal to 0", BusinessRule},
		{"zero fees", func(r *Request) { *r = withInvoice(*r, due, 0, 0) }, "", 0},
		{"a late fee without an explanation", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0)
			r.Bill.AmountDue.Adjustment.LateFees.Explanation = " "
		}, "Late fee explanation must not be null or blank.", Invalid},
		{"no late fees", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0)
			r.Bill.AmountDue.Adjustment.LateFees = nil
		}, "Adjustment parameters late fees must not be null", Invalid},
		{"another formula", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0)
			r.Bill.AmountDue.Adjustment.Formula = "Compound"
		}, "Unexpected value 'Compound'", Invalid},

		// Discounts.
		{"a zero discount", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0, Discount{DaysBefore: 1, Discount: 0, Explanation: "x"})
		}, "Discount value must be greater than 0.", Invalid},
		{"a discount zero days before", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0, Discount{DaysBefore: 0, Discount: 1, Explanation: "x"})
		}, "Days before discount must be greater than 0.", Invalid},
		{"a discount without an explanation", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0, Discount{DaysBefore: 1, Discount: 1})
		}, "Discount explanation must not be null or blank.", Invalid},
		{"a discount as large as the bill", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0, Discount{DaysBefore: 1, Discount: 1, Explanation: "x"},
				Discount{DaysBefore: 2, Discount: 10_000, Explanation: "y"})
		}, "Bill discount at index 1. Must not be greater than or equal to amount due.", Invalid},
		{"a discount whose edge has passed", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0, Discount{DaysBefore: 1, Discount: 1, Explanation: "x"},
				Discount{DaysBefore: 11, Discount: 2, Explanation: "y"})
		}, "bill.amountDue.adjustments.discounts[1]: target date must be after the current date.", BusinessRule},
		{"a discount whose edge is now", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0, Discount{DaysBefore: 10, Discount: 1, Explanation: "x"})
		}, "", 0},

		// The bill.
		{"an adjustment without an invoice", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0)
			r.Bill.Invoice = nil
		}, "Bill amount due adjustment can only be informed when invoice is not null.", Invalid},
		{"deferred without an invoice", func(r *Request) { r.Bill.PaymentTiming = "DEFERRED" },
			"Bill invoice must be informed when DEFERRED payment timing.", Invalid},
		{"deferred with an invoice", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0)
			r.Bill.PaymentTiming = "deferred"
		}, "", 0},
		{"another payment timing", func(r *Request) { r.Bill.PaymentTiming = "later" }, "Unexpected value 'later'", Invalid},
		{"a due date before the invoice's date", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0)
			r.Bill.Invoice.Date = due.Add(day)
		}, "Invoice dueDate must be after or equal to creation date.", Invalid},
		{"a due date on the invoice's day, in its own offset", func(r *Request) {
			*r = withInvoice(*r, time.Date(2030, 1, 20, 1, 0, 0, 0, time.FixedZone("", 3*60*60)), 0, 0)
			r.Bill.Invoice.Date = time.Date(2030, 1, 20, 0, 0, 0, 0, time.UTC)
		}, "", 0},
		{"a bill currency that is not a code", func(r *Request) { r.Bill.AmountDue.Currency = "US$" },
			"Currency must be a 1-32 character alphabetic code.", Invalid},

		// Payment methods.
		{"a method without a currency: no default, not even the bill's (X9-CUR-421)", func(r *Request) {
			r.PaymentMethods[0].Currency = ""
		}, "paymentMethods[0].currency: must not be null", Invalid},
		{"no payment method", func(r *Request) { r.PaymentMethods = nil }, "paymentMethods: must not be null or empty.", BusinessRule},
		{"a duplicate currency (X9-CUR-423)", func(r *Request) {
			r.PaymentMethods = append(r.PaymentMethods, r.PaymentMethods[0])
			r.PaymentMethods[1].Networks = Networks{RTP: fednow()}
		}, "paymentMethods: must not contain duplicated currency: USD", BusinessRule},
		{"two cases of a currency are not a duplicate", func(r *Request) {
			r.PaymentMethods = append(r.PaymentMethods, r.PaymentMethods[0])
			r.PaymentMethods[1].Currency = "usd"
		}, `currency: Currency "usd" must be written as "USD". This service accepts one spelling on its own API — the one it emits.`, BusinessRule},

		// Bank accounts (I-6) and Solana (I-8).
		{"a routing number of 8 digits", func(r *Request) { r.PaymentMethods[0].Networks.FedNow.RoutingNumber = "02100002" },
			"RoutingNumber is invalid.", Invalid},
		{"a blank routing number", func(r *Request) { r.PaymentMethods[0].Networks.FedNow.RoutingNumber = "" },
			"RoutingNumber must not be null or blank.", Invalid},
		{"a blank account number", func(r *Request) { r.PaymentMethods[0].Networks.FedNow.AccountNumber = " " },
			"AccountNumber must not be null or blank.", Invalid},
		{"an account number of 3 characters", func(r *Request) { r.PaymentMethods[0].Networks.FedNow.AccountNumber = "123" },
			"AccountNumber is invalid.", Invalid},
		{"an account number of 18 characters", func(r *Request) {
			r.PaymentMethods[0].Networks.FedNow.AccountNumber = strings.Repeat("1", 18)
		}, "AccountNumber is invalid.", Invalid},
		{"an account number with a dash", func(r *Request) { r.PaymentMethods[0].Networks.FedNow.AccountNumber = "1234-5678" },
			"AccountNumber is invalid.", Invalid},
		{"the standard's own alphanumeric account number", func(r *Request) {
			r.PaymentMethods[0].Networks.FedNow.AccountNumber = "ACME00112233445"
		}, "", 0},
		{"an ACH account is checked too", func(r *Request) {
			r.PaymentMethods[0].Networks.ACH = &BankAddress{RoutingNumber: "021000021", AccountNumber: "12"}
		}, "AccountNumber is invalid.", Invalid},
		{"a Solana recipient of 43 characters", func(r *Request) {
			r.PaymentMethods[0].Networks = Networks{Solana: &SolanaAddress{Recipient: recipient[:43]}}
		}, "", 0},
		{"a Solana recipient that is not Base58", func(r *Request) {
			r.PaymentMethods[0].Networks = Networks{Solana: &SolanaAddress{Recipient: "0OIl1111111111111111111111111111111111111111"}}
		}, "Solana recipient must be a Base58 address of 32 to 44 characters.", Invalid},
		{"a Solana recipient too short", func(r *Request) {
			r.PaymentMethods[0].Networks = Networks{Solana: &SolanaAddress{Recipient: "tooshort"}}
		}, "Solana recipient must be a Base58 address of 32 to 44 characters.", Invalid},
		{"a Solana recipient too long", func(r *Request) {
			r.PaymentMethods[0].Networks = Networks{Solana: &SolanaAddress{Recipient: recipient + "EXTRA"}}
		}, "Solana recipient must be a Base58 address of 32 to 44 characters.", Invalid},
		{"a Solana memo of 100 characters", func(r *Request) {
			r.PaymentMethods[0].Networks = Networks{Solana: &SolanaAddress{Recipient: recipient, Memo: new(strings.Repeat("é", 100))}}
		}, "", 0},
		{"a Solana memo of 101 characters", func(r *Request) {
			r.PaymentMethods[0].Networks = Networks{Solana: &SolanaAddress{Recipient: recipient, Memo: new(strings.Repeat("m", 101))}}
		}, "Solana memo must not exceed 100 characters.", Invalid},

		// Tips (I-10, ADR-0017; X9-TIP-420, -421, -501, -502).
		{"no tip block", func(r *Request) { r.Bill.Tip = nil }, "", 0},
		{"tips refused", func(r *Request) { r.Bill.Tip = &Tip{} }, "", 0},
		{"tips within a range", func(r *Request) { r.Bill.Tip = &Tip{Allowed: true, Range: &TipRange{Min: 0, Max: 25}} }, "", 0},
		{"tips with presets alone", func(r *Request) { r.Bill.Tip = &Tip{Allowed: true, Presets: []int32{10, 15}} }, "", 0},
		{"tips with one preset: no minimum size", func(r *Request) { r.Bill.Tip = &Tip{Allowed: true, Presets: []int32{10}} }, "", 0},
		{"refused with a range", func(r *Request) { r.Bill.Tip = &Tip{Range: &TipRange{Min: 0, Max: 20}} },
			"tip.allow: cannot provided range or presets when tip is not allowed", BusinessRule},
		{"refused with presets", func(r *Request) { r.Bill.Tip = &Tip{Presets: []int32{10}} },
			"tip.allow: cannot provided range or presets when tip is not allowed", BusinessRule},
		{"allowed with neither", func(r *Request) { r.Bill.Tip = &Tip{Allowed: true} },
			"tip.allow: should inform range or presets when tip is allowed", BusinessRule},
		{"allowed with empty presets", func(r *Request) { r.Bill.Tip = &Tip{Allowed: true, Presets: []int32{}} },
			"tip.allow: should inform range or presets when tip is allowed", BusinessRule},
		{"a tip range upside down", func(r *Request) { r.Bill.Tip = &Tip{Allowed: true, Range: &TipRange{Min: 20, Max: 10}} },
			"Tip range min value cannot be greater than tip range max value", Invalid},

		// Dates (X9-LIFE-420 to -422).
		{"validUntil in the past", func(r *Request) {
			r.ValidUntil = now.Add(-time.Minute)
			r.PaymentMethods[0].ValidUntil = r.ValidUntil
		}, "paymentMethods[0].validUntil: must be after or equal to actual date.", BusinessRule},
		{"a method outliving a past QR Code: the cross-field rule first", func(r *Request) {
			r.ValidUntil = now.Add(-ms)
			r.PaymentMethods[0].ValidUntil = now
		}, "paymentMethods[0].validUntil: must be before or equal than payment request validUntil.", BusinessRule},
		{"validUntil exactly now", func(r *Request) {
			r.ValidUntil = now
			r.PaymentMethods[0].ValidUntil = now
		}, "", 0},
		{"a due date after validUntil", func(r *Request) { *r = withInvoice(*r, r.ValidUntil.Add(ms), 0, 0) },
			"bill.invoice.dueDate: must be before or equal to validUntil date.", BusinessRule},
		{"a due date in the past", func(r *Request) { *r = withInvoice(*r, now.Add(-ms), 0, 0) },
			"bill.invoice.dueDate: must be after or equal to actual date.", BusinessRule},
		{"a method valid after the QR Code", func(r *Request) { r.PaymentMethods[0].ValidUntil = r.ValidUntil.Add(ms) },
			"paymentMethods[0].validUntil: must be before or equal than payment request validUntil.", BusinessRule},

		// Currencies (I-3; X9-CUR-420, -422).
		{"an unsupported currency", func(r *Request) {
			r.Bill.AmountDue.Currency = "EUR"
			r.PaymentMethods[0].Currency = "EUR"
		}, "currency: Currency EUR is not settled by any payment rail this deployment supports. Supported: USD, USDC, FRNT.", BusinessRule},
		{"currencies from two groups: the allow list names BRL first", func(r *Request) {
			r.PaymentMethods = append(r.PaymentMethods, r.PaymentMethods[0])
			r.PaymentMethods[1].Currency = "BRL"
		}, "currency: Currency BRL is not settled by any payment rail this deployment supports. Supported: USD, USDC, FRNT.", BusinessRule},
		{"USD beside USDC and FRNT", func(r *Request) {
			for _, c := range []string{"USDC", "FRNT"} {
				r.PaymentMethods = append(r.PaymentMethods, PaymentMethod{Currency: c, ValidUntil: r.ValidUntil, Amount: 1,
					Networks: Networks{Solana: &SolanaAddress{Recipient: recipient}}})
			}
		}, "", 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := valid()
			tc.change(&r)
			err := CheckCreate(r, BundledCurrencies(), now)
			if tc.want == "" {
				if err != nil {
					t.Fatalf("refused: %v", err)
				}
				return
			}
			text, kind := violation(t, err)
			if text != tc.want || kind != tc.kind {
				t.Errorf("\n got %q (%s)\nwant %q (%s)", text, kind, tc.want, tc.kind)
			}
		})
	}
}

// S9.4-AC1 (I-3): the currency check runs on the configured lists; an empty allow list disables
// it, and the peg rule then names the mix.
func TestCheckCreateWithAnotherConfiguration(t *testing.T) {
	r := valid()
	r.PaymentMethods = append(r.PaymentMethods, r.PaymentMethods[0])
	r.PaymentMethods[1].Currency = "BRL"
	err := CheckCreate(r, NewCurrencies(nil, [][]string{{"USD", "USDC"}, {"BRL"}}), now)
	want := "currency: Currencies [USD, BRL] cannot be combined on the same request; currencies mixed on one request must all belong to the same pegged group, or the request must use a single currency"
	if text, _ := violation(t, err); text != want {
		t.Errorf("got %q", text)
	}
}

// S9.4-AC2 (ADR-0005; X9-AMT-423): with a bill due one day before validUntil, the largest late fee
// is fixed + 100. Bill and method of 18 nines − 1000 with a fee of 1000 are accepted; one more unit
// of bill, fee or method, or a validUntil one day later, is refused naming the field.
func TestTheLateFeeBound(t *testing.T) {
	top := eighteenNines - 1000
	validUntil := now.AddDate(1, 0, 0)
	request := func(bill, method, fixed int64, until time.Time) Request {
		r := valid()
		r.ValidUntil = until
		r.PaymentMethods[0].ValidUntil = until
		r.Bill.AmountDue.Amount = bill
		r.PaymentMethods[0].Amount = method
		return withInvoice(r, validUntil.Add(-day), fixed, 100)
	}
	feeText := func(fixed, days, fee int64) string {
		return "the largest late fee this bill reaches before validUntil (fixed " + itoa(fixed) + " + perDay 100 × " +
			itoa(days) + " days = " + itoa(fee) + ")"
	}
	tail := ", more than the 18 digits ANSI X9.150 allows (999999999999999999). Lower the amount or the late fee, or set an earlier validUntil."

	if err := CheckCreate(request(top, top, 900, validUntil), BundledCurrencies(), now); err != nil {
		t.Fatalf("18 nines exactly: %v", err)
	}
	cases := []struct {
		name string
		r    Request
		want string
	}{
		{"one more unit of bill", request(top+1, top, 900, validUntil),
			"bill.amountDue.amount: " + itoa(top+1) + " with " + feeText(900, 1, 1000) + " can reach 1000000000000000000" + tail},
		{"one more unit of fee", request(top, top, 901, validUntil),
			"bill.amountDue.amount: " + itoa(top) + " with " + feeText(901, 1, 1001) + " can reach 1000000000000000000" + tail},
		{"one more unit of method", request(top, top+1, 900, validUntil),
			"paymentMethods[0].amount: " + itoa(top+1) + " with " + feeText(900, 1, 1000) + " can reach 1000000000000000000" + tail},
		{"validUntil a day later", request(top, top, 900, validUntil.Add(day)),
			"bill.amountDue.amount: " + itoa(top) + " with " + feeText(900, 2, 1100) + " can reach 1000000000000000099" + tail},
	}
	for _, c := range cases {
		err := CheckCreate(c.r, BundledCurrencies(), now)
		if text, kind := violation(t, err); text != c.want || kind != BusinessRule {
			t.Errorf("%s:\n got %q (%s)\nwant %q", c.name, text, kind, c.want)
		}
		// PATCH is held to the same bound against the merged state.
		if err := CheckPatch(c.r, BundledCurrencies()); err == nil {
			t.Errorf("%s: CheckPatch accepted it", c.name)
		}
	}

	// A fee too large for an int64 is refused, never wrapped (Java's X9-AMT-116).
	r := request(1, 1, MaxAmount, validUntil.AddDate(30, 0, 0))
	r.Bill.AmountDue.Adjustment.LateFees.PerDay = MaxAmount
	if text, _ := violation(t, CheckLateFeeBound(r)); !strings.HasPrefix(text, "bill.amountDue.amount: 1 with ") {
		t.Errorf("got %q", text)
	}

	// A QR Code that expires at its due date owes no fee, so nothing to bound.
	r = request(top+1000, top+1000, 900, validUntil)
	r.Bill.Invoice.DueDate = validUntil
	if err := CheckLateFeeBound(r); err != nil {
		t.Errorf("no fee before validUntil: %v", err)
	}
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

// S9.4-AC1: on PATCH the merged state is held to the date rules, whichever side moved (R-EP-27),
// and to the currencies; the past is not judged.
func TestCheckPatch(t *testing.T) {
	cases := []struct {
		name   string
		change func(r *Request)
		want   string
	}{
		{"a valid merged state", func(*Request) {}, ""},
		{"a validUntil already past is the merge's business", func(r *Request) {
			r.ValidUntil = now.Add(-day)
			r.PaymentMethods[0].ValidUntil = r.ValidUntil
		}, ""},
		{"validUntil moved before the due date", func(r *Request) {
			*r = withInvoice(*r, due, 0, 0)
			r.ValidUntil = due.Add(-ms)
			r.PaymentMethods[0].ValidUntil = r.ValidUntil
		}, "bill.invoice.dueDate: must be before or equal to validUntil date."},
		{"validUntil moved before a method's", func(r *Request) { r.ValidUntil = r.ValidUntil.Add(-ms) },
			"paymentMethods[0].validUntil: must be before or equal than payment request validUntil."},
		{"a duplicate currency", func(r *Request) { r.PaymentMethods = append(r.PaymentMethods, r.PaymentMethods[0]) },
			"paymentMethods: must not contain duplicated currency: USD"},
		{"an unsupported currency", func(r *Request) { r.PaymentMethods[0].Currency = "EUR" },
			"currency: Currency EUR is not settled by any payment rail this deployment supports. Supported: USD, USDC, FRNT."},
	}
	for _, tc := range cases {
		r := valid()
		tc.change(&r)
		err := CheckPatch(r, BundledCurrencies())
		if tc.want == "" {
			if err != nil {
				t.Errorf("%s: %v", tc.name, err)
			}
			continue
		}
		if text, _ := violation(t, err); text != tc.want {
			t.Errorf("%s:\n got %q\nwant %q", tc.name, text, tc.want)
		}
	}
}

// Of several repeated currencies, the one named is the first repeated one in request order
// (owner decision 2026-11-15, Story 9.4 Q5): the first, by first appearance, that appears again.
func TestFirstDuplicateIsInRequestOrder(t *testing.T) {
	methods := func(currencies ...string) []PaymentMethod {
		ms := make([]PaymentMethod, 0, len(currencies))
		for _, c := range currencies {
			ms = append(ms, PaymentMethod{Currency: c})
		}
		return ms
	}
	cases := []struct {
		in   []string
		want string
	}{
		{[]string{"USDC", "USD", "USDC", "USD"}, "USDC"},
		{[]string{"USD", "CAD", "CAD", "USD"}, "USD"},
		{[]string{"FRNT", "USD", "USDC", "USD"}, "USD"},
		{[]string{"USD", "USDC"}, ""},
	}
	for _, c := range cases {
		if got := firstDuplicate(methods(c.in...)); got != c.want {
			t.Errorf("%v: named %q, want %q", c.in, got, c.want)
		}
	}
}
