/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity.validator;

import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.exception.QRCodeStatusConflictException;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.BlockchainVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.InvoiceVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.ReservationHolderVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.enumerated.ActionEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    public void validateInvoiceDueDate() {
        InvoiceVO invoice = this.entity.getBill().invoice();

        if (isNull(invoice)) {
            return;
        }

        if (invoice.dueDate().isAfter(this.entity.getValidUntil())) {
            throw new BusinessRuleException("bill.invoice.dueDate", "must be before or equal to validUntil date.");
        }
    }

    public void validatePaymentMethods() {
        List<PaymentMethodVO> paymentMethods = this.entity.getPaymentMethods();

        paymentMethods.stream().collect(Collectors.groupingBy(PaymentMethodVO::currency, Collectors.counting()))
            .forEach((currency, count) -> {
                if (count > 1) {
                    throw new BusinessRuleException("paymentMethods", "must not contain duplicated currency: %s".formatted(currency));
                }
            });

        IntStream.range(0, paymentMethods.size())
            .forEach(index -> validateIfPaymentMethodValidUntilIsBeforeOrEqualToPaymentRequest(paymentMethods.get(index), index));
    }

    public void validatePaymentNotification(PaymentNotificationDataVO newPaymentNotification,
                                            ReservationHolderVO announcingParty) {

        PaymentNotificationVO entityPaymentNotification = this.entity.getPaymentNotification();

        if (isNull(entityPaymentNotification)) {
            throw new BusinessRuleException("paymentNotification", "is not configured for this QR Code.");
        }

        if (NotificationKindEnum.EXTERNAL.equals(entityPaymentNotification.kind())) {
            throw new BusinessRuleException("paymentNotification.kind", "must not be EXTERNAL.");
        }

        validatePaymentNotificationAmounts(newPaymentNotification);

        validatePaymentNotificationNetwork(newPaymentNotification, announcingParty);
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

    private void validatePaymentNotificationNetwork(PaymentNotificationDataVO newPaymentNotification,
                                                    ReservationHolderVO announcingParty) {
        // A switch EXPRESSION, deliberately. The statement form this replaced did not force a case
        // per rail, so adding Solana to the enum silently dropped its notifications through
        // unvalidated — the same fall-through that once let Base, XRP and Arc be accepted and
        // ignored. An expression makes the next rail a compile error instead of a quiet hole.
        newPaymentNotification.payment().interpretedRail().ifPresentOrElse(
            rail -> {
                Runnable validation = switch (rail) {
                    case FEDNOW, RTP -> () -> validatePaymentNotificationFromInstantPayments(newPaymentNotification, announcingParty);
                    case ACH -> () -> validatePaymentNotificationFromACH(newPaymentNotification, announcingParty);
                    case SOLANA -> () -> validatePaymentNotificationFromBlockchain(newPaymentNotification, announcingParty);
                };

                validation.run();
            },
            () -> validatePaymentNotificationFromBlockchain(newPaymentNotification, announcingParty));
    }

    private void validatePaymentNotificationFromInstantPayments(PaymentNotificationDataVO newPaymentNotification,
                                                                ReservationHolderVO announcingParty) {
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

        validateAnnouncement(announcingParty);

        if (StringUtils.isBlank(newPaymentNotification.payment().transactionId())) {
            throw new BusinessRuleException("paymentNotification.data.payment.transactionId",
                "Transaction ID is required for Instant Payment notifications.");
        }
    }

    private void validatePaymentNotificationFromACH(PaymentNotificationDataVO newPaymentNotification,
                                                    ReservationHolderVO announcingParty) {
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

        validateAnnouncement(announcingParty);

        if (isNull(newPaymentNotification.payer()) || StringUtils.isBlank(newPaymentNotification.payer().info())) {
            throw new BusinessRuleException("paymentNotification.data.payer.info", "Payer info is required for ACH payments.");
        }

        if (isNull(newPaymentNotification.expectedDate())) {
            throw new BusinessRuleException("paymentNotification.data.expectedDate", "Expected date is required for ACH payments.");
        }
    }

    private void validatePaymentNotificationFromBlockchain(PaymentNotificationDataVO newPaymentNotification,
                                                           ReservationHolderVO announcingParty) {
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

        BlockchainVO blockchainDTO = newPaymentNotification.blockchain();

        if (ActionEnum.PAYMENT_INITIATED.equals(blockchainDTO.action())) {
            validateAnnouncement(announcingParty);
        }

        List<ActionEnum> BLOCKCHAIN_POST_ACTIONS = List.of(ActionEnum.SENT, ActionEnum.NOT_SENT);

        if (BLOCKCHAIN_POST_ACTIONS.contains(blockchainDTO.action())) {
            validatePostPayment(announcingParty);

            if (ActionEnum.SENT.equals(blockchainDTO.action()) && StringUtils.isBlank(newPaymentNotification.payment().transactionId())) {
                throw new BusinessRuleException(
                    "Transaction ID is required when blockchain notification action is 'sent'.");
            }
        }
    }

    /**
     * Who may announce a payment on this QR Code.
     *
     * <p>Three outcomes, and the middle one is the whole of this rule:
     *
     * <ul>
     *   <li><b>ACTIVE</b> — nobody is holding it. The announcement takes it.</li>
     *   <li><b>PAYMENT_INITIATED, held by this same party</b> — a repeat, not a second payer.
     *       Accepted: it refreshes the window and changes nothing else.</li>
     *   <li><b>PAYMENT_INITIATED, held by somebody else</b> — two payers reaching for one bill.
     *       Refused with 409, because the request is well formed and it is the state that says
     *       no — and because the caller should come back, not correct their request.</li>
     * </ul>
     *
     * <p>The second case used to be refused along with the third. The commonest reason to receive
     * one announcement twice is not a second payer; it is one payer whose request timed out or
     * whose process died between sending and recording. Refusing them punished the party the
     * reservation exists to protect, and did it at the moment their software was already in
     * trouble.
     *
     * <p>Anything else — PAID, CANCELLED, or a reservation nobody can be matched against — is
     * refused as before.
     *
     * <p><b>Every rail comes through here.</b> There used to be three copies of "refuse unless
     * ACTIVE", one per rail, and fixing the blockchain copy alone left ACH refusing the retries it
     * had always refused — found by a test, not by reading. A rule worth stating once is worth
     * having one caller able to get wrong, not three.
     */
    private void validateAnnouncement(ReservationHolderVO announcingParty) {
        QRCodeStatusEnum current = this.entity.effectiveStatus();

        if (QRCodeStatusEnum.ACTIVE.equals(current)) {
            return;
        }

        if (QRCodeStatusEnum.PAYMENT_INITIATED.equals(current) && this.entity.isHeldBy(announcingParty)) {
            return;
        }

        if (QRCodeStatusEnum.PAYMENT_INITIATED.equals(current)) {
            throw new QRCodeStatusConflictException(current,
                "Another payer has already announced a payment for this QR Code. "
                    + "Wait for their reservation to lapse and try again.");
        }

        throw new BusinessRuleException("paymentNotification.data.blockchain.action",
            "Cannot initiate notify a blockchain payment for a QR Code that is not ACTIVE.");
    }

    /**
     * Who may report the outcome of a payment they announced.
     *
     * <p>Only the party that announced it. A {@code SENT} or {@code NOT_SENT} from anybody else is
     * reporting on somebody else's payment, and a {@code NOT_SENT} in particular would let a
     * stranger release a reservation they never took.
     *
     * <p><b>Gated on the holder rather than on the clock</b>, and that difference is the whole
     * reason this is not a one-line check. A payer whose reservation has lapsed may still report:
     * the money moved, and refusing the report does not un-move it — it only means this service
     * never hears about a payment that happened. The 90-second window exists to stop a vanished
     * payer locking a bill forever, not to decide what is true about money.
     *
     * <p>It matters because not every settlement path is bounded. A payment routed through a
     * custody provider can return before its transaction hash exists, with the hash arriving later
     * from a ledger subscription that has no deadline at all. No window covers that, however wide,
     * so a rule keyed on the window would refuse those reports as a matter of course.
     *
     * <p><b>The one case this still cannot serve</b> is a report arriving after the reservation
     * lapsed AND another payer has since taken the QR Code: that overwrites who was holding it, so
     * the late reporter no longer matches anybody. Recording a history of holders would answer it,
     * at the cost of unbounded growth on every document for a case nobody has yet hit. The refusal
     * at least says what happened, which is what the payee needs in order to sort it out by hand.
     */
    private void validatePostPayment(ReservationHolderVO announcingParty) {
        if (this.entity.isHeldBy(announcingParty)) {
            return;
        }

        if (QRCodeStatusEnum.PAYMENT_INITIATED.equals(this.entity.getStatus())) {
            throw new QRCodeStatusConflictException(this.entity.effectiveStatus(),
                "This QR Code is reserved by another payer, so this payment is not yours to report.");
        }

        throw new BusinessRuleException("paymentNotification.data.blockchain.action",
            "Cannot notify a blockchain payment for a QR Code that is not PAYMENT_INITIATED.");
    }

    private void validateTip() {
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
