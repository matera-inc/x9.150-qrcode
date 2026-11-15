// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import "strings"

// Each Validate returns the contract's violations of a body, "<field path>: <message>", in schema
// order, or nil. The handler answers a non-nil result with httpapi.Validation(violations...).
// The constraints are those of openapi.yaml as the Java build's generated DTOs carry them; where
// the two differ, the comment says which the Java build follows.

// Validate checks a create (PaymentRequestInput).
func (r *CreateRequest) Validate() []string {
	c := &checker{}
	c.notNull("validUntil", r.ValidUntil.presence)
	if c.notNull("creditor", r.Creditor.presence) {
		r.Creditor.Value.check(c, "creditor")
	}
	if c.notNull("bill", r.Bill.presence) {
		r.Bill.Value.check(c, "bill")
	}
	c.str("locationId", r.LocationID, size(32, 32))
	c.str("unstructured", r.Unstructured, rulePrintable)
	checkKeyValues(c, "additionalInformation", r.AdditionalInformation)
	if r.PaymentNotification.IsSet() {
		n := r.PaymentNotification.Value
		c.notNull("paymentNotification.kind", n.Kind.presence)
		c.closed("paymentNotification", "paymentNotification", n.Unknown, n)
	}
	checkPaymentMethods(c, r.PaymentMethods, false)
	c.closed("", "the payment request", r.Unknown, *r)
	return c.result()
}

func (cr *Creditor) check(c *checker, path string) {
	checkParty(c, path, cr.Name, cr.Phone, cr.Email)
	if c.notNull(path+".address", cr.Address.presence) {
		cr.Address.Value.check(c, path+".address")
	}
	if u := cr.UltimateCreditor; u.IsSet() {
		up := path + ".ultimateCreditor"
		if c.notNull(up+".account", u.Value.Account.presence) {
			a := u.Value.Account.Value
			if c.notNull(up+".account.id", a.ID.presence) {
				c.str(up+".account.id", a.ID, rulePrintable)
			}
			if c.notNull(up+".account.schemaName", a.SchemaName.presence) {
				c.str(up+".account.schemaName", a.SchemaName, rulePrintable)
			}
			c.closed(up+".account", "account", a.Unknown, a)
		}
		checkParty(c, up, u.Value.Name, u.Value.Phone, u.Value.Email)
		if c.notNull(up+".address", u.Value.Address.presence) {
			u.Value.Address.Value.check(c, up+".address")
		}
		c.closed(up, "ultimateCreditor", u.Value.Unknown, u.Value)
	}
	if c.notNull(path+".MCC", cr.MCC.presence) {
		c.str(path+".MCC", cr.MCC, ruleMCC, size(4, 4))
	}
	c.closed(path, "creditor", cr.Unknown, *cr)
}

// checkParty is the name, phone and email every party carries (Name, Phone, Email schemas).
func checkParty(c *checker, path string, name, phone, mail String) {
	if c.notNull(path+".name", name.presence) {
		c.str(path+".name", name, ruleName, maxLength(50))
	}
	c.str(path+".phone", phone, rulePhone)
	c.str(path+".email", mail, maxLength(60), email)
}

func (a *Address) check(c *checker, path string) {
	if c.notNull(path+".line1", a.Line1.presence) {
		c.str(path+".line1", a.Line1, ruleAddressLine, maxLength(60))
	}
	c.str(path+".line2", a.Line2, ruleAddressLine, maxLength(60))
	if c.notNull(path+".city", a.City.presence) {
		c.str(path+".city", a.City, ruleCity, maxLength(40))
	}
	c.str(path+".state", a.State, ruleState, maxLength(30))
	c.str(path+".postalCode", a.PostalCode, rulePostalCode, maxLength(20))
	if c.notNull(path+".country", a.Country.presence) {
		c.str(path+".country", a.Country, ruleCountry, size(2, 2))
	}
	c.closed(path, "address", a.Unknown, *a)
}

func (b *Bill) check(c *checker, path string) {
	// paymentTiming defaults to "immediate": absent is fine, an explicit null is not.
	c.notExplicitNull(path+".paymentTiming", b.PaymentTiming.presence)
	checkBillBase(c, path, b.Description, b.Order, b.Invoice, b.Tip, false)
	if c.notNull(path+".amountDue", b.AmountDue.presence) {
		a := b.AmountDue.Value
		checkAmount(c, path+".amountDue.amount", a.Amount)
		if c.notNull(path+".amountDue.currency", a.Currency.presence) {
			c.str(path+".amountDue.currency", a.Currency, ruleCurrency, maxLength(32))
		}
		checkAdjustment(c, path+".amountDue.adjustments", a.Adjustments)
		c.closed(path+".amountDue", "amountDue", a.Unknown, a)
	}
	c.closed(path, "bill", b.Unknown, *b)
}

// checkBillBase is what Bill and PatchBill share. patch is PatchTip's presets rule (at least one).
func checkBillBase(c *checker, path string, description String, order Object[Order], invoice Object[Invoice],
	tip Object[Tip], patch bool,
) {
	if c.notNull(path+".description", description.presence) {
		c.str(path+".description", description, rulePrintable, maxLength(100))
	}
	if order.IsSet() {
		if c.notNull(path+".order.number", order.Value.Number.presence) {
			c.str(path+".order.number", order.Value.Number, ruleDocumentNumber, maxLength(20))
		}
		c.closed(path+".order", "order", order.Value.Unknown, order.Value)
	}
	if invoice.IsSet() {
		ip, inv := path+".invoice", invoice.Value
		if c.notNull(ip+".number", inv.Number.presence) {
			c.str(ip+".number", inv.Number, ruleDocumentNumber, maxLength(20))
		}
		c.notNull(ip+".date", inv.Date.presence)
		c.notNull(ip+".dueDate", inv.DueDate.presence)
		if inv.Invoicee.IsSet() {
			who := inv.Invoicee.Value
			checkParty(c, ip+".invoicee", who.Name, who.Phone, who.Email)
			if who.Address.IsSet() {
				who.Address.Value.check(c, ip+".invoicee.address")
			}
			c.closed(ip+".invoicee", "invoicee", who.Unknown, who)
		}
		c.closed(ip, "invoice", inv.Unknown, inv)
	}
	if tip.IsSet() {
		tp, t := path+".tip", tip.Value
		c.notExplicitNull(tp+".allowed", t.Allowed.presence)
		if t.Range.IsSet() {
			r := t.Range.Value
			if c.notNull(tp+".range.min", r.Min.presence) {
				c.rangeInt(tp+".range.min", r.Min, 0, 999)
			}
			if c.notNull(tp+".range.max", r.Max.presence) {
				c.rangeInt(tp+".range.max", r.Max, 0, 999)
			}
			c.closed(tp+".range", "range", r.Unknown, r)
		}
		if patch {
			listSize(c, tp+".presets", t.Presets, 1, 10)
		} else {
			listSize(c, tp+".presets", t.Presets, 0, 10)
		}
		for i, p := range t.Presets.Items {
			c.rangeInt(item(tp+".presets", i), p, 0, 999)
		}
		c.closed(tp, "tip", t.Unknown, t)
	}
}

// checkAmount is a required Amount: an int64 of at most 18 digits.
func checkAmount(c *checker, path string, amount Long) {
	if c.notNull(path, amount.presence) {
		c.maxLong(path, amount, maxAmount)
	}
}

func checkAdjustment(c *checker, path string, adjustment Object[Adjustment]) {
	if !adjustment.IsSet() {
		return
	}
	a := adjustment.Value
	c.notNull(path+".formula", a.Formula.presence)
	defer c.closed(path, "adjustments", a.Unknown, a)
	if !c.notNull(path+".parameters", a.Parameters.presence) {
		return
	}
	pp, p := path+".parameters", a.Parameters.Value
	for i, d := range p.Discounts.Items {
		dp := item(pp+".discounts", i)
		if c.notNull(dp+".daysBefore", d.DaysBefore.presence) {
			c.minInt(dp+".daysBefore", d.DaysBefore, 0)
		}
		checkAmount(c, dp+".discount", d.Discount)
		c.notNull(dp+".explanation", d.Explanation.presence)
		c.closed(dp, "a discount", d.Unknown, d)
	}
	if c.notNull(pp+".lateFees", p.LateFees.presence) {
		lf := p.LateFees.Value
		checkAmount(c, pp+".lateFees.fixed", lf.Fixed)
		checkAmount(c, pp+".lateFees.perDay", lf.PerDay)
		c.notNull(pp+".lateFees.explanation", lf.Explanation.presence)
		c.closed(pp+".lateFees", "lateFees", lf.Unknown, lf)
	}
	c.closed(pp, "parameters", p.Unknown, p)
}

func checkKeyValues(c *checker, path string, pairs List[KeyValuePair]) {
	for i, kv := range pairs.Items {
		p := item(path, i)
		if c.notNull(p+".key", kv.Key.presence) {
			c.str(p+".key", kv.Key, rulePrintable1)
		}
		if c.notNull(p+".value", kv.Value.presence) {
			c.str(p+".value", kv.Value, rulePrintable)
		}
		c.closed(p, "an additionalInformation entry", kv.Unknown, kv)
	}
}

// checkPaymentMethods is paymentMethods of a create (patch false) or a PATCH. Absent, Java's
// generated default is an empty list, so the refusal is the size, not "must not be null".
func checkPaymentMethods(c *checker, methods List[PaymentMethod], patch bool) {
	const path = "paymentMethods"
	if methods.Present && methods.Null {
		c.add(path, msgNotNull)
		return
	}
	if len(methods.Items) < 1 {
		c.add(path, msgSize(1, unbounded))
	}
	for i, m := range methods.Items {
		p := item(path, i)
		if c.notNull(p+".currency", m.Currency.presence) {
			c.str(p+".currency", m.Currency, ruleCurrency, maxLength(32))
		}
		c.notNull(p+".validUntil", m.ValidUntil.presence)
		checkAmount(c, p+".amount", m.Amount)
		// PaymentMethod requires editable.range; PatchEditable lets it be absent or null.
		if m.Editable.IsSet() && (!patch || m.Editable.Value.Range.IsSet()) {
			r := m.Editable.Value.Range
			if c.notNull(p+".editable.range", r.presence) {
				checkAmount(c, p+".editable.range.min", r.Value.Min)
				checkAmount(c, p+".editable.range.max", r.Value.Max)
				c.closed(p+".editable.range", "range", r.Value.Unknown, r.Value)
			}
		}
		if m.Editable.IsSet() {
			c.closed(p+".editable", "editable", m.Editable.Value.Unknown, m.Editable.Value)
		}
		if c.notNull(p+".networks", m.Networks.presence) {
			m.Networks.Value.check(c, p+".networks")
		}
		c.closed(p, "a payment method", m.Unknown, m)
	}
}

func (n *Networks) check(c *checker, path string) {
	for _, rail := range []struct {
		name string
		bank Object[BankAddress]
	}{{"fednow", n.FedNow}, {"rtp", n.RTP}, {"ach", n.ACH}} {
		if !rail.bank.IsSet() {
			continue
		}
		p, b := path+"."+rail.name, rail.bank.Value
		if c.notNull(p+".routingNumber", b.RoutingNumber.presence) {
			c.str(p+".routingNumber", b.RoutingNumber, ruleRoutingNumber, size(9, 9))
		}
		if c.notNull(p+".accountNumber", b.AccountNumber.presence) {
			c.str(p+".accountNumber", b.AccountNumber, ruleAccountNumber, size(4, 17))
		}
		c.notNull(p+".protectionType", b.ProtectionType.presence)
		c.closed(p, rail.name, b.Unknown, b)
	}
	if n.Solana.IsSet() {
		p, s := path+".solana", n.Solana.Value
		if c.notNull(p+".recipient", s.Recipient.presence) {
			c.str(p+".recipient", s.Recipient, ruleBase58Address, size(32, 44))
		}
		c.str(p+".memo", s.Memo, size(1, 100))
		c.closed(p, "solana", s.Unknown, s)
	}
}

// Validate checks a PATCH (PatchPaymentRequestReplacement).
func (r *PatchRequest) Validate() []string {
	c := &checker{}
	c.str("locationId", r.LocationID, size(32, 32))
	if r.Bill.IsSet() {
		b := r.Bill.Value
		c.notNull("bill.paymentTiming", b.PaymentTiming.presence)
		checkBillBase(c, "bill", b.Description, b.Order, b.Invoice, b.Tip, true)
		if c.notNull("bill.amountDue", b.AmountDue.presence) {
			a := b.AmountDue.Value
			checkAmount(c, "bill.amountDue.amount", a.Amount)
			checkAdjustment(c, "bill.amountDue.adjustments", a.Adjustments)
			c.closed("bill.amountDue", "amountDue", a.Unknown, a)
		}
		c.closed("bill", "bill", b.Unknown, b)
	}
	c.str("unstructured", r.Unstructured, rulePrintable)
	checkKeyValues(c, "additionalInformation", r.AdditionalInformation)
	checkPaymentMethods(c, r.PaymentMethods, true)
	c.closed("", "the payment request", r.Unknown, *r)
	return c.result()
}

// Validate checks a status update (StatusUpdate).
func (r *StatusUpdate) Validate() []string {
	c := &checker{}
	c.notNull("status", r.Status.presence)
	c.str("network", r.Network, maxLength(64))
	c.str("endToEndId", r.EndToEndID, maxLength(255))
	return c.result()
}

// Validate checks a pre-payment or post-payment request (OutboundPaymentNotification).
func (r *OutboundNotification) Validate() []string {
	c := &checker{}
	c.notNull("endpoint", r.Endpoint.presence)
	if c.notNull("notification", r.Notification.presence) {
		r.Notification.Value.check(c, "notification.", true)
	}
	c.closed("", "the outbound notification", r.Unknown, *r)
	return c.result()
}

// Validate checks a payment notification (PaymentNotificationData) on its own, its paths starting
// at payment, payer and blockchain. This is how a payee reads another bank's notification, so an
// unknown member is not refused (tolerant to read, INTERPRETATION I-16).
func (n *NotificationData) Validate() []string {
	c := &checker{}
	n.check(c, "", false)
	return c.result()
}

// check checks n. closed is true for the outbound request, whose notification this service signs
// and sends (OutboundPaymentNotificationData, closed at every level); false for one read.
func (n *NotificationData) check(c *checker, prefix string, closed bool) {
	refuse := func(path, object string, unknown Members, v any) {
		if closed {
			c.closed(path, object, unknown, v)
		}
	}
	if c.notNull(prefix+"payment", n.Payment.presence) {
		p, pay := prefix+"payment", n.Payment.Value
		if c.notNull(p+".qrcodeId", pay.QRCodeID.presence) {
			c.str(p+".qrcodeId", pay.QRCodeID, size(32, 32))
		}
		checkAmount(c, p+".amount", pay.Amount)
		c.maxLong(p+".tipAmount", pay.TipAmount, maxAmount)
		if c.notNull(p+".currency", pay.Currency.presence) {
			c.str(p+".currency", pay.Currency, ruleCurrency, maxLength(32))
		}
		if c.notNull(p+".network", pay.Network.presence) {
			c.str(p+".network", pay.Network, maxLength(64))
		}
		c.str(p+".transactionId", pay.TransactionID, maxLength(255))
		refuse(p, "payment", pay.Unknown, pay)
	}
	if n.Payer.IsSet() {
		c.str(prefix+"payer.info", n.Payer.Value.Info, maxLength(254))
		refuse(prefix+"payer", "payer", n.Payer.Value.Unknown, n.Payer.Value)
	}
	if n.Blockchain.IsSet() {
		p, b := prefix+"blockchain", n.Blockchain.Value
		c.notNull(p+".action", b.Action.presence)
		if c.notNull(p+".from", b.From.presence) {
			c.str(p+".from", b.From, ruleBase58Address)
		}
		if c.notNull(p+".to", b.To.presence) {
			c.str(p+".to", b.To, ruleBase58Address)
		}
		refuse(p, "blockchain", b.Unknown, b)
	}
	refuse(strings.TrimSuffix(prefix, "."), "notification", n.Unknown, *n)
}

// Validate checks a signature validation request (SignatureValidationRequest).
func (r *SignatureValidation) Validate() []string {
	c := &checker{}
	c.notNull("content", r.Content.presence)
	return c.result()
}

// Validate checks an EMV decoder request (QRCodeEmvDecoder).
func (r *EmvDecoder) Validate() []string {
	c := &checker{}
	c.notNull("qrCode", r.QRCode.presence)
	return c.result()
}
