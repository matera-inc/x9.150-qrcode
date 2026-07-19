/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.usecase.createqrcode.CreateQRCodeOutput;
import com.matera.x9qrcode.app.usecase.createqrcode.CreateQRCodeUseCase;
import com.matera.x9qrcode.app.usecase.patchqrcode.PatchQRCodeOutput;
import com.matera.x9qrcode.app.usecase.patchqrcode.PatchQRCodeUseCase;
import com.matera.x9qrcode.app.usecase.retrieveqrcode.RetrieveQRCodeInput;
import com.matera.x9qrcode.app.usecase.retrieveqrcode.RetrieveQRCodeOutput;
import com.matera.x9qrcode.app.usecase.retrieveqrcode.RetrieveQRCodeUseCase;
import com.matera.x9qrcode.app.usecase.updatestatus.UpdateQRCodeStatusOutput;
import com.matera.x9qrcode.app.usecase.updatestatus.UpdateQRCodeStatusUseCase;
import com.matera.x9qrcode.infrastructure.generated.api.PaymentRequestApi;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchPaymentRequestReplacementDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestInformationDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestInputDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.StatusUpdateDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.StatusUpdateResponseDTO;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.request.CreateQRCodeRequestMapper;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.request.PatchQRCodeRequestMapper;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.request.UpdateQRCodeStatusRequestMapper;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.response.CreateQRCodeResponseMapper;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.response.PatchQRCodeResponseMapper;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.response.RetrieveQRCodeResponseMapper;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.response.UpdateQRCodeStatusResponseMapper;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentRequestsController implements PaymentRequestApi {

    private final CreateQRCodeUseCase createQRCodeUseCase;
    private final UpdateQRCodeStatusUseCase updateQRCodeStatusUseCase;
    private final PatchQRCodeUseCase patchQRCodeUseCase;
    private final RetrieveQRCodeUseCase retrieveQRCodeUseCase;

    @Override
    @Transactional
    public ResponseEntity<PaymentRequestResponseDTO> createPaymentRequest(PaymentRequestInputDTO paymentRequestInputDTO) {
        CreateQRCodeOutput createQRCodeOutput =
            createQRCodeUseCase.execute(CreateQRCodeRequestMapper.map(paymentRequestInputDTO));

        return ResponseEntity.status(HttpStatus.CREATED).body(CreateQRCodeResponseMapper.map(createQRCodeOutput));
    }

    @Override
    public ResponseEntity<PaymentRequestInformationDTO> getPaymentRequest(String id, Integer revision) {
        RetrieveQRCodeInput retrieveQRCodeInput = new RetrieveQRCodeInput(id, revision);

        RetrieveQRCodeOutput retrieveQRCodeOutput = retrieveQRCodeUseCase.execute(retrieveQRCodeInput);

        return ResponseEntity.ok(RetrieveQRCodeResponseMapper.map(retrieveQRCodeOutput));
    }

    @Override
    @Transactional
    public ResponseEntity<PaymentRequestResponseDTO> patchPaymentRequest(String id,
                                                                         PatchPaymentRequestReplacementDTO patchPaymentRequestReplacementDTO) {
        PatchQRCodeOutput patchQRCodeOutput =
            patchQRCodeUseCase.execute(PatchQRCodeRequestMapper.map(id, patchPaymentRequestReplacementDTO));

        return ResponseEntity.status(HttpStatus.OK).body(PatchQRCodeResponseMapper.map(patchQRCodeOutput));
    }

    @Override
    @Transactional
    public ResponseEntity<StatusUpdateResponseDTO> putPaymentRequestStatusUpdate(String id, StatusUpdateDTO statusUpdateDTO) {
        UpdateQRCodeStatusOutput updateQRCodeStatusOutput =
            updateQRCodeStatusUseCase.execute(UpdateQRCodeStatusRequestMapper.map(id, statusUpdateDTO));

        return ResponseEntity.ok(UpdateQRCodeStatusResponseMapper.map(updateQRCodeStatusOutput));
    }

}
