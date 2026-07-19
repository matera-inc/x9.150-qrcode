/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity.validator;

import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.BlockchainVO;
import com.matera.x9qrcode.domain.vo.InvoiceVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
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
        NetworkEnum network = newPaymentNotification.payment().network();

        switch (network) {
            case FEDNOW, RTP -> validatePaymentNotificationFromInstantPayments(newPaymentNotification);
            case ACH -> validatePaymentNotificationFromACH(newPaymentNotification);
            case POLYGON, SOLANA, ETHEREUM, BITCOIN -> validatePaymentNotificationFromBlockchain(newPaymentNotification);
            default ->
                throw new BusinessRuleException("Unsupported network for payment notification: " + network);
        }
    }

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

        if (!QRCodeStatusEnum.ACTIVE.equals(this.entity.getStatus())) {
            String formattedErrorMessage =
                String.format("Cannot notify %s payments for a QR Code that is not ACTIVE.",
                    newPaymentNotification.payment().network());

            throw new BusinessRuleException("paymentNotification.data", formattedErrorMessage);
        }

        if (StringUtils.isBlank(newPaymentNotification.payment().transactionId())) {
            throw new BusinessRuleException("paymentNotification.data.payment.transactionId",
                "Transaction ID is required for Instant Payment notifications.");
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

        if (!QRCodeStatusEnum.ACTIVE.equals(this.entity.getStatus())) {
            throw new BusinessRuleException("paymentNotification.data",
                "Cannot notify ACH payments for a QR Code that is not ACTIVE.");
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

        for (PaymentMethodVO paymentMethod : paymentMethods) {
            if (nonNull(paymentMethod.networks().polygon()) || nonNull(paymentMethod.networks().solana())
                || nonNull(paymentMethod.networks().ethereum()) || nonNull(paymentMethod.networks().bitcoin())) {
                networkFounded = true;
                break;
            }
        }

        if (!networkFounded) {
            throw new BusinessRuleException("paymentNotification.data.payment.network",
                "This QRCode does not support blockchain networks.");
        }

        if (isNull(newPaymentNotification.blockchain())) {
            throw new BusinessRuleException("paymentNotification.data.blockchain",
                "Blockchain data is required for blockchain payment notifications.");
        }

        BlockchainVO blockchainDTO = newPaymentNotification.blockchain();

        if (ActionEnum.PAYMENT_INITIATED.equals(blockchainDTO.action()) &&
            !QRCodeStatusEnum.ACTIVE.equals(this.entity.getStatus())) {

            throw new BusinessRuleException("paymentNotification.data.blockchain.action",
                "Cannot initiate notify a blockchain payment for a QR Code that is not ACTIVE.");
        }

        List<ActionEnum> BLOCKCHAIN_POST_ACTIONS = List.of(ActionEnum.SENT, ActionEnum.NOT_SENT);

        if (BLOCKCHAIN_POST_ACTIONS.contains(blockchainDTO.action())) {
            if (!QRCodeStatusEnum.INITIATED.equals(this.entity.getStatus())) {
                throw new BusinessRuleException("paymentNotification.data.blockchain.action",
                    "Cannot notify a blockchain payment for a QR Code that is not INITIATED.");
            }

            if (ActionEnum.SENT.equals(blockchainDTO.action()) && StringUtils.isBlank(newPaymentNotification.payment().transactionId())) {
                throw new BusinessRuleException(
                    "Transaction ID is required when blockchain notification action is 'sent'.");
            }
        }
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
