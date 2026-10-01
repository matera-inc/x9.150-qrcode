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
import com.matera.x9qrcode.domain.exception.QRCodePreconditionFailedException;
import com.matera.x9qrcode.domain.exception.QRCodeStatusConflictException;
import com.matera.x9qrcode.domain.generator.IdGenerator;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.AdditionalInformationVO;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    /**
     * The payment request's own version: incremented when its DATA changes, and only then.
     *
     * <p>Marking a request PAID or CANCELLED does not make it a new version of the request — it is
     * the same request with a new status. A new version appears when a caller changes what is being
     * asked for: the amount due, the due date, a payment method.
     */
    private Integer revision;

    /**
     * The optimistic-lock token, carried so it survives the entity-to-document round trip. Moves on
     * every save, unlike {@link #revision}, and is never exposed on the API.
     */
    private Integer lockVersion;
    private final OffsetDateTime createdAt;
    private OffsetDateTime revisedAt;
    private ValidUntilVO validUntil;
    private QRCodeStatusEnum status;
    private final CreditorVO creditor;
    private BillVO bill;
    private UnstructuredVO unstructured;
    /**
     * Labelled lines shown on the bill.
     *
     * <p>A LIST, not a map, and the entries are not keyed. ANSI X9.150 calls the first element
     * {@code key}, but it behaves as a LABEL: repeats are legitimate and meaningful — "Partial
     * payment" three times is three payments — and the order is the order the biller sent.
     *
     * <p>It was a {@code Map<String, String>} and silently dropped every repeat. If you are tempted
     * back to a map by the word "key", that is the mistake this comment exists to stop.
     */
    private List<AdditionalInformationVO> additionalInformation;
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
                         List<AdditionalInformationVO> additionalInformation,
                         PaymentNotificationVO paymentNotification,
                         List<PaymentMethodVO> paymentMethods,
                         PaymentDetailsVO paymentDetails,
                         EmvVO qrcodeContent,
                         Integer lockVersion) {
        this.id = id;
        this.locationId = locationId;
        this.revision = revision;
        this.lockVersion = lockVersion;
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
                                      List<AdditionalInformationVO> additionalInformation,
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
            0,
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
            null,
            null
        );

        qrCodeEntity.qrCodeEntityValidator.validateIfPaymentMethodsAreExpired();
        qrCodeEntity.qrCodeEntityValidator.validateCreationDates();

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
                                       List<AdditionalInformationVO> additionalInformation,
                                       PaymentNotificationVO paymentNotification,
                                       List<PaymentMethodVO> paymentMethods,
                                       PaymentDetailsVO paymentDetails,
                                       EmvVO qrcodeEmv,
                                       Integer lockVersion) {
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
            qrcodeEmv,
            lockVersion
        );
    }

    public void updateLocationId(String locationId) {
        this.locationId = LocationIdVO.from(locationId);
    }

    public void updateQrCodeContent(String qrcodeEmv) {
        this.qrcodeContent = new EmvVO(qrcodeEmv);
    }

    /**
     * Records a DATA change: a new version of the payment request.
     *
     * <p>Called from the patch path only. A status transition deliberately does not call it — the
     * request has not changed, only what has happened to it.
     */
    public void updateRevision() {
        this.revision = isNull(this.revision) ? 1 : this.revision + 1;
        this.touch();
    }

    /**
     * Records that the QR Code was written to, without claiming its content changed.
     *
     * <p>What a status transition does. `revisedAt` moves because the document did; `revision` does
     * not, because the payment request is the same request — a bill marked PAID is not a new version
     * of the bill.
     */
    private void touch() {
        this.revisedAt = DateTimeUtils.nowUTC();
    }

    public void updateValidUntil(OffsetDateTime validUntil) {
        this.validUntil = new ValidUntilVO(validUntil);
    }

    public void updateAdditionalInformation(List<AdditionalInformationVO> additionalInformation) {
        if (isNull(additionalInformation) || additionalInformation.isEmpty()) {
            this.additionalInformation = null;
        } else {
            this.additionalInformation = List.copyOf(additionalInformation);
        }
    }

    public void updateBill(BillVO bill) {
        if (isNull(bill)) {
            throw new BusinessRuleException("bill", "must not be updated with null.");
        }

        this.bill = bill;

        this.qrCodeEntityValidator.validateInvoiceDueDate();
    }

    /**
     * Refuses the operation unless this QR Code is still at {@code expectedRevision}.
     *
     * <p>Test-and-set. A caller reads the QR Code, decides on what it read, and passes the revision
     * it saw; if anything changed in between, the decision was made on stale information and the
     * operation is refused rather than applied.
     *
     * <p>The revision is the persistence layer's optimistic-lock token, which makes this stronger
     * than comparing the status alone: a PATCH that altered the amount between the read and the
     * write is caught too, and a status comparison would sail straight past it.
     *
     * <p>A null expectation means the caller is not making a conditional request, and nothing is
     * checked — the unconditional behaviour every existing caller relies on.
     */
    /**
     * The entity tag a conditional request echoes back: the version of the data AND what has
     * happened to it.
     *
     * <p>It has to be both. `revision` alone stopped being enough the moment it correctly stopped
     * moving for a status change — a caller meaning "cancel only if nobody has started paying"
     * would have been given a token that a pre-payment leaves untouched. The status alone is not
     * enough either: it would miss a PATCH that changed the amount between the read and the write.
     *
     * <p>Opaque to the caller by design. Read it from the {@code ETag} header, send it back in
     * {@code If-Match}, and never parse it — the format is ours to change.
     */
    /**
     * Accepts the lock token the store assigned on the last save.
     *
     * <p>Persistence bookkeeping, not a business operation. Without it an entity saved twice in one
     * request carries a token the store has already moved past, and the second save is refused as a
     * concurrent modification by the very request that made the first one.
     */
    public void applyLockVersion(Integer lockVersion) {
        this.lockVersion = lockVersion;
    }

    public String entityTag() {
        return "%d-%s".formatted(isNull(this.revision) ? 0 : this.revision, this.status.value());
    }

    /**
     * Refuses the operation unless this QR Code is still exactly as the caller last read it.
     *
     * <p>Test-and-set. A null expectation means the caller is not making a conditional request and
     * nothing is checked — the unconditional behaviour every existing caller relies on.
     */
    public void requireEntityTag(String expectedTag) {
        if (isNull(expectedTag) || expectedTag.equals(entityTag())) {
            return;
        }

        throw new QRCodePreconditionFailedException(
            this.status,
            this.revision,
            "The QR Code has changed since it was read: expected %s, found %s."
                .formatted(expectedTag, entityTag()));
    }

    /**
     * A patch that touches the amounts must name every currency this QR Code offers, and no others.
     *
     * <p>This is <b>our</b> rule, not ANSI X9.150's — the standard says nothing about how a payment
     * request is edited. It exists because the two ways of getting it wrong are both silent:
     *
     * <ul>
     *   <li><b>A currency that is not here.</b> Payment methods are matched and merged by currency,
     *       so an unknown one matches nothing. It used to be dropped before this entity ever saw it,
     *       and the caller was told 200 — the amount they sent simply did not exist afterwards.</li>
     *   <li><b>A currency left out.</b> Omitting one used to leave it at its old amount while the
     *       others moved, so a bill could be reduced in USD and left whole in USDC, which is two
     *       different prices for one debt depending on how the payer chooses to settle it.</li>
     * </ul>
     *
     * <p>All or nothing — and since the API makes {@code paymentMethods} required on every patch,
     * in practice that always means all: a patch that only moves the location still restates every
     * currency at its current amount. The empty case is guarded here anyway, for a caller reaching
     * the use case without going through the contract.
     *
     * @param informedCurrencies the currencies the caller put in the patch, in the order they sent them
     */
    /**
     * The QR content a payer submits must be the content this QR Code actually carries.
     *
     * <p>The payer's app sends the scanned EMV string in the signed body of
     * {@code POST /pub/api/v1/loc/{id}}. The reason it is there at all is that the location id is
     * already in the URL, so without a body the signature would bind to nothing: the content is
     * what gives the signature something meaningful to cover.
     *
     * <p>Before this, the only check was that the <em>submitted</em> string's tag-26 URL contained
     * the <em>submitted</em> location id — both halves from the same caller, so it established that
     * the body was self-consistent, never that it was ours. {@code openapi.yaml} already described
     * the 400 on that endpoint as covering "a signed request whose QR content does not match this
     * location", so the contract promised a binding the code did not perform.
     *
     * <p><b>Byte-exact on the EMV string</b>, deliberately. Base64 representation differences
     * (padding, URL-safe alphabet) are absorbed by decoding before comparison, so what is compared
     * is the code itself. Anything looser would mean deciding which parts of a signed code may
     * differ and still count as the same code, which is a judgement no reference implementation
     * should make silently.
     *
     * <p>Honest about the strength of this: the EMV is largely a function of the location URL and
     * the creditor's name and city, all of which a caller holding the location can see. The check
     * raises the bar from "knows the id" to "holds the code", which is what the contract says, and
     * is not a substitute for the signature establishing who the caller is.
     */
    public void requireIssuedQrCodeContent(String submittedQrCodeContent) {
        if (isNull(submittedQrCodeContent)) {
            return;
        }

        String issued = isNull(this.qrcodeContent) ? null : this.qrcodeContent.value();

        if (isNull(issued) || !issued.equals(submittedQrCodeContent)) {
            throw new BusinessRuleException("qrCodeContent",
                "The QR content in the signed request is not the content issued for this location.");
        }
    }

    public void requirePaymentMethodCurrencies(List<String> informedCurrencies) {
        if (isNull(informedCurrencies) || informedCurrencies.isEmpty()) {
            return;
        }

        Set<String> offered = this.paymentMethods.stream()
            .map(PaymentMethodVO::currency)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> informed = new LinkedHashSet<>(informedCurrencies);

        List<String> notOffered = informed.stream().filter(currency -> !offered.contains(currency)).toList();

        if (!notOffered.isEmpty()) {
            throw new BusinessRuleException("paymentMethods",
                "This QR Code offers %s. A patch may change the amount of a currency already on it, never add one — %s is not here."
                    .formatted(String.join(", ", offered), String.join(", ", notOffered)));
        }

        List<String> notInformed = offered.stream().filter(currency -> !informed.contains(currency)).toList();

        if (!notInformed.isEmpty()) {
            throw new BusinessRuleException("paymentMethods",
                "A patch that changes amounts must inform every currency this QR Code offers (%s); %s was left out."
                    .formatted(String.join(", ", offered), String.join(", ", notInformed)));
        }
    }

    public void updatePaymentMethods(List<PaymentMethodVO> updatedPaymentMethods) {
        this.updatePaymentMethods(updatedPaymentMethods, false);
    }

    /**
     * @param partOfALargerChange whether the surrounding request changed something else. The
     *     "nothing to update" refusal is about the whole patch: identical payment methods beside a
     *     moved location are not a no-op, and rejecting them blocks re-pointing a printed QR Code at
     *     the balance still owed.
     */
    public void updatePaymentMethods(List<PaymentMethodVO> updatedPaymentMethods, boolean partOfALargerChange) {
        if (isNull(updatedPaymentMethods) || updatedPaymentMethods.isEmpty()) {
            throw new BusinessRuleException("paymentMethods", "must not be updated with null or empty.");
        }

        if (!partOfALargerChange && this.paymentMethods.equals(updatedPaymentMethods)) {
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
        this.touch();
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
        this.touch();
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
        this.touch();
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
        this.touch();
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
        this.touch();

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
            nonNull(data) ? data.payment().network() : networkOfPaymentDetails(),
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
        return isNull(paymentDetails) ? null : paymentDetails.paymentNetwork();
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

    public List<AdditionalInformationVO> getAdditionalInformation() {
        if (isNull(this.additionalInformation)) {
            return null;
        }

        return Collections.unmodifiableList(this.additionalInformation);
    }

    public List<PaymentMethodVO> getPaymentMethods() {
        if (isNull(this.paymentMethods)) {
            return null;
        }

        return Collections.unmodifiableList(paymentMethods);
    }

}
