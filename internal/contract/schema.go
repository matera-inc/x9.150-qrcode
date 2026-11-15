// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"bytes"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"slices"
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
}

// Creditor is the creditor of a create.
type Creditor struct {
	Name             String                   `json:"name,omitzero"`
	Phone            String                   `json:"phone,omitzero"`
	Email            String                   `json:"email,omitzero"`
	Address          Object[Address]          `json:"address,omitzero"`
	UltimateCreditor Object[UltimateCreditor] `json:"ultimateCreditor,omitzero"`
	MCC              String                   `json:"MCC,omitzero"`
}

// UltimateCreditor is creditor.ultimateCreditor.
type UltimateCreditor struct {
	Account Object[Account] `json:"account,omitzero"`
	Name    String          `json:"name,omitzero"`
	Phone   String          `json:"phone,omitzero"`
	Email   String          `json:"email,omitzero"`
	Address Object[Address] `json:"address,omitzero"`
}

// Account is creditor.ultimateCreditor.account.
type Account struct {
	ID         String `json:"id,omitzero"`
	SchemaName String `json:"schemaName,omitzero"`
}

// Address is Address, and PatchAddress (the same members; line2, state and postalCode may be null).
type Address struct {
	Line1      String `json:"line1,omitzero"`
	Line2      String `json:"line2,omitzero"`
	City       String `json:"city,omitzero"`
	State      String `json:"state,omitzero"`
	PostalCode String `json:"postalCode,omitzero"`
	Country    String `json:"country,omitzero"`
}

// Bill is Bill (BillBase and amountDue) of a create.
type Bill struct {
	PaymentTiming Enum[TimingValues] `json:"paymentTiming,omitzero"`
	Description   String             `json:"description,omitzero"`
	Order         Object[Order]      `json:"order,omitzero"`
	Invoice       Object[Invoice]    `json:"invoice,omitzero"`
	Tip           Object[Tip]        `json:"tip,omitzero"`
	AmountDue     Object[AmountDue]  `json:"amountDue,omitzero"`
}

// Order is bill.order, and PatchOrder.
type Order struct {
	Number String `json:"number,omitzero"`
	Date   Date   `json:"date,omitzero"`
}

// Invoice is bill.invoice, and PatchInvoice.
type Invoice struct {
	Number   String           `json:"number,omitzero"`
	Date     Date             `json:"date,omitzero"`
	DueDate  Timestamp        `json:"dueDate,omitzero"`
	Invoicee Object[Invoicee] `json:"invoicee,omitzero"`
}

// Invoicee is Invoicee, and PatchInvoicee.
type Invoicee struct {
	Name    String          `json:"name,omitzero"`
	Phone   String          `json:"phone,omitzero"`
	Email   String          `json:"email,omitzero"`
	Address Object[Address] `json:"address,omitzero"`
}

// Tip is bill.tip, and PatchTip (whose presets, when sent, hold at least one value).
type Tip struct {
	Allowed Boolean          `json:"allowed,omitzero"`
	Range   Object[TipRange] `json:"range,omitzero"`
	Presets List[Integer]    `json:"presets,omitzero"`
}

// TipRange is TipRange.
type TipRange struct {
	Min Integer `json:"min,omitzero"`
	Max Integer `json:"max,omitzero"`
}

// AmountDue is AmountDue of a create; PatchAmountDue has no currency.
type AmountDue struct {
	Amount      Long               `json:"amount,omitzero"`
	Currency    String             `json:"currency,omitzero"`
	Adjustments Object[Adjustment] `json:"adjustments,omitzero"`
}

// Adjustment is Adjustment, and PatchAdjustment.
type Adjustment struct {
	Formula    Enum[FormulaValues]          `json:"formula,omitzero"`
	Parameters Object[AdjustmentParameters] `json:"parameters,omitzero"`
}

// AdjustmentParameters is adjustments.parameters, and PatchAdjustmentParameters.
type AdjustmentParameters struct {
	Discounts List[Discount]   `json:"discounts,omitzero"`
	LateFees  Object[LateFees] `json:"lateFees,omitzero"`
}

// Discount is an item of parameters.discounts.
type Discount struct {
	DaysBefore  Integer `json:"daysBefore,omitzero"`
	Discount    Long    `json:"discount,omitzero"`
	Explanation String  `json:"explanation,omitzero"`
}

// LateFees is parameters.lateFees.
type LateFees struct {
	Fixed       Long   `json:"fixed,omitzero"`
	PerDay      Long   `json:"perDay,omitzero"`
	Explanation String `json:"explanation,omitzero"`
}

// KeyValuePair is an item of additionalInformation.
type KeyValuePair struct {
	Key   String `json:"key,omitzero"`
	Value String `json:"value,omitzero"`
}

// PaymentNotification is the paymentNotification of a create.
type PaymentNotification struct {
	Kind     Enum[KindValues] `json:"kind,omitzero"`
	Endpoint URI              `json:"endpoint,omitzero"`
}

// PaymentMethod is an item of paymentMethods, and PatchPaymentMethod (whose editable.range may be
// absent).
type PaymentMethod struct {
	Currency   String           `json:"currency,omitzero"`
	ValidUntil Timestamp        `json:"validUntil,omitzero"`
	Amount     Long             `json:"amount,omitzero"`
	Editable   Object[Editable] `json:"editable,omitzero"`
	Networks   Object[Networks] `json:"networks,omitzero"`
}

// Editable is paymentMethods[].editable, and PatchEditable.
type Editable struct {
	Range Object[IntegerRange] `json:"range,omitzero"`
}

// IntegerRange is IntegerRange.
type IntegerRange struct {
	Min Long `json:"min,omitzero"`
	Max Long `json:"max,omitzero"`
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

// BankAddress is FedNow, RTP and ACH.
type BankAddress struct {
	RoutingNumber  String                 `json:"routingNumber,omitzero"`
	AccountNumber  String                 `json:"accountNumber,omitzero"`
	ProtectionType Enum[ProtectionValues] `json:"protectionType,omitzero"`
}

// Solana is the Solana Foundation's published embedding (official-spec/SOLANA-FIELDS.md). It is
// closed (`additionalProperties: false`, I-9): a key other than recipient and memo is kept in
// Unknown and refused by Validate.
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
// Present tells which members the PATCH carried; Null which it cleared.
type PatchRequest struct {
	LocationID            String              `json:"locationId,omitzero"`
	ValidUntil            Timestamp           `json:"validUntil,omitzero"`
	Bill                  Object[PatchBill]   `json:"bill,omitzero"`
	Unstructured          String              `json:"unstructured,omitzero"`
	AdditionalInformation List[KeyValuePair]  `json:"additionalInformation,omitzero"`
	PaymentMethods        List[PaymentMethod] `json:"paymentMethods,omitzero"`
}

// PatchBill is PatchBillUpdate: paymentTiming is required and has no default.
type PatchBill struct {
	PaymentTiming Enum[TimingValues]     `json:"paymentTiming,omitzero"`
	Description   String                 `json:"description,omitzero"`
	Order         Object[Order]          `json:"order,omitzero"`
	Invoice       Object[Invoice]        `json:"invoice,omitzero"`
	Tip           Object[Tip]            `json:"tip,omitzero"`
	AmountDue     Object[PatchAmountDue] `json:"amountDue,omitzero"`
}

// PatchAmountDue is PatchAmountDue: the amount and its adjustments, no currency.
type PatchAmountDue struct {
	Amount      Long               `json:"amount,omitzero"`
	Adjustments Object[Adjustment] `json:"adjustments,omitzero"`
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
}

// NotificationData is PaymentNotificationData: the notification a payer sends a payee.
type NotificationData struct {
	Payment      Object[NotificationPayment] `json:"payment,omitzero"`
	Payer        Object[Payer]               `json:"payer,omitzero"`
	ExpectedDate Timestamp                   `json:"expectedDate,omitzero"`
	Blockchain   Object[Blockchain]          `json:"blockchain,omitzero"`
}

// NotificationPayment is notification.payment.
type NotificationPayment struct {
	QRCodeID      String `json:"qrcodeId,omitzero"`
	Amount        Long   `json:"amount,omitzero"`
	TipAmount     Long   `json:"tipAmount,omitzero"`
	Currency      String `json:"currency,omitzero"`
	Network       String `json:"network,omitzero"`
	TransactionID String `json:"transactionId,omitzero"`
}

// Payer is notification.payer.
type Payer struct {
	Info String `json:"info,omitzero"`
}

// Blockchain is notification.blockchain.
type Blockchain struct {
	Action Enum[ActionValues] `json:"action,omitzero"`
	From   String             `json:"from,omitzero"`
	To     String             `json:"to,omitzero"`
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
		*other = slices.DeleteFunc(*other, func(m Member) bool { return m.Name == name })
		*other = append(*other, Member{Name: name, Value: slices.Clone(value)})
	}
	_, err := dec.ReadToken()
	return err
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
