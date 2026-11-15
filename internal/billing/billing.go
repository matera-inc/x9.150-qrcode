// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package billing decides whether a payment request may be issued, and what it costs at a given
// instant. It is pure: no database, no HTTP, and no clock of its own. Every function that depends
// on time takes the instant to judge at, which the caller reads once from internal/clock.
//
// It answers three questions, each as the Java build answers it:
//
//   - Is this request acceptable? CheckCreate and CheckPatch refuse what Java refuses, in Java's
//     order, naming the same field with the same message (a Violation): a network other than
//     fednow, rtp, ach and solana, by name (ADR-0010, ADR-0012); a currency off the allow list or
//     written in another case, and currencies from two peg groups (Currencies); a duplicate
//     currency; a bank account or a Solana recipient of the wrong shape (INTERPRETATION I-6, I-8);
//     a tip offer that contradicts itself (I-10, ADR-0017); a date in the past or after the QR
//     Code's validUntil; an amount past 18 digits, alone or with the largest late fee the bill can
//     reach (ADR-0005).
//   - What is owed at an instant? Adjust applies the FixedDiscountLateFeeLinearInterest formula at
//     exactly one instant (I-13, I-14): a discount up to and including dueDate − daysBefore, no fee
//     at exactly dueDate, a fee that rises at each 24-hour boundary and stops at validUntil.
//   - What does the payload show? Quote chooses the payment methods still valid at that instant,
//     carries the adjustment to each by ratio (BUG-001's fix), and caps the adjustment's validUntil
//     by the methods and the QR Code (FR36).
//
// Amounts are int64 minor units (ADR-0005); every intermediate product is computed with math/big,
// so nothing wraps, and a result past 18 digits is refused rather than returned.
package billing
