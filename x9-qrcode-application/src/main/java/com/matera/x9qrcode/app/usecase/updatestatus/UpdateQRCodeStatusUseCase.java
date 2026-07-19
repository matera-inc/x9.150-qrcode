/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.updatestatus;

import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.PaymentDetailsVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;

import lombok.RequiredArgsConstructor;

import java.util.List;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@RequiredArgsConstructor
public class UpdateQRCodeStatusUseCase extends UseCase<UpdateQRCodeStatusInput, UpdateQRCodeStatusOutput> {

    private final QRCodeRepository qrCodeRepository;

    @Override
    public UpdateQRCodeStatusOutput execute(UpdateQRCodeStatusInput updateQRCodeStatusInput) {
        QRCodeIdVO qrCodeIdVO = QRCodeIdVO.from(updateQRCodeStatusInput.id());

        QRCodeEntity qrCodeEntity = retrieveQRCodeEntity(qrCodeIdVO);

        NetworkEnum network = isNull(updateQRCodeStatusInput.paymentNetwork())
            ? null
            : NetworkEnum.fromValue(updateQRCodeStatusInput.paymentNetwork().value());

        if (nonNull(network)) {
            checkNetworkIsValidPaymentMethod(qrCodeEntity.getPaymentMethods(), network);
        }

        switch (updateQRCodeStatusInput.status()) {
            case PAID -> qrCodeEntity.pay(buildPaymentDetails(updateQRCodeStatusInput));
            case CANCELLED -> qrCodeEntity.cancel(buildPaymentDetails(updateQRCodeStatusInput));
            case ACTIVE -> qrCodeEntity.reactivate(buildPaymentDetails(updateQRCodeStatusInput));
            default -> throw new BusinessRuleException(
                "Status %s is not allowed.".formatted(updateQRCodeStatusInput.status().value()));
        }

        qrCodeRepository.save(qrCodeEntity);

        return new UpdateQRCodeStatusOutput(
            qrCodeIdVO.valueAsString(),
            qrCodeEntity.getStatus().value()
        );
    }

    private QRCodeEntity retrieveQRCodeEntity(QRCodeIdVO qrCodeIdVO) {
        try {
            return qrCodeRepository.findById(qrCodeIdVO);
        } catch (BusinessRuleException e) {
            throw new BusinessRuleException(EXPIRED_QRCODE_ERROR_MESSAGE.formatted(qrCodeIdVO));
        }
    }

    private void checkNetworkIsValidPaymentMethod(List<PaymentMethodVO> paymentMethods, NetworkEnum network) {
        if (paymentMethods.stream().noneMatch(paymentMethod -> checkNetworkIsFilled(paymentMethod.networks(), network))) {
            throw new BusinessRuleException("Network %s is not a valid payment method.".formatted(network.value()));
        }
    }

    private boolean checkNetworkIsFilled(NetworksVO networks, NetworkEnum network) {
        return switch (network) {
            case FEDNOW -> nonNull(networks.fedNow());
            case RTP -> nonNull(networks.rtp());
            case ACH -> nonNull(networks.ach());
            case POLYGON -> nonNull(networks.polygon());
            case SOLANA -> nonNull(networks.solana());
            case ETHEREUM -> nonNull(networks.ethereum());
            case BITCOIN -> nonNull(networks.bitcoin());
            case BASE -> nonNull(networks.base());
            case XRP -> nonNull(networks.xrp());
            case ARC -> nonNull(networks.arc());
        };
    }

    private PaymentDetailsVO buildPaymentDetails(UpdateQRCodeStatusInput input) {
        if (isNull(input.endToEndId()) && isNull(input.paymentNetwork())) {
            return null;
        }

        return new PaymentDetailsVO(
            input.endToEndId(),
            isNull(input.paymentNetwork()) ? null : NetworkEnum.fromValue(input.paymentNetwork().value())
        );
    }

}
