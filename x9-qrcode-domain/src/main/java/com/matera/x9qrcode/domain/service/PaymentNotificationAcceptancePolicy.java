/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.dto.FormulaResultDTO;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.service.factory.FormulaFactory;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;

import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Decides whether a payment notification may be accepted against the QR Code it names.
 *
 * <p>A notification that arrives before funds move is a <em>request for permission</em>, so this is
 * the gate: only if everything checks out do we answer OK. The checks are the product rules —
 * a valid amount, the <em>correct</em> amount, a currency this QR Code offers, a destination address
 * this QR Code published, that address carried by the method of that currency, and nothing expired.
 *
 * <p>Currency, address existence and address/currency compatibility collapse into a single lookup:
 * <b>find the payment method whose currency equals the notification's and whose network object for
 * the notified rail carries the notification's destination address.</b> If no such method exists the
 * notification is refused, and that one predicate has covered all three. Amount and expiry are then
 * compared against the method it found.
 */
@RequiredArgsConstructor
public class PaymentNotificationAcceptancePolicy {

    private final FormulaFactory formulaFactory;

    /**
     * @return the payment method the notification matched
     * @throws BusinessRuleException if the notification may not be accepted
     */
    public PaymentMethodVO accept(QRCodeEntity qrCode, PaymentNotificationDataVO notification, OffsetDateTime at) {
        validateQRCodeNotExpired(qrCode, at);

        PaymentMethodVO method = matchPaymentMethod(qrCode, notification);

        validatePaymentMethodNotExpired(method, at);
        validateAmount(qrCode, notification, method, at);

        return method;
    }

    private void validateQRCodeNotExpired(QRCodeEntity qrCode, OffsetDateTime at) {
        if (qrCode.getValidUntil().isBefore(at)) {
            throw new BusinessRuleException("validUntil",
                "This QR Code expired at %s and can no longer be paid.".formatted(qrCode.getValidUntil()));
        }
    }

    private void validatePaymentMethodNotExpired(PaymentMethodVO method, OffsetDateTime at) {
        if (method.validUntil().isBefore(at)) {
            throw new BusinessRuleException("paymentMethods.validUntil",
                "The %s payment method expired at %s.".formatted(method.currency(), method.validUntil()));
        }
    }

    /**
     * The single lookup described in the class comment. Bank rails carry no destination address in a
     * notification, so for those the match is on currency and rail alone.
     */
    private PaymentMethodVO matchPaymentMethod(QRCodeEntity qrCode, PaymentNotificationDataVO notification) {
        String notifiedCurrency = notification.payment().currency();
        String notifiedNetwork = notification.payment().network();

        List<PaymentMethodVO> methods = qrCode.getPaymentMethods();

        // Case-insensitively: this is a third-party payer's message, not our own API.
        boolean currencyOffered = methods.stream().anyMatch(m -> m.currency().equalsIgnoreCase(notifiedCurrency));

        if (!currencyOffered) {
            throw new BusinessRuleException("paymentNotification.data.payment.currency",
                "This QR Code does not offer the %s currency.".formatted(notifiedCurrency));
        }

        Optional<PaymentMethodVO> matched = methods.stream()
            .filter(m -> m.currency().equalsIgnoreCase(notifiedCurrency))
            .filter(m -> m.networks().supports(notifiedNetwork))
            .filter(m -> destinationMatches(m, notification, notifiedNetwork))
            .findFirst();

        return matched.orElseThrow(() -> new BusinessRuleException(
            "paymentNotification.data",
            notification.payment().isStandardRail()
                ? "This QR Code offers no %s payment method on the %s rail."
                      .formatted(notifiedCurrency, notifiedNetwork)
                : ("No %s payment method on this QR Code publishes the destination address %s. "
                   + "The currency, the rail and the address must belong to the same payment method.")
                      .formatted(notifiedCurrency, destinationOf(notification))));
    }

    private boolean destinationMatches(PaymentMethodVO method,
                                       PaymentNotificationDataVO notification,
                                       String notifiedNetwork) {
        if (notification.payment().isStandardRail()) {
            // A bank-rail notification names no destination account; the rail check above is the match.
            return true;
        }

        String published = method.networks().destinationAddressFor(notifiedNetwork);

        return nonNull(published) && published.equals(destinationOf(notification));
    }

    private String destinationOf(PaymentNotificationDataVO notification) {
        if (isNull(notification.blockchain()) || isNull(notification.blockchain().to())) {
            return null;
        }

        return notification.blockchain().to().walletAddress();
    }

    /**
     * The notified amount must be an amount this QR Code could legitimately have quoted — and must
     * not be an underpayment.
     *
     * <p>A payment method carries a face amount, but the payload applies the bill's adjustment when
     * the payer fetches it, and the adjustment moves with time: a discount window closes, a late fee
     * accrues. Each fetch can therefore quote a different figure from the same QR Code, which is why
     * the accepted set is not a single number.
     *
     * <p>Two amounts are accepted:
     * <ul>
     *   <li>the <b>currently adjusted</b> amount — what a payer fetching right now would be quoted;</li>
     *   <li>the <b>face</b> amount, but only when it is not less than the adjusted one.</li>
     * </ul>
     *
     * <p>That asymmetry is the point. When a discount applies the adjusted amount is lower, so paying
     * face is an overpayment and harmless to accept — and refusing it would punish a payer who
     * fetched before the discount applied. When a <b>late fee</b> applies the adjusted amount is
     * higher, so accepting face would be accepting less than is owed; a stale quote is not a licence
     * to underpay, and the payer's remedy is to re-fetch, which is what the standard expects of them
     * once an adjustment window has moved.
     *
     * <p>Narrowing this to a single expected figure needs the payer's {@code dateForPayment} on the
     * notification so the original quote can be reproduced exactly. The contract does not carry it.
     */
    private void validateAmount(QRCodeEntity qrCode,
                                PaymentNotificationDataVO notification,
                                PaymentMethodVO method,
                                OffsetDateTime at) {
        long notified = notification.payment().amount().value();

        if (nonNull(method.editable())) {
            validateWithinEditableRange(notified, method);
            return;
        }

        long faceAmount = method.amount().value();
        long adjustedAmount = adjustedAmountFor(qrCode, method, at);

        if (notified == adjustedAmount) {
            return;
        }

        if (notified == faceAmount && faceAmount >= adjustedAmount) {
            return;
        }

        String expected = faceAmount == adjustedAmount
            ? String.valueOf(faceAmount)
            : (faceAmount > adjustedAmount
                ? "%d (adjusted) or %d (face)".formatted(adjustedAmount, faceAmount)
                : "%d (adjusted, including the late fee)".formatted(adjustedAmount));

        throw new BusinessRuleException("paymentNotification.data.payment.amount",
            "Expected %s %s but the notification carries %d.".formatted(expected, method.currency(), notified));
    }

    /**
     * When the payer chooses the amount, the published range is the rule.
     *
     * <p>The payload advertises {@code editable.range} precisely so the payer may pick — a donation,
     * a top-up, an open tab. Demanding the face amount back would refuse every legitimate use of the
     * feature, so the only question here is whether the chosen figure is inside the range we
     * published. No adjustment is applied: a discount computed against a fixed bill has no meaning
     * for an amount the payer sets.
     */
    private void validateWithinEditableRange(long notified, PaymentMethodVO method) {
        long min = method.editable().range().minAmount();
        long max = method.editable().range().maxAmount();

        if (notified < min || notified > max) {
            throw new BusinessRuleException("paymentNotification.data.payment.amount",
                "The %s amount is editable within %d..%d but the notification carries %d."
                    .formatted(method.currency(), min, max, notified));
        }
    }

    private long adjustedAmountFor(QRCodeEntity qrCode, PaymentMethodVO method, OffsetDateTime at) {
        FormulaResultDTO formulaResult = calculateAdjustment(qrCode, at);

        if (isNull(formulaResult)) {
            return method.amount().value();
        }

        long originalBillAmount = formulaResult.amount() - formulaResult.adjustmentAmount();

        if (originalBillAmount <= 0) {
            return method.amount().value();
        }

        // Pro-rate by ratio rather than subtracting the raw minor-unit delta: currencies on one QR
        // share a pegged group but not a scale, so USD cents and USDC micro-units differ.
        return BigDecimal.valueOf(method.amount().value())
            .multiply(BigDecimal.valueOf(formulaResult.amount()))
            .divide(BigDecimal.valueOf(originalBillAmount), 0, RoundingMode.HALF_UP)
            .longValueExact();
    }

    private FormulaResultDTO calculateAdjustment(QRCodeEntity qrCode, OffsetDateTime at) {
        BillVO bill = qrCode.getBill();

        if (isNull(bill.amountDue().adjustment()) || isNull(bill.invoice())) {
            return null;
        }

        FormulaService formulaService = formulaFactory.createFormula(bill.amountDue().adjustment().formula());

        if (isNull(formulaService)) {
            return null;
        }

        return formulaService.calculate(
            at,
            bill.invoice().dueDate(),
            bill.amountDue().currencyAmount().amount(),
            bill.amountDue().currencyAmount().currency(),
            bill.amountDue().adjustment().parameters()
        );
    }

}
