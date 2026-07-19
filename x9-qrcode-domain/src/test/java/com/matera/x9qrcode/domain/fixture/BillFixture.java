/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.fixture;

import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.AdjustmentVO;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.EmailVO;
import com.matera.x9qrcode.domain.vo.InvoiceVO;
import com.matera.x9qrcode.domain.vo.InvoiceeVO;
import com.matera.x9qrcode.domain.vo.LateFeesVO;
import com.matera.x9qrcode.domain.vo.NameVO;
import com.matera.x9qrcode.domain.vo.NumberIdentifierVO;
import com.matera.x9qrcode.domain.vo.OrderVO;
import com.matera.x9qrcode.domain.vo.PhoneVO;
import com.matera.x9qrcode.domain.vo.TipRangeVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;
import net.datafaker.Faker;

import java.time.LocalDate;
import java.util.List;

public final class BillFixture {

    private final Faker faker;
    private final PhoneFixture phoneFixture;
    private final AddressFixture addressFixture;

    public BillFixture(Faker faker, PhoneFixture phoneFixture, AddressFixture addressFixture) {
        this.faker = faker;
        this.phoneFixture = phoneFixture;
        this.addressFixture = addressFixture;
    }

    public DescriptionVO description() {
        return new DescriptionVO(faker.lorem().characters(10, 100));
    }

    public BillVO bill() {
        return new BillVO(
            description(),
            order(),
            invoice(),
            tip(),
            amountDue(),
            deferredPaymentTiming()
        );
    }

    public PaymentTimingEnum deferredPaymentTiming() {
        return PaymentTimingEnum.DEFERRED;
    }

    public PaymentTimingEnum immediatePaymentTiming() {
        return PaymentTimingEnum.IMMEDIATE;
    }

    public OrderVO order() {
        NumberIdentifierVO number = new NumberIdentifierVO(faker.number().digits(8));

        LocalDate date = LocalDate.now().plusDays(5);

        return new OrderVO(number, date);
    }

    public InvoiceVO invoice() {
        InvoiceeVO invoicee = new InvoiceeVO(
            new NameVO(faker.name().fullName()),
            new PhoneVO(phoneFixture.phone()),
            new EmailVO(faker.internet().emailAddress()),
            addressFixture.address()
        );

        return new InvoiceVO(
            faker.number().digits(20),
            DateTimeUtils.nowUTC().toLocalDate(),
            DateTimeUtils.nowUTC().plusDays(5),
            invoicee
        );
    }

    public TipVO tip() {
        TipRangeVO range = new TipRangeVO(1, 10);

        List<Integer> presets = List.of(2, 5);

        Boolean allowed = true;

        return new TipVO(allowed, range, presets);
    }

    public AmountDueVO amountDue() {
        return new AmountDueVO(currencyAmount(), adjustment());
    }

    public AmountDueVO amountDueWithoutAdjustment() {
        return new AmountDueVO(currencyAmount(), null);
    }

    public AmountDueVO amountDueWithHighAdjustment() {
        AdjustmentVO adjustment = new AdjustmentVO(
            FormulaEnum.FIXED_DISCOUNT_LATE_FEE_LINEAR_INTEREST,
            new AdjustmentParametersVO(List.of(new DiscountVO(1, 100000L, "High discount")), lateFees())
        );

        return new AmountDueVO(currencyAmount(), adjustment);
    }

    public AdjustmentVO adjustment() {
        return new AdjustmentVO(FormulaEnum.FIXED_DISCOUNT_LATE_FEE_LINEAR_INTEREST, adjustmentParameters());
    }

    public AdjustmentParametersVO adjustmentParameters() {
        return new AdjustmentParametersVO(List.of(discount(), discount(), discount()), lateFees());
    }

    public LateFeesVO lateFees() {
        return new LateFeesVO(1000L, 1000L, faker.lorem().characters(10, 50));
    }

    public DiscountVO discount() {
        return new DiscountVO(faker.number().numberBetween(1, 5), 1000L, faker.lorem().characters(10, 50));
    }

    public CurrencyAmountVO currencyAmount() {
        return new CurrencyAmountVO(10000L, "USD");
    }

}
