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
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BankPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.BlockchainVO;
import com.matera.x9qrcode.domain.vo.CreditorVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.EditableAmountVO;
import com.matera.x9qrcode.domain.vo.EmailVO;
import com.matera.x9qrcode.domain.vo.EmvVO;
import com.matera.x9qrcode.domain.vo.ExpectedDateVO;
import com.matera.x9qrcode.domain.vo.InvoiceVO;
import com.matera.x9qrcode.domain.vo.InvoiceeVO;
import com.matera.x9qrcode.domain.vo.LateFeesVO;
import com.matera.x9qrcode.domain.vo.NameVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.NumberIdentifierVO;
import com.matera.x9qrcode.domain.vo.OrderVO;
import com.matera.x9qrcode.domain.vo.PaymentDetailsVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPayerVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPaymentVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.PhoneVO;
import com.matera.x9qrcode.domain.vo.TipRangeVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.UltimateCreditorVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.enumerated.ActionEnum;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.model.QRCodeMongoPersistenceModel;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class QRCodeMongoEntityMapper {

    public static QRCodeEntity map(QRCodeMongoPersistenceModel document) {
        return QRCodeEntity.restore(
            document.getId(),
            document.getLocationId(),
            document.getRevision(),
            document.getCreatedAt(),
            document.getRevisedAt(),
            document.getValidUntil(),
            QRCodeStatusEnum.fromValue(document.getStatus()),
            buildCreditor(document.getCreditor()),
            buildBill(document.getBill()),
            new UnstructuredVO(document.getUnstructured()),
            document.getAdditionalInformation(),
            buildPaymentNotification(document.getPaymentNotification()),
            document.getPaymentMethods().stream().map(QRCodeMongoEntityMapper::buildPaymentMethod).toList(),
            buildPaymentDetails(document.getPaymentDetails()),
            new EmvVO(document.getQrcodeEmv())
        );
    }

    private static CreditorVO buildCreditor(QRCodeMongoPersistenceModel.Creditor doc) {
        return new CreditorVO(
            doc.getName(),
            doc.getPhone(),
            doc.getEmail(),
            buildAddress(doc.getAddress()),
            buildUltimateCreditor(doc.getUltimateCreditor()),
            doc.getMerchantCategoryCode()
        );
    }

    private static UltimateCreditorVO buildUltimateCreditor(QRCodeMongoPersistenceModel.UltimateCreditor doc) {
        if (isNull(doc)) {
            return null;
        }

        return new UltimateCreditorVO(
            buildAccount(doc.getAccount()),
            doc.getName(),
            doc.getPhone(),
            doc.getEmail(),
            buildAddress(doc.getAddress())
        );
    }

    private static BillVO buildBill(QRCodeMongoPersistenceModel.Bill doc) {
        return new BillVO(
            new DescriptionVO(doc.getDescription()),
            buildOrder(doc.getOrder()),
            buildInvoice(doc.getInvoice()),
            buildTip(doc.getTip()),
            buildAmountDue(doc.getAmountDue()),
            PaymentTimingEnum.fromValue(doc.getPaymentTiming())
        );
    }

    private static TipVO buildTip(QRCodeMongoPersistenceModel.Tip doc) {
        if (isNull(doc)) {
            return null;
        }

        return new TipVO(doc.getAllowed(), buildTipRange(doc.getRange()), doc.getPresets());
    }

    private static TipRangeVO buildTipRange(QRCodeMongoPersistenceModel.Range doc) {
        if (isNull(doc)) {
            return null;
        }

        return new TipRangeVO(
            doc.getMin(),
            doc.getMax()
        );
    }

    private static AccountVO buildAccount(QRCodeMongoPersistenceModel.Account doc) {
        return new AccountVO(doc.getId(), doc.getSchemaName());
    }

    private static AddressVO buildAddress(QRCodeMongoPersistenceModel.Address doc) {
        if (isNull(doc)) {
            return null;
        }

        return new AddressVO(
            doc.getLine1(),
            doc.getLine2(),
            doc.getCity(),
            doc.getState(),
            doc.getPostalCode(),
            doc.getCountry()
        );
    }

    private static InvoiceVO buildInvoice(QRCodeMongoPersistenceModel.Invoice doc) {
        if (isNull(doc)) {
            return null;
        }

        return new InvoiceVO(
            doc.getNumber(),
            doc.getDate(),
            doc.getDueDate(),
            buildInvoicee(doc.getInvoicee()));
    }

    private static InvoiceeVO buildInvoicee(QRCodeMongoPersistenceModel.Invoicee doc) {
        if (isNull(doc)) {
            return null;
        }

        return new InvoiceeVO(
            new NameVO(doc.getName()),
            new PhoneVO(doc.getPhone()),
            new EmailVO(doc.getEmail()),
            buildAddress(doc.getAddress()));
    }

    private static OrderVO buildOrder(QRCodeMongoPersistenceModel.Order doc) {
        if (isNull(doc)) {
            return null;
        }

        return new OrderVO(
            new NumberIdentifierVO(doc.getNumber()),
            doc.getDate()
        );
    }

    private static AmountDueVO buildAmountDue(QRCodeMongoPersistenceModel.AmountDue doc) {
        if (isNull(doc)) {
            return null;
        }

        return new AmountDueVO(new CurrencyAmountVO(doc.getAmount(), doc.getCurrency()),
            buildAdjustment(doc.getAdjustment()));
    }

    private static AdjustmentVO buildAdjustment(QRCodeMongoPersistenceModel.Adjustment doc) {
        if (isNull(doc)) {
            return null;
        }

        return new AdjustmentVO(FormulaEnum.fromValue(doc.getFormula()), buildAdjustmentParameters(doc.getParameters()));
    }

    private static AdjustmentParametersVO buildAdjustmentParameters(QRCodeMongoPersistenceModel.AdjustmentParameters doc) {
        List<DiscountVO> discounts = isNull(doc.getDiscounts()) || doc.getDiscounts().isEmpty() ? null :
            doc.getDiscounts().stream().map(QRCodeMongoEntityMapper::buildDiscount).toList();
        return new AdjustmentParametersVO(discounts, buildLateFees(doc.getLateFees()));
    }

    private static DiscountVO buildDiscount(QRCodeMongoPersistenceModel.Discount doc) {
        return new DiscountVO(doc.getDaysBefore(), doc.getDiscount(), doc.getExplanation());
    }

    private static LateFeesVO buildLateFees(QRCodeMongoPersistenceModel.LateFees doc) {
        return new LateFeesVO(doc.getFixed(), doc.getPerDay(), doc.getExplanation());
    }

    private static EditableAmountVO buildEditable(QRCodeMongoPersistenceModel.Editable doc) {
        if (isNull(doc)) {
            return null;
        }

        return new EditableAmountVO(
            buildRange(doc.getRange())
        );
    }

    private static AmountRangeVO buildRange(QRCodeMongoPersistenceModel.AmountRange doc) {
        if (isNull(doc)) {
            return null;
        }

        return new AmountRangeVO(
            doc.getMin(),
            doc.getMax()
        );
    }

    private static PaymentMethodVO buildPaymentMethod(QRCodeMongoPersistenceModel.PaymentMethod doc) {
        return new PaymentMethodVO(
            doc.getCurrency(),
            doc.getValidUntil(),
            new AmountVO(doc.getAmount()),
            buildEditable(doc.getEditable()),
            buildNetworks(doc.getNetworks())
        );
    }

    private static PaymentDetailsVO buildPaymentDetails(QRCodeMongoPersistenceModel.PaymentDetails doc) {
        if (isNull(doc)) {
            return null;
        }

        return new PaymentDetailsVO(
            doc.getEndToEndId(),
            isNull(doc.getPaymentNetwork()) ? null : NetworkEnum.fromValue(doc.getPaymentNetwork())
        );
    }

    private static PaymentNotificationVO buildPaymentNotification(QRCodeMongoPersistenceModel.PaymentNotification doc) {
        if (isNull(doc)) {
            return null;
        }

        return new PaymentNotificationVO(
            NotificationKindEnum.fromValue(doc.getKind()),
            doc.getEndpoint(),
            buildPaymentNotificationData(doc.getData())
        );
    }

    private static PaymentNotificationDataVO buildPaymentNotificationData(QRCodeMongoPersistenceModel.PaymentNotificationData doc) {
        if (isNull(doc)) {
            return null;
        }

        return new PaymentNotificationDataVO(
            buildPaymentNotificationPayment(doc.getPayment()),
            buildPaymentNotificationPayer(doc.getPayer()),
            new ExpectedDateVO(doc.getExpectedDate()),
            buildBlockchain(doc.getBlockchain())
        );
    }

    private static PaymentNotificationPaymentVO buildPaymentNotificationPayment(QRCodeMongoPersistenceModel.PaymentNotificationPayment doc) {
        return new PaymentNotificationPaymentVO(
            new AmountVO(doc.getAmount()),
            isNull(doc.getTipAmount()) ? null : new AmountVO(doc.getTipAmount()),
            doc.getCurrency(),
            NetworkEnum.fromValue(doc.getNetwork()),
            doc.getTransactionId()
        );
    }

    private static PaymentNotificationPayerVO buildPaymentNotificationPayer(QRCodeMongoPersistenceModel.PaymentNotificationPayer doc) {
        if (isNull(doc)) {
            return null;
        }

        return new PaymentNotificationPayerVO(doc.getInfo());
    }

    private static BlockchainVO buildBlockchain(QRCodeMongoPersistenceModel.PaymentNotificationBlockchain doc) {
        if (isNull(doc)) {
            return null;
        }

        return new BlockchainVO(ActionEnum.fromValue(doc.getAction()), new CryptoWalletPaymentAddressVO(doc.getFrom()),
            new CryptoWalletPaymentAddressVO(doc.getTo()));
    }

    private static NetworksVO buildNetworks(QRCodeMongoPersistenceModel.Networks doc) {
        return new NetworksVO(
            buildBankPaymentAddress(doc.getFedNow()),
            buildBankPaymentAddress(doc.getAch()),
            buildBankPaymentAddress(doc.getRtp()),
            buildCryptoWalletPaymentAddress(doc.getPolygon()),
            buildCryptoWalletPaymentAddress(doc.getSolana()),
            buildCryptoWalletPaymentAddress(doc.getEthereum()),
            buildCryptoWalletPaymentAddress(doc.getBitcoin()),
            buildCryptoWalletPaymentAddress(doc.getBase()),
            buildCryptoWalletPaymentAddress(doc.getXrp()),
            buildCryptoWalletPaymentAddress(doc.getArc()),
            doc.getAdditionalProperties()
        );
    }

    private static BankPaymentAddressVO buildBankPaymentAddress(QRCodeMongoPersistenceModel.BankPaymentAddress doc) {
        if (isNull(doc)) {
            return null;
        }

        return new BankPaymentAddressVO(
            doc.getRoutingNumber(),
            doc.getAccountNumber()
        );
    }

    private static CryptoWalletPaymentAddressVO buildCryptoWalletPaymentAddress(QRCodeMongoPersistenceModel.CryptoWalletPaymentAddress doc) {
        if (isNull(doc)) {
            return null;
        }

        return new CryptoWalletPaymentAddressVO(doc.getWalletAddress());
    }

}

