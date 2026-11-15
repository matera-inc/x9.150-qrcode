/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.validation;

import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchPaymentRequestReplacementDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestInputDTO;

import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.Validator;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import static java.util.Objects.nonNull;

/**
 * Refuses an unknown field, at any level, in a body we generate from: a create, a PATCH, and an
 * outbound payment notification, beside the body's other violations (INTERPRETATION I-16, owner
 * 2026-11-15: strict to generate). A payload we read is never checked here.
 *
 * <p>The fields themselves are found by {@link ClosedRequestFields} while the body is read. This adds
 * each as a field error to the same binding result bean validation fills, so the refusal is the usual
 * 400 whose {@code violations} name every problem at once — for example
 * {@code creditor.address.x: is not a field of address, whose fields are line1, line2, city, state,
 * postalCode and country}.
 */
@ControllerAdvice
public class ClosedRequestFieldsValidator implements Validator {

    @InitBinder
    public void register(WebDataBinder binder) {
        Object target = binder.getTarget();
        if (nonNull(target) && supports(target.getClass())) {
            binder.addValidators(this);
        }
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return PaymentRequestInputDTO.class.isAssignableFrom(clazz)
            || PatchPaymentRequestReplacementDTO.class.isAssignableFrom(clazz)
            || OutboundPaymentNotificationDTO.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        if (!(errors instanceof BindingResult result)) {
            return;
        }
        for (ClosedRequestFields.Violation violation : ClosedRequestFields.recorded()) {
            result.addError(new FieldError(result.getObjectName(), violation.path(), violation.message()));
        }
    }

}
