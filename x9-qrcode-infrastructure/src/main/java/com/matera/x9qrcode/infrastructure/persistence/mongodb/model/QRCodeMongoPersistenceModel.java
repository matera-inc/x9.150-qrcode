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

    @Override
    public boolean isNew() {
        return isNull(revision);
    }

    @Id
    private UUID id;

    @Indexed(unique = true)
    private UUID locationId;

    // TODO: consider a custom bean to allow TTL customization
    @Field(name = "ttl")
    @Indexed(expireAfter = "30S")
    private Instant ttl;

    @Version
    private Integer revision;

    @Field(name = "valid_until")
    private OffsetDateTime validUntil;

    @Field(name = "created_at")
    private OffsetDateTime createdAt;

    @Field(name = "revised_at")
    private OffsetDateTime revisedAt;

    @Field(name = "status")
    private String status;

    @Field(name = "creditor")
    private Creditor creditor;

    @Field(name = "bill")
    private Bill bill;

    @Field(name = "unstructured")
    private String unstructured;

    @Field(name = "additional_information")
    private Map<String, String> additionalInformation;

    @Field(name = "payment_notification")
    private PaymentNotification paymentNotification;

    @Field(name = "payment_method")
    private List<PaymentMethod> paymentMethods;

    @Field(name = "payment_details")
    private PaymentDetails paymentDetails;

    @Field(name = "qrcode_emv")
    private String qrcodeEmv;

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
    public static class Networks {

        private BankPaymentAddress fedNow;
        private BankPaymentAddress ach;
        private BankPaymentAddress rtp;
        private CryptoWalletPaymentAddress polygon;
        private CryptoWalletPaymentAddress solana;
        private CryptoWalletPaymentAddress ethereum;
        private CryptoWalletPaymentAddress bitcoin;
        private CryptoWalletPaymentAddress base;
        private CryptoWalletPaymentAddress xrp;
        private CryptoWalletPaymentAddress arc;
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

}
