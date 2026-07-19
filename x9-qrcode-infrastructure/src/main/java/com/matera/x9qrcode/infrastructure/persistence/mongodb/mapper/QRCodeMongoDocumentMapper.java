/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.persistence.mongodb.mapper;

import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.vo.AccountVO;
import com.matera.x9qrcode.domain.vo.AddressVO;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.AdjustmentVO;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.AmountRangeVO;
import com.matera.x9qrcode.domain.vo.BankPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.BlockchainVO;
import com.matera.x9qrcode.domain.vo.CreditorVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.EditableAmountVO;
import com.matera.x9qrcode.domain.vo.InvoiceVO;
import com.matera.x9qrcode.domain.vo.InvoiceeVO;
import com.matera.x9qrcode.domain.vo.LateFeesVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.OrderVO;
import com.matera.x9qrcode.domain.vo.PaymentDetailsVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPayerVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPaymentVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.TipRangeVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.UltimateCreditorVO;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.model.QRCodeMongoPersistenceModel;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class QRCodeMongoDocumentMapper {

    public static QRCodeMongoPersistenceModel map(QRCodeEntity entity) {
        QRCodeMongoPersistenceModel qrCodeMongoPersistenceModel = new QRCodeMongoPersistenceModel();
        qrCodeMongoPersistenceModel.setId(entity.getId().value());
        qrCodeMongoPersistenceModel.setLocationId(entity.getLocationId().value());
        qrCodeMongoPersistenceModel.setTtl(entity.getValidUntil().toInstant());
        qrCodeMongoPersistenceModel.setRevision(entity.getRevision());
        qrCodeMongoPersistenceModel.setCreatedAt(entity.getCreatedAt());
        qrCodeMongoPersistenceModel.setRevisedAt(entity.getRevisedAt());
        qrCodeMongoPersistenceModel.setValidUntil(entity.getValidUntil());
        qrCodeMongoPersistenceModel.setStatus(entity.getStatus().value());
        qrCodeMongoPersistenceModel.setCreditor(buildCreditor(entity.getCreditor()));
        qrCodeMongoPersistenceModel.setBill(buildBill(entity.getBill()));
        qrCodeMongoPersistenceModel.setUnstructured(entity.getUnstructured().value());
        qrCodeMongoPersistenceModel.setAdditionalInformation(entity.getAdditionalInformation());
        qrCodeMongoPersistenceModel.setPaymentNotification(buildPaymentNotification(entity.getPaymentNotification()));
        qrCodeMongoPersistenceModel.setPaymentMethods(
            entity.getPaymentMethods().stream().map(QRCodeMongoDocumentMapper::buildPaymentMethod).toList());
        qrCodeMongoPersistenceModel.setPaymentDetails(buildPaymentDetails(entity.getPaymentDetails()));
        qrCodeMongoPersistenceModel.setQrcodeEmv(entity.getQrcodeContent().value());

        return qrCodeMongoPersistenceModel;
    }

    private static QRCodeMongoPersistenceModel.Creditor buildCreditor(CreditorVO creditor) {
        QRCodeMongoPersistenceModel.Creditor creditorDocument = new QRCodeMongoPersistenceModel.Creditor();
        creditorDocument.setName(creditor.name().value());
        creditorDocument.setEmail(creditor.email().value());
        creditorDocument.setPhone(creditor.phone().value());
        creditorDocument.setAddress(buildAddress(creditor.address()));
        creditorDocument.setUltimateCreditor(buildUltimateCreditor(creditor.ultimateCreditor()));
        creditorDocument.setMerchantCategoryCode(creditor.merchantCategoryCode().value());

        return creditorDocument;
    }

    private static QRCodeMongoPersistenceModel.UltimateCreditor buildUltimateCreditor(UltimateCreditorVO ultimateCreditor) {
        if (isNull(ultimateCreditor)) {
            return null;
        }

        QRCodeMongoPersistenceModel.UltimateCreditor ultimateCreditorDocument =
            new QRCodeMongoPersistenceModel.UltimateCreditor();
        ultimateCreditorDocument.setAccount(buildAccount(ultimateCreditor.account()));
        ultimateCreditorDocument.setName(ultimateCreditor.name());
        ultimateCreditorDocument.setEmail(ultimateCreditor.email());
        ultimateCreditorDocument.setPhone(ultimateCreditor.phone());
        ultimateCreditorDocument.setAddress(buildAddress(ultimateCreditor.address()));

        return ultimateCreditorDocument;
    }

    private static QRCodeMongoPersistenceModel.Bill buildBill(BillVO bill) {
        QRCodeMongoPersistenceModel.Bill billDocument = new QRCodeMongoPersistenceModel.Bill();
        billDocument.setDescription(bill.description().value());
        billDocument.setInvoice(buildInvoice(bill.invoice()));
        billDocument.setOrder(buildOrder(bill.order()));
        billDocument.setTip(buildTip(bill.tip()));
        billDocument.setAmountDue(buildAmountDue(bill.amountDue()));
        billDocument.setPaymentTiming(bill.paymentTiming().value());

        return billDocument;
    }

    private static QRCodeMongoPersistenceModel.Tip buildTip(TipVO tip) {
        if (isNull(tip)) {
            return null;
        }

        QRCodeMongoPersistenceModel.Tip tipDocument = new QRCodeMongoPersistenceModel.Tip();
        tipDocument.setRange(buildTipRange(tip.range()));
        tipDocument.setPresets(tip.presets());
        tipDocument.setAllowed(tip.allowed());

        return tipDocument;
    }

    private static QRCodeMongoPersistenceModel.Range buildTipRange(TipRangeVO tipRange) {
        if (isNull(tipRange)) {
            return null;
        }

        QRCodeMongoPersistenceModel.Range rangeDocument = new QRCodeMongoPersistenceModel.Range();
        rangeDocument.setMin(tipRange.minimum());
        rangeDocument.setMax(tipRange.maximum());

        return rangeDocument;
    }

    private static QRCodeMongoPersistenceModel.Account buildAccount(AccountVO account) {
        QRCodeMongoPersistenceModel.Account accountDocument = new QRCodeMongoPersistenceModel.Account();
        accountDocument.setId(account.id());
        accountDocument.setSchemaName(account.schemaName());

        return accountDocument;
    }

    private static QRCodeMongoPersistenceModel.Address buildAddress(AddressVO address) {
        if (isNull(address)) {
            return null;
        }

        QRCodeMongoPersistenceModel.Address addressDocument = new QRCodeMongoPersistenceModel.Address();
        addressDocument.setLine1(address.line1());
        addressDocument.setLine2(address.line2());
        addressDocument.setCity(address.city());
        addressDocument.setState(address.state());
        addressDocument.setCountry(address.country());
        addressDocument.setPostalCode(address.postalCode());

        return addressDocument;
    }

    private static QRCodeMongoPersistenceModel.Order buildOrder(OrderVO order) {
        if (isNull(order)) {
            return null;
        }

        QRCodeMongoPersistenceModel.Order orderDocument = new QRCodeMongoPersistenceModel.Order();
        orderDocument.setNumber(order.number());
        orderDocument.setDate(order.date());

        return orderDocument;
    }

    private static QRCodeMongoPersistenceModel.Invoice buildInvoice(InvoiceVO invoice) {
        if (isNull(invoice)) {
            return null;
        }

        QRCodeMongoPersistenceModel.Invoice invoiceDocument = new QRCodeMongoPersistenceModel.Invoice();
        invoiceDocument.setNumber(invoice.number().value());
        invoiceDocument.setDate(invoice.date());
        invoiceDocument.setDueDate(invoice.dueDate());
        invoiceDocument.setInvoicee(buildInvoicee(invoice.invoicee()));
        return invoiceDocument;
    }

    private static QRCodeMongoPersistenceModel.AmountDue buildAmountDue(AmountDueVO amountDue) {
        if (isNull(amountDue)) {
            return null;
        }

        QRCodeMongoPersistenceModel.AmountDue amountDueDocument = new QRCodeMongoPersistenceModel.AmountDue();
        amountDueDocument.setAmount(amountDue.currencyAmount().amount());
        amountDueDocument.setCurrency(amountDue.currencyAmount().currency());
        amountDueDocument.setAdjustment(buildAdjustment(amountDue.adjustment()));

        return amountDueDocument;
    }

    private static QRCodeMongoPersistenceModel.Adjustment buildAdjustment(AdjustmentVO adjustment) {
        QRCodeMongoPersistenceModel.Adjustment adjustmentDocument = new QRCodeMongoPersistenceModel.Adjustment();

        if (isNull(adjustment)) {
            return null;
        }

        adjustmentDocument.setFormula(adjustment.formula().value());
        adjustmentDocument.setParameters(buildAdjustmentParameters(adjustment.parameters()));

        return adjustmentDocument;
    }

    private static QRCodeMongoPersistenceModel.AdjustmentParameters buildAdjustmentParameters(AdjustmentParametersVO adjustmentParameters) {
        QRCodeMongoPersistenceModel.AdjustmentParameters adjustmentParametersDocument =
            new QRCodeMongoPersistenceModel.AdjustmentParameters();

        List<DiscountVO> discounts = adjustmentParameters.discounts();

        adjustmentParametersDocument.setDiscounts(isNull(discounts) || discounts.isEmpty()
            ? null
            : discounts.stream().map(QRCodeMongoDocumentMapper::buildDiscount).toList());
        adjustmentParametersDocument.setLateFees(buildLateFees(adjustmentParameters.lateFees()));

        return adjustmentParametersDocument;
    }

    private static QRCodeMongoPersistenceModel.Discount buildDiscount(DiscountVO discount) {
        QRCodeMongoPersistenceModel.Discount discountDocument = new QRCodeMongoPersistenceModel.Discount();
        discountDocument.setDaysBefore(discount.daysBefore());
        discountDocument.setDiscount(discount.discount());
        discountDocument.setExplanation(discount.explanation());

        return discountDocument;
    }

    private static QRCodeMongoPersistenceModel.LateFees buildLateFees(LateFeesVO lateFees) {
        QRCodeMongoPersistenceModel.LateFees lateFeesDocument = new QRCodeMongoPersistenceModel.LateFees();
        lateFeesDocument.setFixed(lateFees.fixed());
        lateFeesDocument.setPerDay(lateFees.perDay());
        lateFeesDocument.setExplanation(lateFees.explanation());

        return lateFeesDocument;
    }

    private static QRCodeMongoPersistenceModel.Editable buildEditable(EditableAmountVO editableAmount) {
        if (isNull(editableAmount)) {
            return null;
        }

        QRCodeMongoPersistenceModel.Editable editableDocument = new QRCodeMongoPersistenceModel.Editable();
        editableDocument.setRange(buildRange(editableAmount.range()));

        return editableDocument;
    }

    private static QRCodeMongoPersistenceModel.AmountRange buildRange(AmountRangeVO amountRange) {
        if (isNull(amountRange)) {
            return null;
        }

        QRCodeMongoPersistenceModel.AmountRange amountRangeDocument = new QRCodeMongoPersistenceModel.AmountRange();
        amountRangeDocument.setMin(amountRange.minAmount());
        amountRangeDocument.setMax(amountRange.maxAmount());

        return amountRangeDocument;
    }

    private static QRCodeMongoPersistenceModel.PaymentMethod buildPaymentMethod(PaymentMethodVO paymentMethod) {
        QRCodeMongoPersistenceModel.PaymentMethod paymentMethodDocument = new QRCodeMongoPersistenceModel.PaymentMethod();
        paymentMethodDocument.setCurrency(paymentMethod.currency());
        paymentMethodDocument.setValidUntil(paymentMethod.validUntil());
        paymentMethodDocument.setAmount(paymentMethod.amount().value());
        paymentMethodDocument.setEditable(buildEditable(paymentMethod.editable()));
        paymentMethodDocument.setNetworks(buildNetworks(paymentMethod.networks()));

        return paymentMethodDocument;
    }

    private static QRCodeMongoPersistenceModel.PaymentDetails buildPaymentDetails(PaymentDetailsVO paymentDetails) {
        if (isNull(paymentDetails)) {
            return null;
        }

        QRCodeMongoPersistenceModel.PaymentDetails paymentDetailsDocument = new QRCodeMongoPersistenceModel.PaymentDetails();
        paymentDetailsDocument.setEndToEndId(paymentDetails.endToEndId());
        paymentDetailsDocument.setPaymentNetwork(isNull(paymentDetails.paymentNetwork()) ? null : paymentDetails.paymentNetwork().value());

        return paymentDetailsDocument;
    }

    private static QRCodeMongoPersistenceModel.Networks buildNetworks(NetworksVO networks) {
        QRCodeMongoPersistenceModel.Networks networksDocument = new QRCodeMongoPersistenceModel.Networks();

        networksDocument.setFedNow(buildBankPaymentAddress(networks.fedNow()));
        networksDocument.setAch(buildBankPaymentAddress(networks.ach()));
        networksDocument.setRtp(buildBankPaymentAddress(networks.rtp()));
        networksDocument.setPolygon(buildCryptoWalletPaymentAddress(networks.polygon()));
        networksDocument.setSolana(buildCryptoWalletPaymentAddress(networks.solana()));
        networksDocument.setEthereum(buildCryptoWalletPaymentAddress(networks.ethereum()));
        networksDocument.setBitcoin(buildCryptoWalletPaymentAddress(networks.bitcoin()));
        networksDocument.setBase(buildCryptoWalletPaymentAddress(networks.base()));
        networksDocument.setXrp(buildCryptoWalletPaymentAddress(networks.xrp()));
        networksDocument.setArc(buildCryptoWalletPaymentAddress(networks.arc()));
        networksDocument.setAdditionalProperties(networks.additionalProperties());

        return networksDocument;
    }

    private static QRCodeMongoPersistenceModel.BankPaymentAddress buildBankPaymentAddress(BankPaymentAddressVO bankPaymentAddress) {
        if (isNull(bankPaymentAddress)) {
            return null;
        }

        QRCodeMongoPersistenceModel.BankPaymentAddress bankPaymentAddressDocument = new QRCodeMongoPersistenceModel.BankPaymentAddress();
        bankPaymentAddressDocument.setRoutingNumber(bankPaymentAddress.routingNumber());
        bankPaymentAddressDocument.setAccountNumber(bankPaymentAddress.accountNumber());

        return bankPaymentAddressDocument;
    }


    private static QRCodeMongoPersistenceModel.Invoicee buildInvoicee(InvoiceeVO invoicee) {
        if (isNull(invoicee)) {
            return null;
        }

        QRCodeMongoPersistenceModel.Invoicee invoiceeDocument = new QRCodeMongoPersistenceModel.Invoicee();
        invoiceeDocument.setName(invoicee.name().value());
        invoiceeDocument.setPhone(invoicee.phone().value());
        invoiceeDocument.setEmail(invoicee.email().value());
        invoiceeDocument.setAddress(buildAddress(invoicee.address()));

        return invoiceeDocument;
    }

    private static QRCodeMongoPersistenceModel.PaymentNotification buildPaymentNotification(PaymentNotificationVO paymentNotification) {
        if (isNull(paymentNotification)) {
            return null;
        }

        QRCodeMongoPersistenceModel.PaymentNotification paymentNotificationDocument = new QRCodeMongoPersistenceModel.PaymentNotification();
        paymentNotificationDocument.setKind(paymentNotification.kind().name());
        paymentNotificationDocument.setEndpoint(paymentNotification.endpoint());
        paymentNotificationDocument.setData(buildPaymentNotificationData(paymentNotification.data()));

        return paymentNotificationDocument;
    }

    private static QRCodeMongoPersistenceModel.PaymentNotificationData buildPaymentNotificationData(
        PaymentNotificationDataVO paymentNotificationData) {
        if (isNull(paymentNotificationData)) {
            return null;
        }

        QRCodeMongoPersistenceModel.PaymentNotificationData paymentNotificationDataDocument =
            new QRCodeMongoPersistenceModel.PaymentNotificationData();
        paymentNotificationDataDocument.setPayment(buildPaymentNotificationPayment(paymentNotificationData.payment()));
        paymentNotificationDataDocument.setPayer(buildPaymentNotificationPayer(paymentNotificationData.payer()));
        paymentNotificationDataDocument.setExpectedDate(paymentNotificationData.expectedDate().value());
        paymentNotificationDataDocument.setBlockchain(buildPaymentNotificationBlockchain(paymentNotificationData.blockchain()));

        return paymentNotificationDataDocument;
    }

    private static QRCodeMongoPersistenceModel.PaymentNotificationPayment buildPaymentNotificationPayment(
        PaymentNotificationPaymentVO payment) {
        QRCodeMongoPersistenceModel.PaymentNotificationPayment paymentDocument =
            new QRCodeMongoPersistenceModel.PaymentNotificationPayment();
        paymentDocument.setAmount(payment.amount().value());

        if (nonNull(payment.tipAmount())) {
            paymentDocument.setTipAmount(payment.tipAmount().value());
        }

        paymentDocument.setCurrency(payment.currency());
        paymentDocument.setNetwork(payment.network().value());
        paymentDocument.setTransactionId(payment.transactionId());

        return paymentDocument;
    }

    private static QRCodeMongoPersistenceModel.PaymentNotificationPayer buildPaymentNotificationPayer(
        PaymentNotificationPayerVO payer) {
        if (isNull(payer)) {
            return null;
        }

        QRCodeMongoPersistenceModel.PaymentNotificationPayer payerDocument =
            new QRCodeMongoPersistenceModel.PaymentNotificationPayer();
        payerDocument.setInfo(payer.info());

        return payerDocument;
    }

    private static QRCodeMongoPersistenceModel.PaymentNotificationBlockchain buildPaymentNotificationBlockchain(
        BlockchainVO blockchain) {
        if (isNull(blockchain)) {
            return null;
        }

        QRCodeMongoPersistenceModel.PaymentNotificationBlockchain blockchainDocument =
            new QRCodeMongoPersistenceModel.PaymentNotificationBlockchain();
        blockchainDocument.setAction(blockchain.action().value());
        blockchainDocument.setFrom(blockchain.from().walletAddress());
        blockchainDocument.setTo(blockchain.to().walletAddress());

        return blockchainDocument;
    }

    private static QRCodeMongoPersistenceModel.CryptoWalletPaymentAddress buildCryptoWalletPaymentAddress(
        CryptoWalletPaymentAddressVO cryptoWalletPaymentAddress) {
        if (isNull(cryptoWalletPaymentAddress)) {
            return null;
        }

        QRCodeMongoPersistenceModel.CryptoWalletPaymentAddress cryptoWalletPaymentAddressDocument =
            new QRCodeMongoPersistenceModel.CryptoWalletPaymentAddress();
        cryptoWalletPaymentAddressDocument.setWalletAddress(cryptoWalletPaymentAddress.walletAddress());

        return cryptoWalletPaymentAddressDocument;
    }

}
