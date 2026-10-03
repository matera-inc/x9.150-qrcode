/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.persistence.mongodb.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.Objects.isNull;

@Data
@Document(collection = "qrcodes")
public class QRCodeMongoPersistenceModel implements Persistable<UUID> {

    /**
     * Whether this document has never been stored.
     *
     * <p>Keyed on the LOCK token, not on {@link #revision}. A document that has never been saved has
     * no lock token; a brand-new payment request, by contrast, legitimately starts at revision 0,
     * and reading newness from that made Spring Data take a first save for an update.
     */
    @Override
    public boolean isNew() {
        return isNull(lockVersion);
    }

    @Id
    private UUID id;

    @Indexed(unique = true)
    private UUID locationId;

    // TODO: consider a custom bean to allow TTL customization
    @Field(name = "ttl")
    @Indexed(expireAfter = "30S")
    private Instant ttl;

    /**
     * The optimistic-lock token, managed by Spring Data and never exposed.
     *
     * <p>Separate from {@link #revision} on purpose. It moves on EVERY save — a status transition,
     * a drained event, anything — because that is what a lost-update check needs. `revision` moves
     * only when the payment request's DATA changes, because that is what the word means to a biller.
     * One field cannot honestly be both.
     */
    @Version
    private Integer lockVersion;

    /**
     * The payment request's own version: incremented when its DATA changes, and only then.
     *
     * <p>Marking a request PAID or CANCELLED does not make it a new version of the request. It is
     * the same request with a new status. A new version appears when a caller changes what is being
     * asked for — the amount due, the due date — through PATCH.
     */
    private Integer revision;

    @Field(name = "valid_until")
    private OffsetDateTime validUntil;

    @Field(name = "created_at")
    private OffsetDateTime createdAt;

    @Field(name = "revised_at")
    private OffsetDateTime revisedAt;

    /**
     * When a PAYMENT_INITIATED reservation stops counting; null in every other state.
     *
     * <p>Persisted so a restart does not reset every reservation's clock — a redeploy would
     * otherwise hand every payer a fresh 90 seconds, or, if it were held in memory only, drop
     * every reservation at once.
     */
    @Field(name = "initiated_expires_at")
    private OffsetDateTime initiatedExpiresAt;

    /**
     * Who holds the current reservation; null in every state but PAYMENT_INITIATED.
     *
     * <p>Persisted for the same reason as the instant above: a restart must not forget who is
     * holding a QR Code, or every payer mid-announcement becomes a stranger to it and cannot
     * report what they did.
     */
    @Field(name = "reserved_by")
    private ReservedBy reservedBy;

    @Field(name = "status")
    private String status;

    @Field(name = "creditor")
    private Creditor creditor;

    @Field(name = "bill")
    private Bill bill;

    @Field(name = "unstructured")
    private String unstructured;

    /**
     * The labelled lines, stored as a LIST so repeats survive.
     *
     * <p>Written to a new field rather than reusing {@code additional_information}, which older
     * documents hold as an object. Changing the shape under the same name would make every existing
     * QR Code unreadable; this way both are readable and nothing has to be migrated.
     */
    @Field(name = "additional_information_entries")
    private List<AdditionalInformation> additionalInformation;

    /**
     * How the field was stored before it was understood to be a list. Read-only: never written
     * again, and mapped forward when an old document is loaded. Remove once no document carries it.
     */
    @Field(name = "additional_information")
    private Map<String, String> legacyAdditionalInformation;

    @Field(name = "payment_notification")
    private PaymentNotification paymentNotification;

    @Field(name = "payment_method")
    private List<PaymentMethod> paymentMethods;

    @Field(name = "payment_details")
    private PaymentDetails paymentDetails;

    @Field(name = "qrcode_emv")
    private String qrcodeEmv;

    /**
     * Events produced by state changes, written in the same document as the change itself.
     *
     * <p>Embedded rather than a separate collection so the two are one atomic write: an event cannot
     * be lost without also losing the state change that caused it, and cannot be published for a
     * state change that did not happen — with no multi-document transaction.
     */
    @Field(name = "outbox")
    private List<OutboxEvent> outbox;

    @Data
    public static class OutboxEvent {

        @Field(name = "event_id")
        private UUID eventId;

        @Field(name = "type")
        private String type;

        @Field(name = "occurred_at")
        private Instant occurredAt;

        @Field(name = "qrcode_revision")
        private Integer qrCodeRevision;

        @Field(name = "location_id")
        private String locationId;

        @Field(name = "amount")
        private Long amount;

        @Field(name = "tip_amount")
        private Long tipAmount;

        @Field(name = "payer_info")
        private String payerInfo;

        @Field(name = "currency")
        private String currency;

        @Field(name = "network")
        private String network;

        @Field(name = "transaction_id")
        private String transactionId;

        @Field(name = "invoice_number")
        private String invoiceNumber;

        @Field(name = "order_number")
        private String orderNumber;

        @Field(name = "reason")
        private String reason;

    }

    @Data
    public static class Creditor {

        @Field(name = "name")
        private String name;

        @Field(name = "phone")
        private String phone;

        @Field(name = "email")
        private String email;

        @Field(name = "address")
        private Address address;

        @Field(name = "ultimate_creditor")
        private UltimateCreditor ultimateCreditor;

        @Field(name = "merchant_category_code")
        private String merchantCategoryCode;

    }

    @Data
    public static class UltimateCreditor {

        @Field(name = "account")
        private Account account;

        @Field(name = "name")
        private String name;

        @Field(name = "phone")
        private String phone;

        @Field(name = "email")
        private String email;

        @Field(name = "address")
        private Address address;

    }

    @Data
    public static class Account {

        @Field(name = "id")
        private String id;

        @Field(name = "schema_name")
        private String schemaName;

    }

    @Data
    public static class Address {

        @Field(name = "line1")
        private String line1;

        @Field(name = "line2")
        private String line2;

        @Field(name = "city")
        private String city;

        @Field(name = "state")
        private String state;

        @Field(name = "postal_code")
        private String postalCode;

        @Field(name = "country")
        private String country;

    }

    @Data
    public static class Bill {

        @Field(name = "description")
        private String description;

        @Field(name = "order")
        private Order order;

        @Field(name = "invoice")
        private Invoice invoice;

        @Field(name = "tip")
        private Tip tip;

        @Field(name = "amount_due")
        private AmountDue amountDue;

        @Field(name = "payment_timing")
        private String paymentTiming;
    }

    @Data
    public static class Tip {

        @Field(name = "range")
        private Range range;

        @Field(name = "allowed")
        private Boolean allowed;

        @Field(name = "presets")
        private List<Integer> presets;

    }

    @Data
    public static class Invoice {

        @Field(name = "number")
        private String number;

        @Field(name = "description")
        private String description;

        @Field(name = "creation_date_time")
        private LocalDate date;

        @Field(name = "due_date")
        private OffsetDateTime dueDate;

        @Field(name = "invoicee")
        private Invoicee invoicee;

    }

    @Data
    public static class Invoicee {

        @Field(name = "name")
        private String name;

        @Field(name = "phone")
        private String phone;

        @Field(name = "email")
        private String email;

        @Field(name = "address")
        private Address address;

    }

    @Data
    public static class Order {

        @Field(name = "number")
        private String number;

        @Field(name = "order_date")
        private LocalDate date;

    }

    @Data
    public static class AmountDue {

        @Field(name = "amount")
        private Long amount;

        @Field(name = "currency")
        private String currency;

        @Field(name = "adjustments")
        private Adjustment adjustment;

    }

    @Data
    public static class Adjustment {

        @Field(name = "formula")
        private String formula;

        @Field(name = "parameters")
        private AdjustmentParameters parameters;

    }

    @Data
    public static class AdjustmentParameters {

        @Field(name = "discounts")
        private List<Discount> discounts;

        @Field(name = "late_fees")
        private LateFees lateFees;

    }

    @Data
    public static class Discount {
        @Field(name = "days_before")
        private Integer daysBefore;

        @Field(name = "discount")
        private Long discount;

        @Field(name = "explanation")
        private String explanation;
    }

    @Data
    public static class LateFees {
        @Field(name = "fixed")
        private Long fixed;

        @Field(name = "per_day")
        private Long perDay;

        @Field(name = "explanation")
        private String explanation;
    }

    @Data
    public static class Editable {


        @Field(name = "range")
        private AmountRange range;

    }

    @Data
    public static class Range {

        @Field(name = "min")
        private Integer min;

        @Field(name = "max")
        private Integer max;

    }

    @Data
    public static class AmountRange {

        @Field(name = "min")
        private Long min;

        @Field(name = "max")
        private Long max;

    }

    @Data
    public static class PaymentMethod {

        @Field(name = "currency")
        private String currency;

        @Field(name = "valid_until")
        private OffsetDateTime validUntil;

        @Field(name = "amount")
        private Long amount;

        @Field(name = "editable")
        private Editable editable;

        @Field(name = "networks")
        private Networks networks;

    }

    @Data
    public static class PaymentDetails {

        @Field(name = "end_to_end_id")
        private String endToEndId;

        @Field(name = "payment_network")
        private String paymentNetwork;

    }

    @Data
    public static class AdditionalInformation {

        /**
         * ANSI X9.150's name for it. It is a LABEL, not a unique index — repeats are legitimate and
         * are exactly why this is a list.
         */
        private String key;

        private String value;

    }

    @Data
    public static class PaymentNotification {
        @Field(name = "kind")
        private String kind;

        @Field(name = "endpoint")
        private URI endpoint;

        @Field(name = "data")
        private PaymentNotificationData data;
    }

    @Data
    public static class PaymentNotificationData {

        @Field(name = "payment")
        private PaymentNotificationPayment payment;

        @Field(name = "payer")
        private PaymentNotificationPayer payer;

        @Field(name = "expected_date")
        private OffsetDateTime expectedDate;

        @Field(name = "blockchain")
        private PaymentNotificationBlockchain blockchain;

    }

    @Data
    public static class PaymentNotificationPayment {

        @Field(name = "amount")
        private Long amount;

        @Field(name = "tip_amount")
        private Long tipAmount;


        @Field(name = "currency")
        private String currency;

        @Field(name = "network")
        private String network;

        @Field(name = "transaction_id")
        private String transactionId;

    }

    @Data
    public static class PaymentNotificationPayer {

        @Field(name = "info")
        private String info;

    }

    @Data
    public static class PaymentNotificationBlockchain {

        @Field(name = "action")
        private String action;

        @Field(name = "from")
        private String from;

        @Field(name = "to")
        private String to;

    }

    @Data
    public static class SolanaPaymentAddress {

        private String recipient;
        private String memo;

    }

    @Data
    public static class Networks {

        private BankPaymentAddress fedNow;
        private BankPaymentAddress ach;
        private BankPaymentAddress rtp;
        // Six unpublished chains used to sit here (polygon, ethereum, bitcoin, base, xrp, arc).
        // They were modelled ahead of any publication and removed with ADR-0010. Solana stays, in
        // the shape its own Foundation published: recipient + memo, not a bare walletAddress.
        private SolanaPaymentAddress solana;
        private Map<String, Object> additionalProperties;

    }

    @Data
    public static class BankPaymentAddress {

        @Field(name = "routing_number")
        private String routingNumber;

        @Field(name = "account_number")
        private String accountNumber;

    }

    @Data
    public static class CryptoWalletPaymentAddress {

        @Field(name = "wallet_address")
        private String walletAddress;

    }


    /**
     * The party holding a reservation: {@code payer.info} as sent, plus the subject of the
     * certificate that signed the announcement. See {@code ReservationHolderVO}.
     */
    @Data
    public static class ReservedBy {

        @Field(name = "payer_info")
        private String payerInfo;

        @Field(name = "signer_subject")
        private String signerSubject;

    }

}
