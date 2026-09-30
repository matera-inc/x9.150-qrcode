/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.patchqrcode;

import com.matera.x9qrcode.app.dto.LocationDTO;
import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.app.service.QRCodeEMVService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.dto.PaymentMethodUpdateDTO;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.app.usecase.createqrcode.mapper.CreateQRCodeLocationMapper;
import com.matera.x9qrcode.app.usecase.patchqrcode.mapper.PatchQRCodeBillMapper;
import com.matera.x9qrcode.app.usecase.patchqrcode.mapper.PatchQRCodePaymentMethodsMapper;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.service.CurrencyMixPolicy;
import com.matera.x9qrcode.domain.service.SupportedCurrencyPolicy;
import com.matera.x9qrcode.domain.vo.LocationIdVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static java.util.Objects.nonNull;

@RequiredArgsConstructor
public class PatchQRCodeUseCase extends UseCase<PatchQRCodeInput, PatchQRCodeOutput> {

    private final QRCodeRepository qrCodeRepository;
    private final QRCodeEMVService qrCodeEMVService;
    private final QRCodeLocationService qrCodeLocationService;
    private final CurrencyMixPolicy currencyMixPolicy;
    private final SupportedCurrencyPolicy supportedCurrencyPolicy;

    @Override
    public PatchQRCodeOutput execute(PatchQRCodeInput input) {
        QRCodeIdVO qrCodeIdVO = QRCodeIdVO.from(input.id());

        QRCodeEntity qrCodeEntity = qrCodeRepository.findById(qrCodeIdVO);

        // Before anything is applied: editing a live bill has the same race as cancelling one. A
        // payer can reach PAYMENT_INITIATED between the read that decided this edit and the write
        // that applies it, and the edit would then rewrite a bill somebody is midway through paying.
        qrCodeEntity.requireEntityTag(input.expectedEntityTag());

        if (qrCodeEntity.isNotActiveOrInitiated()) {
            throw new BusinessRuleException("QR code with id %s must be active to be updated.".formatted(qrCodeIdVO));
        }

        // treatLocationUpdate RETURNS the consumer that assigns the location; it has to be applied.
        // Discarding it released the previous holder and gave the location to nobody, which is worse
        // than doing nothing: the QR Code already printed stops resolving.
        input.locationId().ifPresent(locationId ->
            treatLocationUpdate(locationId, qrCodeEntity).accept(locationId));
        input.validUntil().ifPresent(qrCodeEntity::updateValidUntil);
        input.additionalInformationMap().ifPresent(qrCodeEntity::updateAdditionalInformation);
        input.unstructured().ifPresent(qrCodeEntity::updateUnstructured);
        input.billUpdateDTO().ifPresent(billUpdateDTO ->
            qrCodeEntity.updateBill(PatchQRCodeBillMapper.map(qrCodeEntity.getBill(), billUpdateDTO)));

        // Before the mapper touches them: a patch that names any currency must name them all, and
        // only ones this QR Code already offers. The mapper matches by currency and silently
        // discarded anything that did not match, so this has to be asked before it runs.
        qrCodeEntity.requirePaymentMethodCurrencies(
            input.paymentMethodUpdateDTOList().stream().map(PaymentMethodUpdateDTO::currency).toList());

        List<PaymentMethodVO> updatedPaymentMethods =
            PatchQRCodePaymentMethodsMapper.map(qrCodeEntity.getPaymentMethods(), input.paymentMethodUpdateDTOList());

        // "Nothing to update" is a statement about the WHOLE patch. Judging it on the payment methods
        // alone rejected a patch that moved the location and left the amounts alone — which is the
        // shape of re-pointing a printed QR Code at the balance still owed after a partial payment.
        boolean changedSomethingElse = input.locationId().isPresent()
            || input.validUntil().isPresent()
            || input.billUpdateDTO().isPresent()
            || input.unstructured().isPresent()
            || input.additionalInformationMap().isPresent();

        qrCodeEntity.updatePaymentMethods(updatedPaymentMethods, changedSomethingElse);

        List<String> currencies = collectCurrencies(qrCodeEntity);

        supportedCurrencyPolicy.validate(currencies);
        currencyMixPolicy.validate(currencies);

        String qrCodeContent = qrCodeEMVService.generateQrCodeContent(qrCodeEntity);

        qrCodeEntity.updateQrCodeContent(qrCodeContent);
        qrCodeEntity.updateRevision();

        qrCodeRepository.save(qrCodeEntity);

        LocationDTO locationDTO = CreateQRCodeLocationMapper.map(
            qrCodeEntity.getLocationId(),
            qrCodeLocationService.generateLocation(qrCodeEntity.getLocationId(), false)
        );

        return new PatchQRCodeOutput(qrCodeIdVO.value(), qrCodeContent, locationDTO);
    }

    private Consumer<String> treatLocationUpdate(String locationId, QRCodeEntity patchQRCodeEntity) {
        qrCodeRepository.findOptionalByLocation(LocationIdVO.from(locationId)).ifPresent(qrCode -> {
            if (!Objects.equals(qrCode.getCreditor(), patchQRCodeEntity.getCreditor())) {
                throw new BusinessRuleException(
                    "Creditor information must be equal to original location whe updating location id");
            }

            qrCode.releaseLocation();

            qrCodeRepository.save(qrCode);
        });

        return patchQRCodeEntity::updateLocationId;
    }

    private static List<String> collectCurrencies(final QRCodeEntity qrCodeEntity) {
        List<String> currencies = new ArrayList<>();

        currencies.add(qrCodeEntity.getBill().amountDue().currencyAmount().currency());

        List<PaymentMethodVO> paymentMethods = qrCodeEntity.getPaymentMethods();
        if (nonNull(paymentMethods)) {
            paymentMethods.forEach(paymentMethod -> currencies.add(paymentMethod.currency()));
        }

        return currencies;
    }

}
