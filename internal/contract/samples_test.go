// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

// The bodies every test starts from: each request schema with EVERY optional member present, so
// that a constraint anywhere in it has a parent to live in. They are the black-box suite's samples
// (others/blackbox/x9client/samples.py, full_payment_request and the contract cases' bases).

const sampleCreate = `{
  "validUntil": "2030-12-31T23:59:59Z",
  "creditor": {
    "name": "Blackbox Co", "phone": "+15552223333", "email": "party@example.com",
    "address": {"line1": "1 A St", "line2": "Suite 2", "city": "Los Angeles", "state": "CA",
                "postalCode": "90012", "country": "US"},
    "MCC": "4900",
    "ultimateCreditor": {
      "name": "Blackbox Party", "phone": "+15552223333", "email": "party@example.com",
      "address": {"line1": "1 A St", "line2": "Suite 2", "city": "Los Angeles", "state": "CA",
                  "postalCode": "90012", "country": "US"},
      "account": {"id": "ACC-1", "schemaName": "internal"}
    }
  },
  "bill": {
    "description": "blackbox contract", "paymentTiming": "immediate",
    "order": {"number": "ORD-1", "date": "2030-01-10"},
    "invoice": {"number": "INV-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z",
                "invoicee": {"name": "Blackbox Party", "phone": "+15552223333",
                             "email": "party@example.com",
                             "address": {"line1": "1 A St", "line2": "Suite 2", "city": "Los Angeles",
                                         "state": "CA", "postalCode": "90012", "country": "US"}}},
    "tip": {"allowed": true, "range": {"min": 0, "max": 25}, "presets": [10, 15]},
    "amountDue": {"amount": 1000, "currency": "USD", "adjustments": {
      "formula": "FixedDiscountLateFeeLinearInterest",
      "parameters": {
        "discounts": [{"daysBefore": 5, "discount": 50, "explanation": "Early payment"}],
        "lateFees": {"fixed": 100, "perDay": 10, "explanation": "Late fee"}}}}
  },
  "locationId": "0123456789ABCDEF0123456789ABCDEF",
  "unstructured": "blackbox",
  "additionalInformation": [{"key": "Note", "value": "contract"}],
  "paymentNotification": {"kind": "DEFAULT", "endpoint": "https://payee.example.com/notify"},
  "paymentMethods": [
    {"currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
     "editable": {"range": {"min": 500, "max": 2000}},
     "networks": {
       "fednow": {"routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "plaintext"},
       "rtp": {"routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "plaintext"},
       "ach": {"routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "plaintext"}}},
    {"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
     "networks": {"solana": {"recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "memo": "blackbox"}}}
  ]
}`

const samplePatch = `{
  "locationId": "0123456789ABCDEF0123456789ABCDEF",
  "validUntil": "2030-12-31T23:59:59Z",
  "bill": {
    "description": "blackbox contract", "paymentTiming": "immediate",
    "order": {"number": "ORD-1", "date": "2030-01-10"},
    "invoice": {"number": "INV-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z",
                "invoicee": {"name": "Blackbox Party", "phone": "+15552223333",
                             "email": "party@example.com",
                             "address": {"line1": "1 A St", "line2": "Suite 2", "city": "Los Angeles",
                                         "state": "CA", "postalCode": "90012", "country": "US"}}},
    "tip": {"allowed": true, "range": {"min": 0, "max": 25}, "presets": [10, 15]},
    "amountDue": {"amount": 1000, "adjustments": {
      "formula": "FixedDiscountLateFeeLinearInterest",
      "parameters": {
        "discounts": [{"daysBefore": 5, "discount": 50, "explanation": "Early payment"}],
        "lateFees": {"fixed": 100, "perDay": 10, "explanation": "Late fee"}}}}
  },
  "unstructured": "blackbox",
  "additionalInformation": [{"key": "Note", "value": "contract"}],
  "paymentMethods": [
    {"currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
     "editable": {"range": {"min": 500, "max": 2000}},
     "networks": {
       "fednow": {"routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "plaintext"},
       "rtp": {"routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "plaintext"},
       "ach": {"routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "plaintext"}}},
    {"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
     "networks": {"solana": {"recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "memo": "blackbox"}}}
  ]
}`

const sampleStatusUpdate = `{"status": "PAID", "network": "solana", "endToEndId": "E2E-C-1"}`

const sampleOutbound = `{
  "endpoint": "http://127.0.0.1:9/pub/api/v1/payment-notification",
  "correlationId": "8a652794-9e73-4fd7-a628-4d525b638cbc",
  "notification": {
    "payment": {"qrcodeId": "0123456789ABCDEF0123456789ABCDEF", "amount": 1000, "tipAmount": 0,
                "currency": "USDC", "network": "solana",
                "transactionId": "3n8kLq2Vb7yTfXpR9sWzM4cJhG6dEuA1"},
    "payer": {"info": "contract@example.com"},
    "expectedDate": "2030-12-31T23:59:59Z",
    "blockchain": {"action": "SENT", "from": "5xoBq7f7CDgZwqHrDBdRWM84ExRetg4gZq93dyJtoSwp",
                   "to": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"}
  }
}`

const sampleSignatureValidation = `{"content": "a.b.c"}`

const sampleEmvDecoder = `{"qrCode": "000201", "dateForPayment": "2030-12-21",
  "correlationID": "8a652794-9e73-4fd7-a628-4d525b638cbc"}`
