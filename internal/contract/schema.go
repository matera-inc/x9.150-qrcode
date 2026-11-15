// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"bytes"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"reflect"
	"slices"
	"strings"
)

// The request schemas of openapi.yaml, one Go type each, members named as on the wire. Every
// member is tagged omitzero, so a body written back has exactly the keys that were sent; the
// Java build's defaults (paymentTiming "immediate", tip.allowed true) are not filled in here.

// CreateRequest is PaymentRequestInput, the body of POST /api/v1/payment-request.
type CreateRequest struct {
	ValidUntil            Timestamp                   `json:"validUntil,omitzero"`
	Creditor              Object[Creditor]            `json:"creditor,omitzero"`
	Bill                  Object[Bill]                `json:"bill,omitzero"`
	LocationID            String                      `json:"locationId,omitzero"`
	Unstructured          String                      `json:"unstructured,omitzero"`
	AdditionalInformation List[KeyValuePair]          `json:"additionalInformation,omitzero"`
	PaymentNotification   Object[PaymentNotification] `json:"paymentNotification,omitzero"`
	PaymentMethods        List[PaymentMethod]         `json:"paymentMethods,omitzero"`
	Unknown               Members                     `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (r *CreateRequest) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, r, &r.Unknown)
}

// Creditor is the creditor of a create.
type Creditor struct {
	Name             String                   `json:"name,omitzero"`
	Phone            String                   `json:"phone,omitzero"`
	Email            String                   `json:"email,omitzero"`
	Address          Object[Address]          `json:"address,omitzero"`
	UltimateCreditor Object[UltimateCreditor] `json:"ultimateCreditor,omitzero"`
	MCC              String                   `json:"MCC,omitzero"`
	Unknown          Members                  `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (cr *Creditor) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, cr, &cr.Unknown)
}

// UltimateCreditor is creditor.ultimateCreditor.
type UltimateCreditor struct {
	Account Object[Account] `json:"account,omitzero"`
	Name    String          `json:"name,omitzero"`
	Phone   String          `json:"phone,omitzero"`
	Email   String          `json:"email,omitzero"`
	Address Object[Address] `json:"address,omitzero"`
	Unknown Members         `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (u *UltimateCreditor) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, u, &u.Unknown)
}

// Account is creditor.ultimateCreditor.account.
type Account struct {
	ID         String  `json:"id,omitzero"`
	SchemaName String  `json:"schemaName,omitzero"`
	Unknown    Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (a *Account) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, a, &a.Unknown)
}

// Address is Address, and PatchAddress (the same members; line2, state and postalCode may be null).
type Address struct {
	Line1      String  `json:"line1,omitzero"`
	Line2      String  `json:"line2,omitzero"`
	City       String  `json:"city,omitzero"`
	State      String  `json:"state,omitzero"`
	PostalCode String  `json:"postalCode,omitzero"`
	Country    String  `json:"country,omitzero"`
	Unknown    Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (a *Address) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, a, &a.Unknown)
}

// Bill is Bill (BillBase and amountDue) of a create.
type Bill struct {
	PaymentTiming Enum[TimingValues] `json:"paymentTiming,omitzero"`
	Description   String             `json:"description,omitzero"`
	Order         Object[Order]      `json:"order,omitzero"`
	Invoice       Object[Invoice]    `json:"invoice,omitzero"`
	Tip           Object[Tip]        `json:"tip,omitzero"`
	AmountDue     Object[AmountDue]  `json:"amountDue,omitzero"`
	Unknown       Members            `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (b *Bill) UnmarshalJSONFrom(dec *jsontext.Decoder) error { return readClosed(dec, b, &b.Unknown) }

// Order is bill.order, and PatchOrder.
type Order struct {
	Number  String  `json:"number,omitzero"`
	Date    Date    `json:"date,omitzero"`
	Unknown Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (o *Order) UnmarshalJSONFrom(dec *jsontext.Decoder) error { return readClosed(dec, o, &o.Unknown) }

// Invoice is bill.invoice, and PatchInvoice.
type Invoice struct {
	Number   String           `json:"number,omitzero"`
	Date     Date             `json:"date,omitzero"`
	DueDate  Timestamp        `json:"dueDate,omitzero"`
	Invoicee Object[Invoicee] `json:"invoicee,omitzero"`
	Unknown  Members          `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (i *Invoice) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, i, &i.Unknown)
}

// Invoicee is Invoicee, and PatchInvoicee.
type Invoicee struct {
	Name    String          `json:"name,omitzero"`
	Phone   String          `json:"phone,omitzero"`
	Email   String          `json:"email,omitzero"`
	Address Object[Address] `json:"address,omitzero"`
	Unknown Members         `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (i *Invoicee) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, i, &i.Unknown)
}

// Tip is bill.tip, and PatchTip (whose presets, when sent, hold at least one value).
type Tip struct {
	Allowed Boolean          `json:"allowed,omitzero"`
	Range   Object[TipRange] `json:"range,omitzero"`
	Presets List[Integer]    `json:"presets,omitzero"`
	Unknown Members          `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (tp *Tip) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, tp, &tp.Unknown)
}

// TipRange is TipRange.
type TipRange struct {
	Min     Integer `json:"min,omitzero"`
	Max     Integer `json:"max,omitzero"`
	Unknown Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (tr *TipRange) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, tr, &tr.Unknown)
}

// AmountDue is AmountDue of a create; PatchAmountDue has no currency.
type AmountDue struct {
	Amount      Long               `json:"amount,omitzero"`
	Currency    String             `json:"currency,omitzero"`
	Adjustments Object[Adjustment] `json:"adjustments,omitzero"`
	Unknown     Members            `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (a *AmountDue) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, a, &a.Unknown)
}

// Adjustment is Adjustment, and PatchAdjustment.
type Adjustment struct {
	Formula    Enum[FormulaValues]          `json:"formula,omitzero"`
	Parameters Object[AdjustmentParameters] `json:"parameters,omitzero"`
	Unknown    Members                      `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (a *Adjustment) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, a, &a.Unknown)
}

// AdjustmentParameters is adjustments.parameters, and PatchAdjustmentParameters.
type AdjustmentParameters struct {
	Discounts List[Discount]   `json:"discounts,omitzero"`
	LateFees  Object[LateFees] `json:"lateFees,omitzero"`
	Unknown   Members          `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (p *AdjustmentParameters) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, p, &p.Unknown)
}

// Discount is an item of parameters.discounts.
type Discount struct {
	DaysBefore  Integer `json:"daysBefore,omitzero"`
	Discount    Long    `json:"discount,omitzero"`
	Explanation String  `json:"explanation,omitzero"`
	Unknown     Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (d *Discount) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, d, &d.Unknown)
}

// LateFees is parameters.lateFees.
type LateFees struct {
	Fixed       Long    `json:"fixed,omitzero"`
	PerDay      Long    `json:"perDay,omitzero"`
	Explanation String  `json:"explanation,omitzero"`
	Unknown     Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (l *LateFees) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, l, &l.Unknown)
}

// KeyValuePair is an item of additionalInformation.
type KeyValuePair struct {
	Key     String  `json:"key,omitzero"`
	Value   String  `json:"value,omitzero"`
	Unknown Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (kv *KeyValuePair) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, kv, &kv.Unknown)
}

// PaymentNotification is the paymentNotification of a create.
type PaymentNotification struct {
	Kind     Enum[KindValues] `json:"kind,omitzero"`
	Endpoint URI              `json:"endpoint,omitzero"`
	Unknown  Members          `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (p *PaymentNotification) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, p, &p.Unknown)
}

// PaymentMethod is an item of paymentMethods, and PatchPaymentMethod (whose editable.range may be
// absent). A key it does not declare is kept in Unknown, which is never written back, and refused
// by the create and PATCH checks (I-16).
type PaymentMethod struct {
	Currency   String           `json:"currency,omitzero"`
	ValidUntil Timestamp        `json:"validUntil,omitzero"`
	Amount     Long             `json:"amount,omitzero"`
	Editable   Object[Editable] `json:"editable,omitzero"`
	Networks   Object[Networks] `json:"networks,omitzero"`
	Unknown    Members          `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (m *PaymentMethod) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, m, &m.Unknown)
}

// Editable is paymentMethods[].editable, and PatchEditable.
type Editable struct {
	Range   Object[IntegerRange] `json:"range,omitzero"`
	Unknown Members              `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (e *Editable) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, e, &e.Unknown)
}

// IntegerRange is IntegerRange.
type IntegerRange struct {
	Min     Long    `json:"min,omitzero"`
	Max     Long    `json:"max,omitzero"`
	Unknown Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (r *IntegerRange) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, r, &r.Unknown)
}

// Networks is NetworksSimple and PatchNetworksSimple: the four interpreted rails, and every other
// key kept as sent in Other, in order (I-9). The contract leaves it open; the business rules refuse
// what is in Other by name (I-2), so a refused network is never dropped in silence.
type Networks struct {
	FedNow Object[BankAddress] `json:"fednow,omitzero"`
	RTP    Object[BankAddress] `json:"rtp,omitzero"`
	ACH    Object[BankAddress] `json:"ach,omitzero"`
	Solana Object[Solana]      `json:"solana,omitzero"`
	Other  Members             `json:"-"`
}

// OtherNames are the keys of Other, in the order they were sent.
func (n Networks) OtherNames() []string { return n.Other.Names() }

// UnmarshalJSONFrom implements json.UnmarshalerFrom: the four rails into their members, every
// other key into Other.
func (n *Networks) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*n = Networks{}
	return readMembers(dec, &n.Other, map[string]any{
		"fednow": &n.FedNow, "rtp": &n.RTP, "ach": &n.ACH, "solana": &n.Solana,
	})
}

// MarshalJSONTo implements json.MarshalerTo.
func (n Networks) MarshalJSONTo(enc *jsontext.Encoder) error {
	type known Networks
	return writeMembers(enc, known(n), n.Other)
}

// BankAddress is FedNow, RTP and ACH, whose fields X9.150 itself fixes. Like Solana it is closed
// on the QR Codes this service generates (owner, 2026-11-15; INTERPRETATION I-16): a key other
// than the three is kept in Unknown and refused by the create and PATCH checks. Reading never
// fails on one, so a reader of somebody else's payload may use this type and ignore Unknown.
type BankAddress struct {
	RoutingNumber  String                 `json:"routingNumber,omitzero"`
	AccountNumber  String                 `json:"accountNumber,omitzero"`
	ProtectionType Enum[ProtectionValues] `json:"protectionType,omitzero"`
	Unknown        Members                `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (b *BankAddress) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*b = BankAddress{}
	return readMembers(dec, &b.Unknown, map[string]any{
		"routingNumber": &b.RoutingNumber, "accountNumber": &b.AccountNumber, "protectionType": &b.ProtectionType,
	})
}

// MarshalJSONTo implements json.MarshalerTo.
func (b BankAddress) MarshalJSONTo(enc *jsontext.Encoder) error {
	type known BankAddress
	return writeMembers(enc, known(b), b.Unknown)
}

// Solana is the Solana Foundation's published embedding (official-spec/SOLANA-FIELDS.md). It is
// closed (`additionalProperties: false`, I-9): a key other than recipient and memo is kept in
// Unknown and refused by the create and PATCH checks (I-16), never by reading.
type Solana struct {
	Recipient String  `json:"recipient,omitzero"`
	Memo      String  `json:"memo,omitzero"`
	Unknown   Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (s *Solana) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*s = Solana{}
	return readMembers(dec, &s.Unknown, map[string]any{"recipient": &s.Recipient, "memo": &s.Memo})
}

// MarshalJSONTo implements json.MarshalerTo.
func (s Solana) MarshalJSONTo(enc *jsontext.Encoder) error {
	type known Solana
	return writeMembers(enc, known(s), s.Unknown)
}

// PatchRequest is PatchPaymentRequestReplacement, the body of PATCH /api/v1/payment-request/{id}.
// Present tells which members the PATCH carried; Null which it cleared. A key it does not declare
// is kept in Unknown and refused by name (I-16).
type PatchRequest struct {
	LocationID            String              `json:"locationId,omitzero"`
	ValidUntil            Timestamp           `json:"validUntil,omitzero"`
	Bill                  Object[PatchBill]   `json:"bill,omitzero"`
	Unstructured          String              `json:"unstructured,omitzero"`
	AdditionalInformation List[KeyValuePair]  `json:"additionalInformation,omitzero"`
	PaymentMethods        List[PaymentMethod] `json:"paymentMethods,omitzero"`
	Unknown               Members             `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (r *PatchRequest) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, r, &r.Unknown)
}

// PatchBill is PatchBillUpdate: paymentTiming is required and has no default.
type PatchBill struct {
	PaymentTiming Enum[TimingValues]     `json:"paymentTiming,omitzero"`
	Description   String                 `json:"description,omitzero"`
	Order         Object[Order]          `json:"order,omitzero"`
	Invoice       Object[Invoice]        `json:"invoice,omitzero"`
	Tip           Object[Tip]            `json:"tip,omitzero"`
	AmountDue     Object[PatchAmountDue] `json:"amountDue,omitzero"`
	Unknown       Members                `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (b *PatchBill) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, b, &b.Unknown)
}

// PatchAmountDue is PatchAmountDue: the amount and its adjustments, no currency.
type PatchAmountDue struct {
	Amount      Long               `json:"amount,omitzero"`
	Adjustments Object[Adjustment] `json:"adjustments,omitzero"`
	Unknown     Members            `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (a *PatchAmountDue) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, a, &a.Unknown)
}

// StatusUpdate is StatusUpdate, the body of PUT /api/v1/payment-request/{id}/status-update.
type StatusUpdate struct {
	Status     Enum[StatusValues] `json:"status,omitzero"`
	Network    String             `json:"network,omitzero"`
	EndToEndID String             `json:"endToEndId,omitzero"`
}

// OutboundNotification is OutboundPaymentNotification, the body of
// POST /api/v1/payment-notification/pre-payment and post-payment.
type OutboundNotification struct {
	Endpoint      URI                      `json:"endpoint,omitzero"`
	CorrelationID UUID                     `json:"correlationId,omitzero"`
	Notification  Object[NotificationData] `json:"notification,omitzero"`
	Unknown       Members                  `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (r *OutboundNotification) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, r, &r.Unknown)
}

// NotificationData is PaymentNotificationData: the notification a payer sends a payee.
type NotificationData struct {
	Payment      Object[NotificationPayment] `json:"payment,omitzero"`
	Payer        Object[Payer]               `json:"payer,omitzero"`
	ExpectedDate Timestamp                   `json:"expectedDate,omitzero"`
	Blockchain   Object[Blockchain]          `json:"blockchain,omitzero"`
	Unknown      Members                     `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (n *NotificationData) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, n, &n.Unknown)
}

// NotificationPayment is notification.payment.
type NotificationPayment struct {
	QRCodeID      String  `json:"qrcodeId,omitzero"`
	Amount        Long    `json:"amount,omitzero"`
	TipAmount     Long    `json:"tipAmount,omitzero"`
	Currency      String  `json:"currency,omitzero"`
	Network       String  `json:"network,omitzero"`
	TransactionID String  `json:"transactionId,omitzero"`
	Unknown       Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (p *NotificationPayment) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, p, &p.Unknown)
}

// Payer is notification.payer.
type Payer struct {
	Info    String  `json:"info,omitzero"`
	Unknown Members `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (p *Payer) UnmarshalJSONFrom(dec *jsontext.Decoder) error { return readClosed(dec, p, &p.Unknown) }

// Blockchain is notification.blockchain.
type Blockchain struct {
	Action  Enum[ActionValues] `json:"action,omitzero"`
	From    String             `json:"from,omitzero"`
	To      String             `json:"to,omitzero"`
	Unknown Members            `json:"-"`
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom (readClosed).
func (b *Blockchain) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	return readClosed(dec, b, &b.Unknown)
}

// SignatureValidation is SignatureValidationRequest, the body of POST /api/v1/signature/validate.
type SignatureValidation struct {
	Content String `json:"content,omitzero"`
}

// EmvDecoder is QRCodeEmvDecoder, the body of POST /api/v1/qrcode-emv-decoder.
type EmvDecoder struct {
	QRCode         String `json:"qrCode,omitzero"`
	DateForPayment Date   `json:"dateForPayment,omitzero"`
	CorrelationID  UUID   `json:"correlationID,omitzero"`
}

// Member is a key of an object that its type does not declare, with its value as sent.
type Member struct {
	Name  string
	Value jsontext.Value
}

// Members are undeclared keys, in the order they were sent (a repeated key at its last place).
type Members []Member

// Names are the members' keys, in order.
func (m Members) Names() []string {
	if len(m) == 0 {
		return nil
	}
	names := make([]string, 0, len(m))
	for _, member := range m {
		names = append(names, member.Name)
	}
	return names
}

// readClosed reads an object the contract closes (additionalProperties: false, INTERPRETATION
// I-16) into the struct v points to: each key a member's json name declares into that member, any
// other into unknown, which the create, PATCH and outbound checks refuse by name. Reading itself
// never fails on an unknown key. A null reads as the zero struct, as the default reader reads it.
func readClosed(dec *jsontext.Decoder, v any, unknown *Members) error {
	rv := reflect.ValueOf(v).Elem()
	rv.SetZero()
	if dec.PeekKind() == 'n' {
		_, err := dec.ReadToken()
		return err
	}
	declared := make(map[string]any, rv.NumField())
	for i, name := range memberNames(rv.Type()) {
		if name != "" {
			declared[name] = rv.Field(i).Addr().Interface()
		}
	}
	return readMembers(dec, unknown, declared)
}

// memberNames is the json name of each field of t, in order; "" for a field that is not a member
// (json:"-").
func memberNames(t reflect.Type) []string {
	names := make([]string, t.NumField())
	for i := range t.NumField() {
		f := t.Field(i)
		name, _, _ := strings.Cut(f.Tag.Get("json"), ",")
		if f.IsExported() && name != "-" {
			if name == "" {
				name = f.Name
			}
			names[i] = name
		}
	}
	return names
}

// fieldsOf is the members of the struct v, in the order it declares them (the contract's order).
func fieldsOf(v any) []string {
	return slices.DeleteFunc(memberNames(reflect.TypeOf(v)), func(n string) bool { return n == "" })
}

// readMembers reads an object: a declared key into its member (a pointer in declared), any other
// into other. A repeated key keeps its last value, as Jackson keeps it.
func readMembers(dec *jsontext.Decoder, other *Members, declared map[string]any) error {
	if dec.PeekKind() != '{' {
		return errMalformed
	}
	if _, err := dec.ReadToken(); err != nil {
		return err
	}
	for dec.PeekKind() != '}' {
		tok, err := dec.ReadToken()
		if err != nil {
			return err
		}
		name := tok.String()
		if member, ok := declared[name]; ok {
			if err := json.UnmarshalDecode(dec, member); err != nil {
				return err
			}
			continue
		}
		value, err := dec.ReadValue()
		if err != nil {
			return err
		}
		kept, err := lastWins(value)
		if err != nil {
			return err
		}
		*other = slices.DeleteFunc(*other, func(m Member) bool { return m.Name == name })
		*other = append(*other, Member{Name: name, Value: kept})
	}
	_, err := dec.ReadToken()
	return err
}

// lastWins is value with every repeated object key, at any depth, kept once with its last value:
// the rule the rest of the reader applies, as Jackson does. An undeclared member is otherwise
// carried as sent (I-9), but a repeated key inside it could not be written back, because every
// object written here has unique names.
func lastWins(value jsontext.Value) (jsontext.Value, error) {
	kind := value.Kind()
	if kind != '{' && kind != '[' {
		return slices.Clone(value), nil
	}
	dec := jsontext.NewDecoder(bytes.NewReader(value), jsontext.AllowDuplicateNames(true))
	if _, err := dec.ReadToken(); err != nil {
		return nil, err
	}
	var (
		members Members
		items   []jsontext.Value
	)
	for dec.PeekKind() != '}' && dec.PeekKind() != ']' {
		name := ""
		if kind == '{' {
			tok, err := dec.ReadToken()
			if err != nil {
				return nil, err
			}
			name = tok.String()
		}
		raw, err := dec.ReadValue()
		if err != nil {
			return nil, err
		}
		kept, err := lastWins(raw)
		if err != nil {
			return nil, err
		}
		if kind == '[' {
			items = append(items, kept)
			continue
		}
		members = slices.DeleteFunc(members, func(m Member) bool { return m.Name == name })
		members = append(members, Member{Name: name, Value: kept})
	}
	if _, err := dec.ReadToken(); err != nil {
		return nil, err
	}

	var buf bytes.Buffer
	enc := jsontext.NewEncoder(&buf)
	if kind == '[' {
		if err := enc.WriteToken(jsontext.BeginArray); err != nil {
			return nil, err
		}
		for _, item := range items {
			if err := enc.WriteValue(item); err != nil {
				return nil, err
			}
		}
		if err := enc.WriteToken(jsontext.EndArray); err != nil {
			return nil, err
		}
	} else {
		if err := enc.WriteToken(jsontext.BeginObject); err != nil {
			return nil, err
		}
		for _, m := range members {
			if err := enc.WriteToken(jsontext.String(m.Name)); err != nil {
				return nil, err
			}
			if err := enc.WriteValue(m.Value); err != nil {
				return nil, err
			}
		}
		if err := enc.WriteToken(jsontext.EndObject); err != nil {
			return nil, err
		}
	}
	return jsontext.Value(bytes.TrimSuffix(buf.Bytes(), []byte("\n"))), nil
}

// writeMembers writes known's members, then other's, as one object.
func writeMembers(enc *jsontext.Encoder, known any, other Members) error {
	raw, err := json.Marshal(known)
	if err != nil {
		return err
	}
	if len(other) == 0 {
		return enc.WriteValue(raw)
	}
	dec := jsontext.NewDecoder(bytes.NewReader(raw))
	if _, err := dec.ReadToken(); err != nil {
		return err
	}
	if err := enc.WriteToken(jsontext.BeginObject); err != nil {
		return err
	}
	for dec.PeekKind() != '}' {
		tok, err := dec.ReadToken()
		if err != nil {
			return err
		}
		name := tok.String()
		value, err := dec.ReadValue()
		if err != nil {
			return err
		}
		if err := enc.WriteToken(jsontext.String(name)); err != nil {
			return err
		}
		if err := enc.WriteValue(value); err != nil {
			return err
		}
	}
	for _, m := range other {
		if err := enc.WriteToken(jsontext.String(m.Name)); err != nil {
			return err
		}
		if err := enc.WriteValue(m.Value); err != nil {
			return err
		}
	}
	return enc.WriteToken(jsontext.EndObject)
}
