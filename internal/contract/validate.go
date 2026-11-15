// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

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
		c.notNull("paymentNotification.kind", r.PaymentNotification.Value.Kind.presence)
	}
	checkPaymentMethods(c, r.PaymentMethods, false)
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
		}
		checkParty(c, up, u.Value.Name, u.Value.Phone, u.Value.Email)
		if c.notNull(up+".address", u.Value.Address.presence) {
			u.Value.Address.Value.check(c, up+".address")
		}
	}
	if c.notNull(path+".MCC", cr.MCC.presence) {
		c.str(path+".MCC", cr.MCC, ruleMCC, size(4, 4))
	}
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
	}
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
		}
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
		}
		if patch {
			listSize(c, tp+".presets", t.Presets, 1, 10)
		} else {
			listSize(c, tp+".presets", t.Presets, 0, 10)
		}
		for i, p := range t.Presets.Items {
			c.rangeInt(item(tp+".presets", i), p, 0, 999)
		}
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
	}
	if c.notNull(pp+".lateFees", p.LateFees.presence) {
		lf := p.LateFees.Value
		checkAmount(c, pp+".lateFees.fixed", lf.Fixed)
		checkAmount(c, pp+".lateFees.perDay", lf.PerDay)
		c.notNull(pp+".lateFees.explanation", lf.Explanation.presence)
	}
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
			}
		}
		if c.notNull(p+".networks", m.Networks.presence) {
			m.Networks.Value.check(c, p+".networks")
		}
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
		// The bank rails are closed on what this service generates, as solana is (owner,
		// 2026-11-15; INTERPRETATION I-16). The Java build ignores an unknown key here today.
		for _, name := range b.Unknown.Names() {
			c.add(p+"."+name, "is not a field of "+rail.name+", whose fields are routingNumber, accountNumber and protectionType")
		}
	}
	if n.Solana.IsSet() {
		p, s := path+".solana", n.Solana.Value
		if c.notNull(p+".recipient", s.Recipient.presence) {
			c.str(p+".recipient", s.Recipient, ruleBase58Address, size(32, 44))
		}
		c.str(p+".memo", s.Memo, size(1, 100))
		// Solana is closed (additionalProperties: false, I-9). The Java build ignores an unknown
		// key there; the contract and INTERPRETATION I-9 refuse it, and Go follows them.
		for _, name := range s.Unknown.Names() {
			c.add(p+"."+name, "is not a field of solana, whose fields are recipient and memo")
		}
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
			checkAmount(c, "bill.amountDue.amount", b.AmountDue.Value.Amount)
			checkAdjustment(c, "bill.amountDue.adjustments", b.AmountDue.Value.Adjustments)
		}
	}
	c.str("unstructured", r.Unstructured, rulePrintable)
	checkKeyValues(c, "additionalInformation", r.AdditionalInformation)
	checkPaymentMethods(c, r.PaymentMethods, true)
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
		r.Notification.Value.check(c, "notification.")
	}
	return c.result()
}

// Validate checks a payment notification (PaymentNotificationData) on its own, its paths starting
// at payment, payer and blockchain.
func (n *NotificationData) Validate() []string {
	c := &checker{}
	n.check(c, "")
	return c.result()
}

func (n *NotificationData) check(c *checker, prefix string) {
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
	}
	if n.Payer.IsSet() {
		c.str(prefix+"payer.info", n.Payer.Value.Info, maxLength(254))
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
	}
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
