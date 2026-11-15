// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/contract"
)

// StoredRequest reads a QR Code's request_json back into the type its create and PATCH wrote it
// from, for another package that judges a message against the QR Code (the payment-notification
// endpoint, Story 10.4).
func StoredRequest(raw []byte) (contract.CreateRequest, error) { return readRequest(raw) }

// BillingRequest is what the business rules read of a request: the payment methods, the bill, its
// tip and its adjustment, as a create reads them.
func BillingRequest(req *contract.CreateRequest) billing.Request { return billingRequest(req) }
