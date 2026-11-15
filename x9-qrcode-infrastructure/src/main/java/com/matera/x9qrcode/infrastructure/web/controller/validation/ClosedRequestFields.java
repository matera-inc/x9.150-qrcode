/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.validation;

import com.matera.x9qrcode.infrastructure.generated.dto.ACHDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AddressDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentParametersDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentParametersDiscountsInnerDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentParametersLateFeesDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AmountDueDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillBaseInvoiceDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillBaseOrderDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillBaseTipDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.CreditorDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.CreditorUltimateCreditorAccountDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.CreditorUltimateCreditorDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.FedNowDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.IntegerRangeDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.InvoiceeDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.KeyValuePairDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationDataBlockchainDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationDataDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationDataPayerDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationDataPaymentDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchAddressDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchAdjustmentDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchAdjustmentParametersDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchAmountDueDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchBillUpdateDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchEditableDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchInvoiceDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchInvoiceeDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchOrderDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchPaymentMethodDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchPaymentRequestReplacementDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchTipDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentMethodDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentMethodEditableDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestInputDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.RTPDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.SolanaDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.TipRangeDTO;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Notes every unknown field found, at any level, in a body we generate from while it is read: a
 * create, a PATCH, and an outbound payment notification.
 *
 * <p>What we generate is closed (INTERPRETATION I-16, owner 2026-11-15): every object of those three
 * bodies is {@code additionalProperties: false} in the contract, from the top level down to the rails.
 * The one open object is {@code networks}, which must receive an unknown network to refuse it by name
 * (I-9); it is not listed here. The service's ObjectMapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * disabled, which on its own drops such a field without a word.
 *
 * <p>The field is not refused here, mid-parse, because that would stop the read and hide every other
 * violation of the same body. It is recorded on the current request, with its path and its message,
 * and {@link ClosedRequestFieldsValidator} adds it to the body's validation result, beside the rest.
 *
 * <p>Only the request-side types are watched. A payload we read (the location's payload, the EMV
 * decoder's, another bank's payment notification) uses other types ({@code Payload*},
 * {@code PaymentNotificationData}), which are open: there an unknown field is dropped, never refused.
 * Outside a web request nothing is recorded.
 */
public final class ClosedRequestFields extends DeserializationProblemHandler {

    private static final List<String> BANK_FIELDS = List.of("routingNumber", "accountNumber", "protectionType");
    private static final List<String> ADDRESS = List.of("line1", "line2", "city", "state", "postalCode", "country");
    private static final List<String> BILL =
        List.of("paymentTiming", "description", "order", "invoice", "tip", "amountDue");
    private static final List<String> ORDER = List.of("number", "date");
    private static final List<String> INVOICE = List.of("number", "date", "dueDate", "invoicee");
    private static final List<String> PARTY = List.of("name", "phone", "email", "address");
    private static final List<String> TIP = List.of("allowed", "range", "presets");
    private static final List<String> RANGE = List.of("min", "max");
    private static final List<String> ADJUSTMENTS = List.of("formula", "parameters");
    private static final List<String> PARAMETERS = List.of("discounts", "lateFees");
    private static final List<String> PAYMENT_METHOD = List.of("currency", "validUntil", "amount", "editable", "networks");

    /**
     * Each closed object: the name a refusal gives it, and its fields, in the contract's order. The
     * Go build answers with the same words ({@code internal/contract}); a test keeps each list equal to
     * the generated type's own properties.
     */
    static final Map<Class<?>, Closed> OBJECTS = Map.ofEntries(
        // create
        Map.entry(PaymentRequestInputDTO.class, new Closed("the payment request", List.of("validUntil", "creditor",
            "bill", "locationId", "unstructured", "additionalInformation", "paymentNotification", "paymentMethods"))),
        Map.entry(CreditorDTO.class, new Closed("creditor",
            List.of("name", "phone", "email", "address", "ultimateCreditor", "MCC"))),
        Map.entry(CreditorUltimateCreditorDTO.class, new Closed("ultimateCreditor",
            List.of("account", "name", "phone", "email", "address"))),
        Map.entry(CreditorUltimateCreditorAccountDTO.class, new Closed("account", List.of("id", "schemaName"))),
        Map.entry(AddressDTO.class, new Closed("address", ADDRESS)),
        Map.entry(BillDTO.class, new Closed("bill", BILL)),
        Map.entry(BillBaseOrderDTO.class, new Closed("order", ORDER)),
        Map.entry(BillBaseInvoiceDTO.class, new Closed("invoice", INVOICE)),
        Map.entry(InvoiceeDTO.class, new Closed("invoicee", PARTY)),
        Map.entry(BillBaseTipDTO.class, new Closed("tip", TIP)),
        Map.entry(TipRangeDTO.class, new Closed("range", RANGE)),
        Map.entry(AmountDueDTO.class, new Closed("amountDue", List.of("amount", "currency", "adjustments"))),
        Map.entry(AdjustmentDTO.class, new Closed("adjustments", ADJUSTMENTS)),
        // The PATCH's discounts and lateFees are the same generated types as the create's.
        Map.entry(AdjustmentParametersDTO.class, new Closed("parameters", PARAMETERS)),
        Map.entry(AdjustmentParametersDiscountsInnerDTO.class, new Closed("a discount",
            List.of("daysBefore", "discount", "explanation"))),
        Map.entry(AdjustmentParametersLateFeesDTO.class, new Closed("lateFees",
            List.of("fixed", "perDay", "explanation"))),
        Map.entry(KeyValuePairDTO.class, new Closed("an additionalInformation entry", List.of("key", "value"))),
        Map.entry(PaymentNotificationDTO.class, new Closed("paymentNotification", List.of("kind", "endpoint"))),
        Map.entry(PaymentMethodDTO.class, new Closed("a payment method", PAYMENT_METHOD)),
        Map.entry(PaymentMethodEditableDTO.class, new Closed("editable", List.of("range"))),
        Map.entry(IntegerRangeDTO.class, new Closed("range", RANGE)),
        Map.entry(FedNowDTO.class, new Closed("fednow", BANK_FIELDS)),
        Map.entry(RTPDTO.class, new Closed("rtp", BANK_FIELDS)),
        Map.entry(ACHDTO.class, new Closed("ach", BANK_FIELDS)),
        Map.entry(SolanaDTO.class, new Closed("solana", List.of("recipient", "memo"))),
        // PATCH
        Map.entry(PatchPaymentRequestReplacementDTO.class, new Closed("the payment request", List.of("locationId",
            "validUntil", "bill", "unstructured", "additionalInformation", "paymentMethods"))),
        Map.entry(PatchBillUpdateDTO.class, new Closed("bill", BILL)),
        Map.entry(PatchOrderDTO.class, new Closed("order", ORDER)),
        Map.entry(PatchInvoiceDTO.class, new Closed("invoice", INVOICE)),
        Map.entry(PatchInvoiceeDTO.class, new Closed("invoicee", PARTY)),
        Map.entry(PatchAddressDTO.class, new Closed("address", ADDRESS)),
        Map.entry(PatchTipDTO.class, new Closed("tip", TIP)),
        Map.entry(PatchAmountDueDTO.class, new Closed("amountDue", List.of("amount", "adjustments"))),
        Map.entry(PatchAdjustmentDTO.class, new Closed("adjustments", ADJUSTMENTS)),
        Map.entry(PatchAdjustmentParametersDTO.class, new Closed("parameters", PARAMETERS)),
        Map.entry(PatchPaymentMethodDTO.class, new Closed("a payment method", PAYMENT_METHOD)),
        Map.entry(PatchEditableDTO.class, new Closed("editable", List.of("range"))),
        // the outbound payment notification
        Map.entry(OutboundPaymentNotificationDTO.class, new Closed("the outbound notification",
            List.of("endpoint", "correlationId", "notification"))),
        Map.entry(OutboundPaymentNotificationDataDTO.class, new Closed("notification",
            List.of("payment", "payer", "expectedDate", "blockchain"))),
        Map.entry(OutboundPaymentNotificationDataPaymentDTO.class, new Closed("payment",
            List.of("qrcodeId", "amount", "tipAmount", "currency", "network", "transactionId"))),
        Map.entry(OutboundPaymentNotificationDataPayerDTO.class, new Closed("payer", List.of("info"))),
        Map.entry(OutboundPaymentNotificationDataBlockchainDTO.class, new Closed("blockchain",
            List.of("action", "from", "to"))));

    private static final String ATTRIBUTE = ClosedRequestFields.class.getName() + ".violations";
    private static final String READ_ONLY_ATTRIBUTE = ClosedRequestFields.class.getName() + ".readOnly";

    /**
     * What a GET answer carries at a level a PATCH accepts, but a PATCH cannot change (owner,
     * 2026-11-15, option B): not refused while the body is read, but kept with its value, and
     * compared with the stored one by {@link ReadOnlyPatchFields}. A body copied from GET keeps
     * working; an attempt to change one of them is refused by name.
     */
    static final Map<Class<?>, Set<String>> READ_ONLY = Map.of(
        PatchPaymentRequestReplacementDTO.class, Set.of("id", "qrCode", "qrCodeB64", "location", "creditor",
            "paymentNotification", "revision", "createdAt", "revisedAt", "sentAt", "status", "paymentDetails", "emv"),
        PatchAmountDueDTO.class, Set.of("currency"));

    /** A closed object: what a refusal calls it, and its own fields as the refusal lists them. */
    record Closed(String name, List<String> fields) {

        Closed {
            fields = List.copyOf(fields);
        }

        /**
         * {@code is not a field of <name>, whose fields are a, b and c} — word for word what the Go
         * build answers; {@code whose only field is a} when there is one.
         */
        String message() {
            if (fields.size() == 1) {
                return "is not a field of %s, whose only field is %s".formatted(name, fields.getFirst());
            }
            String listed = String.join(", ", fields.subList(0, fields.size() - 1)) + " and " + fields.getLast();
            return "is not a field of %s, whose fields are %s".formatted(name, listed);
        }
    }

    /** One unknown field: where it is, and what is said about it. */
    record Violation(String path, String message) {
    }

    /** A read-only member a PATCH carried: where it is, and the value sent. */
    record ReadOnly(String path, JsonNode value) {
    }

    @Override
    public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p, JsonDeserializer<?> deserializer,
                                         Object beanOrClass, String propertyName) throws IOException {
        Class<?> type = beanOrClass instanceof Class<?> c ? c : isNull(beanOrClass) ? null : beanOrClass.getClass();
        Closed closed = isNull(type) ? null : OBJECTS.get(type);
        if (isNull(closed)) {
            return false;
        }

        if (READ_ONLY.getOrDefault(type, Set.of()).contains(propertyName)) {
            JsonNode value = ctxt.readTree(p);
            record(READ_ONLY_ATTRIBUTE, new ReadOnly(pathOf(p.getParsingContext(), propertyName), value));
            return true;
        }

        p.skipChildren();

        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (nonNull(request)) {
            @SuppressWarnings("unchecked")
            List<Violation> found = (List<Violation>) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (isNull(found)) {
                found = new ArrayList<>();
                request.setAttribute(ATTRIBUTE, found, RequestAttributes.SCOPE_REQUEST);
            }
            found.add(new Violation(pathOf(p.getParsingContext(), propertyName), closed.message()));
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private static <T> void record(String attribute, T item) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (isNull(request)) {
            return;
        }
        List<T> found = (List<T>) request.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST);
        if (isNull(found)) {
            found = new ArrayList<>();
            request.setAttribute(attribute, found, RequestAttributes.SCOPE_REQUEST);
        }
        found.add(item);
    }

    /** The read-only members the current request carried, in the order read. Empty when none. */
    static List<ReadOnly> readOnlyRecorded() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (isNull(request)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<ReadOnly> found = (List<ReadOnly>) request.getAttribute(READ_ONLY_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return isNull(found) ? List.of() : Collections.unmodifiableList(found);
    }

    /** The unknown fields recorded on the current request. Empty when there are none. */
    static List<Violation> recorded() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (isNull(request)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Violation> found = (List<Violation>) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return isNull(found) ? List.of() : Collections.unmodifiableList(found);
    }

    /**
     * The field's path in the notation of every other violation: {@code paymentMethods[0].networks.fednow.x},
     * {@code creditor.address.x}, or {@code x} at the top level.
     *
     * <p>{@code context} is the closed object itself. Its own current name may or may not be the
     * unknown field yet, so the walk starts at its parent and {@code propertyName} is appended.
     */
    static String pathOf(JsonStreamContext context, String propertyName) {
        StringBuilder path = new StringBuilder();
        for (JsonStreamContext c = context.getParent(); nonNull(c) && !c.inRoot(); c = c.getParent()) {
            if (c.inArray()) {
                path.insert(0, "[" + c.getCurrentIndex() + "]");
            } else if (nonNull(c.getCurrentName())) {
                path.insert(0, "." + c.getCurrentName());
            }
        }
        path.append('.').append(propertyName);
        return path.charAt(0) == '.' ? path.substring(1) : path.toString();
    }

}
