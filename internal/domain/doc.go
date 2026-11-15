// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package domain decides a QR Code's lifecycle: every transition of ADR-0035's map, every cell of
// ADR-0036's matrix, and every payment event. It is pure: no database, no HTTP, no clock. Each
// decision takes the row as read, one "now" and the configuration, and returns what to write,
// what to append and what to answer; the caller (the store, Story 9.5) runs it inside one
// transaction and re-runs it from a fresh read when it loses a race (ADR-0025).
//
// The rules it holds, in one place:
//
//   - Time is applied on read (ADR-0019, ADR-0029). [EffectiveStatus] turns a PAYMENT_INITIATED
//     whose reservation has lapsed into ACTIVE; nothing ever writes a status because time passed.
//     A registered transaction hash, and x9.reservation.ttl-seconds = 0, stop the window
//     (ADR-0036). A reservation with no stamp is treated as holding.
//   - Each trigger reads the status ADR-0035's map names: PATCH, PAID, CANCELLED and the location
//     release read the stored status; everything else reads the effective one.
//   - A status-update to the status the QR Code already has, stored and effective, is a retry:
//     nothing written, no event (ADR-0020, ADR-0036 ¶).
//   - Two notifications come from the same payer only when both carry payer.info, the values are
//     equal and the certificate subjects are equal (ADR-0036). An unattributed reservation, taken
//     through the status-update API, matches nobody.
//   - Only PATCH moves the revision (ADR-0016); every accepted transition sets revised_at.
//   - [BuildEvent] is the only code that constructs a payment event (ADR-0035). Its body comes from
//     the stored notification [EventBodySource] picks (the latest carrying a transactionId, else
//     the last stored), else from the first payment method and the payment details.
//   - Only the payment system marks a QR Code PAID, through the status-update API. No payer
//     message ever does (ADR-0036).
package domain
