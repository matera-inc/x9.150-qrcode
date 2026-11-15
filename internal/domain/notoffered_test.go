// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import (
	"slices"
	"strings"
	"testing"

	"github.com/google/go-cmp/cmp"
)

// ADR-0036, amended 2026-11-15 (owner): a post-commit on a rail or in a currency the QR Code does not
// offer is an error, recorded just in case. In every status: stored with its receipt, one
// payment.sent on every rail (a bank rail included) carrying the not-offered codes after the
// others, the row untouched (no reservation taken, none refreshed or stopped, revised_at kept), and
// the answer a 400 naming what is not offered, as sent.
func TestPostCommitNotOffered(t *testing.T) {
	solanaOnly := func(r Row) Row {
		r.RequestJSON = []byte(`{"paymentMethods":[{"currency":"USDC","amount":1000,"networks":{"solana":{"recipient":"R"},"fednow":null}}]}`)
		return r
	}
	fedNow := withTx(bank(holderA, "FedNow"), "E2E-9")
	eurc := withTx(chain(holderA, ActionSent), "TX-9")
	eurc.Currency = "eurc"
	both := withTx(bank(otherInfo, "RTP"), "E2E-8")
	both.Currency = "EURC"
	contradicting := withTx(chain(holderA, ActionPaymentInitiated), "TX-8")
	contradicting.Currency = "EURC"

	for _, c := range []struct {
		name  string
		row   Row
		n     Notification
		field string
		text  string
		devs  []Deviation
	}{
		{"a rail not offered on ACTIVE: no reservation, payment.sent on a bank rail", solanaOnly(active()), fedNow,
			"paymentNotification.data.payment", "This QR Code does not offer the FedNow rail nor the USD currency.",
			[]Deviation{riws, DeviationNotOfferedRail, DeviationNotOfferedCurrency}},
		{"a currency not offered from the holder: clean otherwise, the window keeps running", held(holderA), eurc,
			"paymentNotification.data.payment.currency", "This QR Code does not offer the eurc currency.",
			[]Deviation{DeviationNotOfferedCurrency}},
		{"a rail not offered, from another payer on a held QR Code: the holder keeps it", solanaOnly(held(holderA)),
			func() Notification { n := both; n.Currency = "usdc"; return n }(),
			"paymentNotification.data.payment.network", "This QR Code does not offer the RTP rail.",
			[]Deviation{riws, DeviationNotOfferedRail}},
		{"both", solanaOnly(held(holderA)), both,
			"paymentNotification.data.payment", "This QR Code does not offer the RTP rail nor the EURC currency.",
			[]Deviation{riws, DeviationNotOfferedRail, DeviationNotOfferedCurrency}},
		{"on PAID, contradicting its action", closed(StatusPaid), contradicting,
			"paymentNotification.data.payment.currency", "EURC currency",
			[]Deviation{riws, ach, DeviationNotOfferedCurrency}},
		{"on a lapsed reservation: not taken", lapsed(holderA), eurc,
			"paymentNotification.data.payment.currency", "eurc",
			[]Deviation{riws, DeviationNotOfferedCurrency}},
		{"once a hash is registered: nothing moves either", withHash(holderA), eurc,
			"paymentNotification.data.payment.currency", "eurc",
			[]Deviation{DeviationNotOfferedCurrency}},
	} {
		t.Run(c.name, func(t *testing.T) {
			d, err := Notify(c.row, c.n, nil, lastFrom(holderA), t0, settings)
			if err != nil {
				t.Fatal(err)
			}
			if d.Write || !cmp.Equal(d.Row, c.row) {
				t.Errorf("the row is written or changed: write %v\n%s", d.Write, cmp.Diff(c.row, d.Row))
			}
			if d.Status != EffectiveStatus(c.row, t0) {
				t.Errorf("status %s, want the effective %s", d.Status, EffectiveStatus(c.row, t0))
			}
			if d.Refusal == nil || d.Refusal.Kind != KindBusinessRule || d.Refusal.Field != c.field || !strings.Contains(d.Refusal.Message, c.text) {
				t.Errorf("refusal %+v, want a 400 on %s naming %q", d.Refusal, c.field, c.text)
			}
			if d.Notification == nil || !slices.Equal(d.Notification.Receipt.Deviations, c.devs) ||
				d.Notification.Receipt.ReceivedIn != EffectiveStatus(c.row, t0) || d.Notification.Phase != PhasePostCommit {
				t.Fatalf("stored %+v, want the post-commit with %v", d.Notification, c.devs)
			}
			if len(d.Events) != 1 || d.Events[0].Type != EventSent {
				t.Fatalf("events %+v, want one payment.sent", d.Events)
			}
			e := d.Events[0]
			if !slices.Equal(e.Deviations, c.devs) || *e.TransactionID != c.n.TransactionID || *e.Currency != c.n.Currency ||
				*e.ReceivedInStatus != EffectiveStatus(c.row, t0) || e.QRCodeRevision != c.row.Revision {
				t.Errorf("payment.sent %+v, want the notification's body and receipt", e)
			}
		})
	}
}

// ADR-0036, amended 2026-11-15, and the owner's ruling of the same day: OfferOf matches the rail
// and the currency whatever their case, each rail on its own; a networks member that is null offers
// nothing; and a rail and a currency each offered, but not on one payment method, are not offered:
// the currency is not offered on that rail.
func TestOfferOf(t *testing.T) {
	for _, c := range []struct {
		network, currency string
		want              []Deviation
		field, text       string
	}{
		{"SOLANA", "usdc", nil, "", ""},
		{"fedNow", "Usd", nil, "", ""},
		{"rtp", "USD", nil, "", ""},
		{"Base", "USDC", []Deviation{DeviationNotOfferedRail},
			"paymentNotification.data.payment.network", "This QR Code does not offer the Base rail."},
		{"solana", "EURC", []Deviation{DeviationNotOfferedCurrency},
			"paymentNotification.data.payment.currency", "This QR Code does not offer the EURC currency."},
		{"tron", "USDT", []Deviation{DeviationNotOfferedRail, DeviationNotOfferedCurrency},
			"paymentNotification.data.payment", "This QR Code does not offer the tron rail nor the USDT currency."},
		{"Solana", "USD", []Deviation{DeviationNotOfferedCurrency},
			"paymentNotification.data.payment.currency", "This QR Code does not offer the USD currency on the Solana rail."},
		{"FedNow", "USDC", []Deviation{DeviationNotOfferedCurrency},
			"paymentNotification.data.payment.currency", "This QR Code does not offer the USDC currency on the FedNow rail."},
	} {
		n := Notification{Network: c.network, Currency: c.currency}
		o, err := OfferOf(active(), n)
		if err != nil || !slices.Equal(o.Deviations(), c.want) {
			t.Errorf("OfferOf(%s, %s).Deviations() = %v, %v; want %v", c.network, c.currency, o.Deviations(), err, c.want)
		}
		r := o.Refusal(n)
		switch {
		case c.field == "" && r != nil:
			t.Errorf("%s %s offered, yet refused: %v", c.network, c.currency, r)
		case c.field != "" && (r == nil || r.Field != c.field || r.Message != c.text):
			t.Errorf("%s %s: refusal %+v, want %s: %s", c.network, c.currency, r, c.field, c.text)
		}
	}
	nullRail := active()
	nullRail.RequestJSON = []byte(`{"paymentMethods":[{"currency":"USD","networks":{"fednow":{"accountNumber":"1"},"rtp":null}}]}`)
	if o, _ := OfferOf(nullRail, Notification{Network: "RTP", Currency: "USD"}); !slices.Equal(o.Deviations(), []Deviation{DeviationNotOfferedRail}) {
		t.Errorf("a null rtp member offers RTP: %v", o.Deviations())
	}
}

// The owner's ruling of 2026-11-15: a post-commit naming a rail and a currency each offered, but not
// on one payment method, is recorded and refused exactly as a currency not offered (X9-CUR-521).
func TestPostCommitCurrencyNotOfferedOnThatRail(t *testing.T) {
	n := withTx(chain(holderA, ActionSent), "TX-7")
	n.Currency = "USD"
	d, err := Notify(held(holderA), n, nil, lastFrom(holderA), t0, settings)
	if err != nil {
		t.Fatal(err)
	}
	if d.Write || d.Refusal == nil || d.Refusal.Message != "This QR Code does not offer the USD currency on the solana rail." {
		t.Fatalf("write %v, refusal %+v", d.Write, d.Refusal)
	}
	if len(d.Events) != 1 || d.Events[0].Type != EventSent || !slices.Equal(d.Events[0].Deviations, []Deviation{DeviationNotOfferedCurrency}) {
		t.Fatalf("events %+v, want one payment.sent flagged NOT_OFFERED_CURRENCY", d.Events)
	}
}
