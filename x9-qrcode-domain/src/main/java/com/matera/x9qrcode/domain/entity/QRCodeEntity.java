/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.entity.validator.QRCodeEntityValidator;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
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
    }

    public void reactivate(PaymentDetailsVO paymentDetails) {
        if (!QRCodeStatusEnum.INITIATED.equals(this.status)) {
            throw new BusinessRuleException("Only QR Codes with INITIATED status can be reactivated.");
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

        this.status = status;
        this.paymentNotification =
            new PaymentNotificationVO(this.paymentNotification.kind(), this.paymentNotification.endpoint(),
                paymentNotificationDataVO);
        this.updateRevision();
    }


    public boolean isNotActiveOrInitiated() {
        List<QRCodeStatusEnum> allowedStatuses = List.of(QRCodeStatusEnum.ACTIVE, QRCodeStatusEnum.INITIATED);

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
