/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.entity.validator.QRCodeEntityValidator;
import com.matera.x9qrcode.domain.event.PaymentEvent;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.exception.QRCodeStatusConflictException;
import com.matera.x9qrcode.domain.generator.IdGenerator;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.CreditorVO;
import com.matera.x9qrcode.domain.vo.EmvVO;
import com.matera.x9qrcode.domain.vo.LocationIdVO;
import com.matera.x9qrcode.domain.vo.PaymentDetailsVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.ValidUntilVO;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentEventTypeEnum;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Getter
public class QRCodeEntity {

    private final QRCodeEntityValidator qrCodeEntityValidator;
    private final QRCodeIdVO id;
    private LocationIdVO locationId;
    private Integer revision;
    private final OffsetDateTime createdAt;
    private OffsetDateTime revisedAt;
    private ValidUntilVO validUntil;
    private QRCodeStatusEnum status;
    private final CreditorVO creditor;
    private BillVO bill;
    private UnstructuredVO unstructured;
    private Map<String, String> additionalInformation;
    private PaymentNotificationVO paymentNotification;
    private List<PaymentMethodVO> paymentMethods;
    private PaymentDetailsVO paymentDetails;
    private EmvVO qrcodeContent;

    /**
     * Events this transition produced, to be written in the same save as the state change.
     *
     * <p>Transient by design: the entity accumulates what it emitted during THIS unit of work, and
     * the persistence adapter appends them to the document's outbox alongside the new state. That is
     * what makes an event impossible to lose without also losing the state change that caused it,
     * and impossible to publish for a state change that did not happen.
     */
    private final List<PaymentEvent> pendingEvents = new ArrayList<>();

    private QRCodeEntity(QRCodeIdVO id,
                         LocationIdVO locationId,
                         Integer revision,
                         OffsetDateTime createdAt,
                         OffsetDateTime revisedAt,
                         OffsetDateTime validUntil,
                         QRCodeStatusEnum status,
                         CreditorVO creditor,
                         BillVO bill,
                         UnstructuredVO unstructured,
                         Map<String, String> additionalInformation,
                         PaymentNotificationVO paymentNotification,
                         List<PaymentMethodVO> paymentMethods,
                         PaymentDetailsVO paymentDetails,
                         EmvVO qrcodeContent) {
        this.id = id;
        this.locationId = locationId;
        this.revision = revision;
        this.createdAt = createdAt;
        this.revisedAt = revisedAt;
        this.validUntil = new ValidUntilVO(validUntil);
        this.status = status;
        this.creditor = creditor;
        this.bill = bill;
        this.unstructured = unstructured;
        this.additionalInformation = additionalInformation;
        this.paymentNotification = paymentNotification;
        this.paymentMethods = paymentMethods;
        this.paymentDetails = paymentDetails;
        this.qrcodeContent = qrcodeContent;

        this.qrCodeEntityValidator = new QRCodeEntityValidator(this);
        qrCodeEntityValidator.validate();
    }

    public static QRCodeEntity create(IdGenerator<UUID> idGenerator,
                                      String locationId,
                                      OffsetDateTime validUntil,
                                      CreditorVO creditor,
                                      BillVO bill,
                                      UnstructuredVO unstructured,
                                      Map<String, String> additionalInformation,
                                      PaymentNotificationVO paymentNotification,
                                      List<PaymentMethodVO> paymentMethods) {
        Objects.requireNonNull(idGenerator, "IdGenerator must not be null.");

        QRCodeIdVO qrCodeIdVO = QRCodeIdVO.from(idGenerator.generate());

        LocationIdVO locationIdVO = nonNull(locationId) && !locationId.isBlank()
            ? LocationIdVO.from(locationId)
            : LocationIdVO.from(qrCodeIdVO.value());

        OffsetDateTime nowUTC = DateTimeUtils.nowUTC();

        QRCodeEntity qrCodeEntity = new QRCodeEntity(
            qrCodeIdVO,
            locationIdVO,
            null,
            nowUTC,
            nowUTC,
            validUntil,
            QRCodeStatusEnum.ACTIVE,
            creditor,
            bill,
            unstructured,
            additionalInformation,
            paymentNotification,
            paymentMethods,
            null,
            null
        );

        qrCodeEntity.qrCodeEntityValidator.validateIfPaymentMethodsAreExpired();

        return qrCodeEntity;
    }

    public static QRCodeEntity restore(UUID id,
                                       UUID locationId,
                                       Integer revision,
                                       OffsetDateTime createdAt,
                                       OffsetDateTime revisedAt,
                                       OffsetDateTime validUntil,
                                       QRCodeStatusEnum status,
                                       CreditorVO creditor,
                                       BillVO bill,
                                       UnstructuredVO unstructured,
                                       Map<String, String> additionalInformation,
                                       PaymentNotificationVO paymentNotification,
                                       List<PaymentMethodVO> paymentMethods,
                                       PaymentDetailsVO paymentDetails,
                                       EmvVO qrcodeEmv) {
        return new QRCodeEntity(
            QRCodeIdVO.from(id),
            LocationIdVO.from(locationId),
            revision,
            createdAt,
            revisedAt,
            validUntil,
            status,
            creditor,
            bill,
            unstructured,
            additionalInformation,
            paymentNotification,
            paymentMethods,
            paymentDetails,
            qrcodeEmv
        );
    }

    public void updateLocationId(String locationId) {
        this.locationId = LocationIdVO.from(locationId);
    }

    public void updateQrCodeContent(String qrcodeEmv) {
        this.qrcodeContent = new EmvVO(qrcodeEmv);
    }

    public void updateRevision() {
        this.revisedAt = DateTimeUtils.nowUTC();
    }

    public void updateValidUntil(OffsetDateTime validUntil) {
        this.validUntil = new ValidUntilVO(validUntil);
    }

    public void updateAdditionalInformation(Map<String, String> additionalInformation) {
        if (isNull(additionalInformation) || additionalInformation.isEmpty()) {
            this.additionalInformation = null;
        } else {
            this.additionalInformation = Collections.unmodifiableMap(additionalInformation);
        }
    }

    public void updateBill(BillVO bill) {
        if (isNull(bill)) {
            throw new BusinessRuleException("bill", "must not be updated with null.");
        }

        this.bill = bill;

        this.qrCodeEntityValidator.validateInvoiceDueDate();
    }

    public void updatePaymentMethods(List<PaymentMethodVO> updatedPaymentMethods) {
        if (isNull(updatedPaymentMethods) || updatedPaymentMethods.isEmpty()) {
            throw new BusinessRuleException("paymentMethods", "must not be updated with null or empty.");
        }

        if (this.paymentMethods.equals(updatedPaymentMethods)) {
            throw new BusinessRuleException("paymentMethods", "can not find any paymentMethod to be updated.");
        }

        Map<String, PaymentMethodVO> paymentMethodsMap =
            this.paymentMethods.stream().collect(Collectors.toMap(PaymentMethodVO::currency, Function.identity()));

        updatedPaymentMethods.forEach(paymentMethod -> {
            if (!paymentMethodsMap.containsKey(paymentMethod.currency())) {
                throw new BusinessRuleException(
                    "Could not find any paymentMethods with currency %s to be updated.".formatted(paymentMethod.currency())
                );
            }

            PaymentMethodVO existingPaymentMethod = paymentMethodsMap.get(paymentMethod.currency());

            if (!existingPaymentMethod.equals(paymentMethod) && paymentMethod.validUntil().isBefore(DateTimeUtils.nowUTC())) {
                throw new BusinessRuleException(
                    "Can not update paymentMethods with currency %s. ValidUntil must be after or equal to actual date."
                        .formatted(paymentMethod.currency())
                );
            }
        });

        Collection<PaymentMethodVO> mergedPaymentMethods = Stream.concat(this.paymentMethods.stream(), updatedPaymentMethods.stream())
            .collect(Collectors.toMap(PaymentMethodVO::currency, Function.identity(), (existing, replacement) -> replacement)).values();

        this.paymentMethods = new ArrayList<>(mergedPaymentMethods);

        this.qrCodeEntityValidator.validatePaymentMethods();
    }

    public void updateUnstructured(String unstructured) {
        this.unstructured = new UnstructuredVO(unstructured);
    }

    public void pay(PaymentDetailsVO paymentDetails) {
        if (this.isNotActiveOrInitiated()) {
            throw new BusinessRuleException(
                "The QRCode needs to be active to be paid. Current status: %s".formatted(this.getStatus())
            );
        }

        if (isNull(paymentDetails) || isNull(paymentDetails.endToEndId()) || isNull(paymentDetails.paymentNetwork())) {
            throw new BusinessRuleException(
                "Both endToEndId and paymentNetwork fields must be provided when marking as paid.");
        }

        this.status = QRCodeStatusEnum.PAID;
        this.paymentDetails = paymentDetails;
        this.updateRevision();
        this.emit(PaymentEventTypeEnum.PAYMENT_CLEARED, null);
    }

    /**
     * ACTIVE -> PAYMENT_INITIATED: a payment is under way for this QR Code.
     *
     * <p>Driven by the payee's own platform, typically on initial network acceptance of a credit
     * transfer request (ISO 20022 pacs.008). Per ANSI X9.150-2026 §A.9 the payee's PSP SHOULD make
     * this transition on such an event "to prevent duplicate payment" — which is why it is refused
     * for any QR Code that is not ACTIVE.
     */
    public void initiatePayment(PaymentDetailsVO paymentDetails) {
        if (!QRCodeStatusEnum.ACTIVE.equals(this.status)) {
            throw new QRCodeStatusConflictException(
                this.status,
                "The QRCode needs to be active to have a payment initiated. Current status: %s".formatted(this.getStatus())
            );
        }

        if (nonNull(paymentDetails)) {
            throw new BusinessRuleException("paymentDetails must be not informed when initiating a payment.");
        }

        this.status = QRCodeStatusEnum.PAYMENT_INITIATED;
        this.updateRevision();
        this.emit(PaymentEventTypeEnum.PAYMENT_INITIATED, null);
    }

    public void releaseLocation() {
        if(isNotActiveOrInitiated()) {
            this.locationId = LocationIdVO.from(UUID.randomUUID());
        } else {
            throw new BusinessRuleException(
                "The QRCode needs to be inactive to reuse location. Current status: %s".formatted(this.getStatus())
            );
        }
    }

    public void cancel(PaymentDetailsVO paymentDetails) {
        if (this.isNotActiveOrInitiated()) {
            throw new BusinessRuleException(
                "The QRCode needs to be active to be cancelled. Current status: %s".formatted(this.getStatus())
            );
        }

        if (nonNull(paymentDetails)) {
            throw new BusinessRuleException("paymentDetails must be not informed when cancelling a QR Code.");
        }

        this.status = QRCodeStatusEnum.CANCELLED;
        this.paymentDetails = null;
        this.updateRevision();
        this.emit(PaymentEventTypeEnum.PAYMENT_CANCELLED, null);
    }

    public void reactivate(PaymentDetailsVO paymentDetails) {
        if (!QRCodeStatusEnum.PAYMENT_INITIATED.equals(this.status)) {
            throw new BusinessRuleException("Only QR Codes with PAYMENT_INITIATED status can be reactivated.");
        }

        if (nonNull(paymentDetails)) {
            throw new BusinessRuleException("paymentDetails must be not informed when reactivating a QR Code.");
        }

        this.status = QRCodeStatusEnum.ACTIVE;
        this.paymentDetails = null;
        this.updateRevision();
    }

    public void notifyPayment(PaymentNotificationDataVO paymentNotificationDataVO) {
        this.notifyPayment(paymentNotificationDataVO, this.status);
    }

    public void notifyPayment(PaymentNotificationDataVO paymentNotificationDataVO, QRCodeStatusEnum status) {
        this.qrCodeEntityValidator.validatePaymentNotification(paymentNotificationDataVO);

        QRCodeStatusEnum previousStatus = this.status;

        this.status = status;
        this.paymentNotification =
            new PaymentNotificationVO(this.paymentNotification.kind(), this.paymentNotification.endpoint(),
                paymentNotificationDataVO);
        this.updateRevision();

        emitForNotification(previousStatus, paymentNotificationDataVO);
    }

    /**
     * A notification emits an event only when it tells a consumer something new.
     *
     * <p>Crucially, a post-commit notification does NOT clear the QR Code. X9.150 never touches
     * money and cannot observe settlement — it only knows what a payer claimed. So a reported
     * transaction is published as {@code payment.sent} and the QR Code stays PAYMENT_INITIATED;
     * whatever system actually receives the funds matches the transaction and calls the status
     * endpoint, and only that produces {@code payment.cleared}.
     */
    private void emitForNotification(QRCodeStatusEnum previousStatus, PaymentNotificationDataVO data) {
        if (!previousStatus.equals(this.status)) {
            // This notification is what moved the QR Code out of circulation.
            emit(PaymentEventTypeEnum.PAYMENT_INITIATED, null);
            return;
        }

        if (isNull(data.blockchain())) {
            // A courtesy notification on a rail that reconciles from the payment message itself.
            return;
        }

        switch (data.blockchain().action()) {
            case SENT -> emit(PaymentEventTypeEnum.PAYMENT_SENT, null);
            case NOT_SENT -> emit(PaymentEventTypeEnum.PAYMENT_FAILED, "payer reported the payment did not proceed");
            case PAYMENT_INITIATED -> {
                // Already handled by the status transition above.
            }
        }
    }


    /** Events emitted by transitions on this instance, in order. */
    public List<PaymentEvent> getPendingEvents() {
        return Collections.unmodifiableList(pendingEvents);
    }

    private void emit(PaymentEventTypeEnum type, String reason) {
        PaymentNotificationDataVO data =
            nonNull(this.paymentNotification) ? this.paymentNotification.data() : null;

        pendingEvents.add(new PaymentEvent(
            UUID.randomUUID(),
            type,
            DateTimeUtils.nowUTC(),
            this.id.value(),
            this.revision,
            this.locationId.valueAsString(),
            nonNull(data) ? data.payment().amount().value() : amountOfFirstMethod(),
            nonNull(data) ? data.payment().currency() : currencyOfFirstMethod(),
            nonNull(data) ? data.payment().network().value() : networkOfPaymentDetails(),
            nonNull(data) ? data.payment().transactionId() : endToEndIdOfPaymentDetails(),
            invoiceNumber(),
            orderNumber(),
            reason
        ));
    }

    private Long amountOfFirstMethod() {
        return isNull(paymentMethods) || paymentMethods.isEmpty() ? null : paymentMethods.get(0).amount().value();
    }

    private String currencyOfFirstMethod() {
        return isNull(paymentMethods) || paymentMethods.isEmpty() ? null : paymentMethods.get(0).currency();
    }

    private String networkOfPaymentDetails() {
        return isNull(paymentDetails) || isNull(paymentDetails.paymentNetwork())
            ? null : paymentDetails.paymentNetwork().value();
    }

    private String endToEndIdOfPaymentDetails() {
        return isNull(paymentDetails) ? null : paymentDetails.endToEndId();
    }

    private String invoiceNumber() {
        return isNull(bill) || isNull(bill.invoice()) || isNull(bill.invoice().number())
            ? null : bill.invoice().number().value();
    }

    private String orderNumber() {
        return isNull(bill) || isNull(bill.order()) ? null : bill.order().number();
    }

    public boolean isNotActiveOrInitiated() {
        List<QRCodeStatusEnum> allowedStatuses = List.of(QRCodeStatusEnum.ACTIVE, QRCodeStatusEnum.PAYMENT_INITIATED);

        return !allowedStatuses.contains(this.status);
    }

    public OffsetDateTime getValidUntil() {
        return this.validUntil.value();
    }

    public Map<String, String> getAdditionalInformation() {
        if (isNull(this.additionalInformation)) {
            return null;
        }

        return Collections.unmodifiableMap(this.additionalInformation);
    }

    public List<PaymentMethodVO> getPaymentMethods() {
        if (isNull(this.paymentMethods)) {
            return null;
        }

        return Collections.unmodifiableList(paymentMethods);
    }

}
