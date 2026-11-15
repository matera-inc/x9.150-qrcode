/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity.validator;

import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.service.LateFeeBound;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.AdjustmentVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.InvoiceVO;
import com.matera.x9qrcode.domain.vo.LateFeesVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.enumerated.ActionEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import org.apache.commons.lang3.StringUtils;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.commons.lang3.BooleanUtils.isNotTrue;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public final class QRCodeEntityValidator {

    private final QRCodeEntity entity;

    public QRCodeEntityValidator(QRCodeEntity entity) {
        if (isNull(entity)) {
            throw new BusinessRuleException("QRCodeEntity", "must not be null");
        }

        this.entity = entity;
    }

    public void validate() {
        validateRequiredProperties();

        validateInvoiceDueDate();

        validatePaymentMethods();

        validateTip();
    }

    public void validateIfPaymentMethodsAreExpired() {
        List<PaymentMethodVO> paymentMethods = this.entity.getPaymentMethods();

        IntStream.range(0, paymentMethods.size()).forEach(i -> {
            PaymentMethodVO paymentMethod = paymentMethods.get(i);

            if (paymentMethod.validUntil().isBefore(DateTimeUtils.nowUTC())) {
                throw new BusinessRuleException("paymentMethods[%d].validUntil".formatted(i), "must be after or equal to actual date.");
            }
        });
    }

    /**
     * The "must be in the future" rules, applied only when a QR Code is CREATED.
     *
     * <p>These used to live in the value-object constructors, which also run on restore — so a QR
     * Code became unreadable the moment any of its own windows moved. A payment QR Code is exactly
     * the kind of record whose windows are meant to move: a discount window closes, a due date
     * passes and a late fee starts accruing. Enforcing the rules on read made those states
     * unreachable, which quietly killed the entire adjustment feature: the QR Code stopped being
     * loadable at precisely the moment its amount was supposed to change.
     *
     * <p>Creating something already in the past is still refused, exactly as before.
     */
    public void validateCreationDates() {
        OffsetDateTime now = DateTimeUtils.nowUTC();

        if (this.entity.getValidUntil().isBefore(now)) {
            throw new BusinessRuleException("validUntil", "must not be less than the current date.");
        }

        BillVO bill = this.entity.getBill();
        InvoiceVO invoice = bill.invoice();

        if (nonNull(invoice)) {
            // dueDate is the only date here that must be in the future: it is when payment is
            // owed. `invoice.date` and `order.date` are NOT — the contract calls them "the date the
            // bill was issued" and "the date the order was created", and a bill issued yesterday for
            // an order raised last week is the ordinary case, not an error. Forcing them forward
            // rejected every truthful invoice and made `order` unusable without inventing a date.
            //
            // `order.date` also NPE'd outright when omitted, which the contract permits: the guard
            // checked `order` for null and then dereferenced `date`. Both problems are gone with the
            // rule, which should never have applied to a date recording when something happened.
            if (invoice.dueDate().isBefore(now)) {
                throw new BusinessRuleException("bill.invoice.dueDate", "must be after or equal to actual date.");
            }

            validateDiscountTargetDates(bill, invoice, now);
        }
    }

    private void validateDiscountTargetDates(BillVO bill, InvoiceVO invoice, OffsetDateTime now) {
        if (isNull(bill.amountDue().adjustment()) || isNull(bill.amountDue().adjustment().parameters())) {
            return;
        }

        List<DiscountVO> discounts = bill.amountDue().adjustment().parameters().discounts();

        if (isNull(discounts)) {
            return;
        }

        IntStream.range(0, discounts.size()).forEach(index -> {
            OffsetDateTime targetDate = invoice.dueDate().minusDays(discounts.get(index).daysBefore());

            if (targetDate.isBefore(now)) {
                throw new BusinessRuleException(
                    "bill.amountDue.adjustments.discounts[%d]".formatted(index),
                    "target date must be after the current date.");
            }
        });
    }

    /**
     * The largest amount the bill can show must fit the 18 digits X9.150 allows (ADR-0005).
     *
     * <p>A late fee is {@code fixed + perDay × daysLate} and grows every day, but it stops at the QR
     * Code's {@code validUntil}, so its largest value is known now. The bill amount is checked as
     * {@code amount + fee}, and every payment-method amount only as the payload would show it: the
     * bill's ratio {@code m × (B + fee) / B}, rounded half up. Every method is bounded by the QR
     * Code's {@code validUntil}, the latest instant any fee is computed at.
     *
     * <p>Run at creation and on PATCH against the merged state; never on restore, so a QR Code
     * stored before this rule stays readable (the formula still refuses to emit past 18 digits).
     */
    public void validateLateFeeBound() {
        BillVO bill = this.entity.getBill();
        AdjustmentVO adjustment = bill.amountDue().adjustment();
        InvoiceVO invoice = bill.invoice();

        if (isNull(adjustment) || isNull(invoice) || isNull(adjustment.parameters())) {
            return;
        }

        LateFeesVO lateFees = adjustment.parameters().lateFees();
        long days = LateFeeBound.daysLate(invoice.dueDate(), this.entity.getValidUntil());
        BigInteger fee = LateFeeBound.largestFee(lateFees, invoice.dueDate(), this.entity.getValidUntil());

        if (fee.signum() == 0) {
            return;
        }

        String feeText = "the largest late fee this bill reaches before validUntil (fixed %d + perDay %d × %d days = %s)"
            .formatted(lateFees.fixed(), lateFees.perDay(), days, fee);

        long billAmount = bill.amountDue().currencyAmount().amount();
        BigInteger billWithFee = BigInteger.valueOf(billAmount).add(fee);

        if (!AmountVO.fitsEighteenDigits(billWithFee)) {
            throw new BusinessRuleException("bill.amountDue.amount", exceeds(billAmount, feeText, billWithFee));
        }

        List<PaymentMethodVO> paymentMethods = this.entity.getPaymentMethods();

        IntStream.range(0, paymentMethods.size()).forEach(index -> {
            long amount = paymentMethods.get(index).amount().value();
            // Only what the payload would show. A separate amount + fee check added nothing: with
            // the bill check above, m + fee past 18 digits implies m > B, and then the scaled
            // amount is at least m + fee, so it is refused here anyway.
            BigInteger withFee = LateFeeBound.prorated(amount, billAmount, billWithFee);

            if (!AmountVO.fitsEighteenDigits(withFee)) {
                throw new BusinessRuleException("paymentMethods[%d].amount".formatted(index),
                    exceeds(amount, feeText, withFee));
            }
        });
    }

    private static String exceeds(long amount, String feeText, BigInteger largest) {
        return ("%d with %s can reach %s, more than the 18 digits ANSI X9.150 allows (%d). "
            + "Lower the amount or the late fee, or set an earlier validUntil.")
            .formatted(amount, feeText, largest, AmountVO.MAX_AMOUNT);
    }

    public void validateInvoiceDueDate() {
        InvoiceVO invoice = this.entity.getBill().invoice();

        if (isNull(invoice)) {
            return;
        }

        if (invoice.dueDate().isAfter(this.entity.getValidUntil())) {
            throw new BusinessRuleException("bill.invoice.dueDate", "must be before or equal to validUntil date.");
        }
    }

    /**
     * The cross-field date rules of creation: the bill falls due, and every payment method lapses,
     * no later than the QR Code's {@code validUntil}. Run by {@link #validate()} at creation and, on
     * every PATCH, against the merged state, whichever side of a pair the PATCH moved.
     */
    public void validateDatesWithinValidUntil() {
        validateInvoiceDueDate();

        List<PaymentMethodVO> paymentMethods = this.entity.getPaymentMethods();

        IntStream.range(0, paymentMethods.size())
            .forEach(index -> validateIfPaymentMethodValidUntilIsBeforeOrEqualToPaymentRequest(paymentMethods.get(index), index));
    }

    public void validatePaymentMethods() {
        List<PaymentMethodVO> paymentMethods = this.entity.getPaymentMethods();

        // Insertion-ordered: of several repeated currencies the first in request order is named, not
        // whichever a HashMap happened to iterate first (owner decision 2026-11-15).
        paymentMethods.stream()
            .collect(Collectors.groupingBy(PaymentMethodVO::currency, LinkedHashMap::new, Collectors.counting()))
            .forEach((currency, count) -> {
                if (count > 1) {
                    throw new BusinessRuleException("paymentMethods", "must not contain duplicated currency: %s".formatted(currency));
                }
            });

        IntStream.range(0, paymentMethods.size())
            .forEach(index -> validateIfPaymentMethodValidUntilIsBeforeOrEqualToPaymentRequest(paymentMethods.get(index), index));
    }

    /**
     * Whether a payer's notification is well formed for THIS QR Code, whatever its status.
     *
     * <p>The status is not judged here: what a notification may do in each status depends on its
     * phase and on who holds the reservation, and that is ADR-0036's matrix, applied by the
     * entity's notification methods.
     */
    public void validatePaymentNotification(PaymentNotificationDataVO newPaymentNotification) {

        PaymentNotificationVO entityPaymentNotification = this.entity.getPaymentNotification();

        if (isNull(entityPaymentNotification)) {
            throw new BusinessRuleException("paymentNotification", "is not configured for this QR Code.");
        }

        if (NotificationKindEnum.EXTERNAL.equals(entityPaymentNotification.kind())) {
            throw new BusinessRuleException("paymentNotification.kind", "must not be EXTERNAL.");
        }

        validatePaymentNotificationAmounts(newPaymentNotification);

        validatePaymentNotificationNetwork(newPaymentNotification);
    }

    private void validateRequiredProperties() {
        if (isNull(this.entity.getValidUntil())) {
            throw new BusinessRuleException("validUntil", "must not be null.");
        }

        if (isNull(this.entity.getCreditor())) {
            throw new BusinessRuleException("creditor", "must not be null.");
        }

        if (isNull(entity.getBill())) {
            throw new BusinessRuleException("bill", "must not be null.");
        }

        if (nonNull(this.entity.getRevision()) && this.entity.getRevision() < 0) {
            throw new BusinessRuleException("revision", "must be equal or greater than zero.");
        }

        if (isNull(this.entity.getCreatedAt())) {
            throw new BusinessRuleException("createdAt", "must not be null.");
        }

        if (isNull(this.entity.getRevisedAt())) {
            throw new BusinessRuleException("revisedAt", "must not be null.");
        }

        if (isNull(this.entity.getStatus())) {
            throw new BusinessRuleException("status", "must not be null.");
        }

        List<PaymentMethodVO> paymentMethods = this.entity.getPaymentMethods();

        if (isNull(paymentMethods) || paymentMethods.isEmpty()) {
            throw new BusinessRuleException("paymentMethods", "must not be null or empty.");
        }
    }

    private void validateIfPaymentMethodValidUntilIsBeforeOrEqualToPaymentRequest(PaymentMethodVO currency, int index) {
        if (currency.validUntil().isAfter(this.entity.getValidUntil())) {
            String field = String.format("paymentMethods[%d].validUntil", index);

            throw new BusinessRuleException(field, "must be before or equal than payment request validUntil.");
        }
    }

    private void validatePaymentNotificationAmounts(PaymentNotificationDataVO newPaymentNotification) {
        Long paymentAmount = newPaymentNotification.payment().amount().value();

        if (paymentAmount <= 0) {
            throw new BusinessRuleException("paymentNotification.data.payment.amount", "must be greater than zero.");
        }

        if (nonNull(newPaymentNotification.payment().tipAmount())) {
            Long tipAmount = newPaymentNotification.payment().tipAmount().value();

            if (tipAmount <= 0) {
                throw new BusinessRuleException("paymentNotification.data.payment.tipAmount",
                    "must be greater than zero.");
            }
        }
    }

    private void validatePaymentNotificationNetwork(PaymentNotificationDataVO newPaymentNotification) {
        // A switch EXPRESSION, deliberately. The statement form this replaced did not force a case
        // per rail, so adding Solana to the enum silently dropped its notifications through
        // unvalidated — the same fall-through that once let Base, XRP and Arc be accepted and
        // ignored. An expression makes the next rail a compile error instead of a quiet hole.
        newPaymentNotification.payment().interpretedRail().ifPresentOrElse(
            rail -> {
                Runnable validation = switch (rail) {
                    case FEDNOW, RTP -> () -> validatePaymentNotificationFromInstantPayments(newPaymentNotification);
                    case ACH -> () -> validatePaymentNotificationFromACH(newPaymentNotification);
                    case SOLANA -> () -> validatePaymentNotificationFromBlockchain(newPaymentNotification);
                };

                validation.run();
            },
            () -> validatePaymentNotificationFromBlockchain(newPaymentNotification));
    }

    /**
     * FedNow and RTP. The End-to-End ID is no longer required: without it the notification is a
     * pre-commit, which is what gives these rails a reservation at all (ADR-0036, S2.4-AC1).
     */
    private void validatePaymentNotificationFromInstantPayments(PaymentNotificationDataVO newPaymentNotification) {
        List<PaymentMethodVO> paymentMethods = this.entity.getPaymentMethods();
        boolean networkFounded = false;

        if (nonNull(newPaymentNotification.blockchain())) {
            throw new BusinessRuleException("paymentNotification.data.blockchain",
                "Blockchain data must not be informed for Instant Payments.");
        }

        for (PaymentMethodVO paymentMethod : paymentMethods) {
            if (nonNull(paymentMethod.networks().fedNow()) || nonNull(paymentMethod.networks().rtp())) {
                networkFounded = true;
                break;
            }
        }

        if (!networkFounded) {
            throw new BusinessRuleException("paymentNotification.data.payment.network",
                "This QRCode does not support Instant Payment networks.");
        }
    }

    private void validatePaymentNotificationFromACH(PaymentNotificationDataVO newPaymentNotification) {
        List<PaymentMethodVO> paymentMethods = this.entity.getPaymentMethods();
        boolean networkFounded = false;

        if (nonNull(newPaymentNotification.blockchain())) {
            throw new BusinessRuleException("paymentNotification.data.blockchain",
                "Blockchain data must not be informed for ACH payments.");
        }

        for (PaymentMethodVO paymentMethod : paymentMethods) {
            if (nonNull(paymentMethod.networks().ach())) {
                networkFounded = true;
                break;
            }
        }

        if (!networkFounded) {
            throw new BusinessRuleException("paymentNotification.data.payment.network",
                "This QRCode does not support ACH network.");
        }

        if (isNull(newPaymentNotification.payer()) || StringUtils.isBlank(newPaymentNotification.payer().info())) {
            throw new BusinessRuleException("paymentNotification.data.payer.info", "Payer info is required for ACH payments.");
        }

        if (isNull(newPaymentNotification.expectedDate())) {
            throw new BusinessRuleException("paymentNotification.data.expectedDate", "Expected date is required for ACH payments.");
        }
    }

    private void validatePaymentNotificationFromBlockchain(PaymentNotificationDataVO newPaymentNotification) {
        List<PaymentMethodVO> paymentMethods = this.entity.getPaymentMethods();
        boolean networkFounded = false;

        String notifiedNetwork = newPaymentNotification.payment().network();

        for (PaymentMethodVO paymentMethod : paymentMethods) {
            // The rail actually notified, not merely "some blockchain": a QR offering Solana does not
            // thereby accept a Bitcoin payment.
            if (paymentMethod.networks().supports(notifiedNetwork)) {
                networkFounded = true;
                break;
            }
        }

        if (!networkFounded) {
            throw new BusinessRuleException("paymentNotification.data.payment.network",
                "This QRCode does not support the %s network.".formatted(notifiedNetwork));
        }

        if (isNull(newPaymentNotification.blockchain())) {
            throw new BusinessRuleException("paymentNotification.data.blockchain",
                "Blockchain data is required for blockchain payment notifications.");
        }

        // SENT claims a committed transaction, and a committed transaction with no hash cannot
        // exist: there is nothing to record. Refused in every status (ADR-0036).
        if (ActionEnum.SENT.equals(newPaymentNotification.blockchain().action())
            && !newPaymentNotification.hasTransactionId()) {
            throw new BusinessRuleException("paymentNotification.data.payment.transactionId",
                "Transaction ID is required when blockchain notification action is 'sent'.");
        }
    }

    /**
     * The create's "not already past" rules for a PATCH (owner decision 2026-11-15): of the dates
     * creation judges against now, only those whose value the PATCH changed are judged, in the
     * create's words and with its inclusive edge — {@code validUntil}, the due date, and each
     * discount's edge ({@code dueDate − daysBefore}) the stored bill did not already have. A date
     * left as it was, even one already past, is not judged again, so a biller can extend a QR Code
     * that has expired. A payment method's {@code validUntil} is checked where a changed method is
     * merged ({@code QRCodeEntity.updatePaymentMethods}).
     */
    public void validateChangedDatesNotPast(OffsetDateTime previousValidUntil, BillVO previousBill) {
        OffsetDateTime now = DateTimeUtils.nowUTC();
        OffsetDateTime validUntil = this.entity.getValidUntil();

        if (!validUntil.isEqual(previousValidUntil) && validUntil.isBefore(now)) {
            throw new BusinessRuleException("validUntil", "must not be less than the current date.");
        }

        BillVO bill = this.entity.getBill();
        InvoiceVO invoice = bill.invoice();

        if (isNull(invoice)) {
            return;
        }

        InvoiceVO previousInvoice = isNull(previousBill) ? null : previousBill.invoice();
        OffsetDateTime previousDue = isNull(previousInvoice) ? null : previousInvoice.dueDate();

        if ((isNull(previousDue) || !invoice.dueDate().isEqual(previousDue)) && invoice.dueDate().isBefore(now)) {
            throw new BusinessRuleException("bill.invoice.dueDate", "must be after or equal to actual date.");
        }

        List<OffsetDateTime> previousEdges = isNull(previousDue) ? List.of()
            : discountsOf(previousBill).stream().map(discount -> previousDue.minusDays(discount.daysBefore())).toList();
        List<DiscountVO> discounts = discountsOf(bill);

        IntStream.range(0, discounts.size()).forEach(index -> {
            OffsetDateTime edge = invoice.dueDate().minusDays(discounts.get(index).daysBefore());

            if (previousEdges.stream().noneMatch(edge::isEqual) && edge.isBefore(now)) {
                throw new BusinessRuleException(
                    "bill.amountDue.adjustments.discounts[%d]".formatted(index),
                    "target date must be after the current date.");
            }
        });
    }

    private static List<DiscountVO> discountsOf(BillVO bill) {
        AdjustmentVO adjustment = bill.amountDue().adjustment();

        if (isNull(adjustment) || isNull(adjustment.parameters()) || isNull(adjustment.parameters().discounts())) {
            return List.of();
        }

        return adjustment.parameters().discounts();
    }

    /**
     * ADR-0017, INTERPRETATION I-10: a tip offer agrees with itself. Run at creation and, by
     * {@code QRCodeEntity.updateBill}, on a PATCH's merged bill.
     */
    public void validateTip() {
        TipVO tip = entity.getBill().tip();
        if (nonNull(tip)) {
            if (isNotTrue(tip.allowed()) && hasTipData(tip)) {
                throw new BusinessRuleException("tip.allow", "cannot provided range or presets when tip is not allowed");
            } else if (isTrue(tip.allowed()) && !hasTipData(tip)) {
                throw new BusinessRuleException("tip.allow", "should inform range or presets when tip is allowed");
            }
        } else {
            throw new BusinessRuleException("tip", "missing tip information");
        }
    }

    private boolean hasTipData(TipVO tip) {
        return nonNull(tip.range()) || nonNull(tip.presets());
    }

}
