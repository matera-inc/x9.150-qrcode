/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.SolanaPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The QR content a payer submits must be the content this QR Code was issued with.
 *
 * <p>Deliberately a domain test rather than a black-box one, and the reason is worth recording
 * because it was not obvious: over HTTP there is only ever ONE genuine content string per location.
 * The EMV is a deterministic function of the location URL and the creditor's name and city, it
 * carries no amount and no revision, and location hand-over requires the creditors to match — so
 * two legitimately-issued codes for one location come out byte-identical. No black-box test built
 * from genuine artifacts can tell "compares the content" apart from "compares the location". Here
 * the two strings can simply be different.
 */
class QRCodeEntityIssuedContentTest extends AbstractTest {

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final OffsetDateTime VALID_UNTIL = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);

    private QRCodeEntity qrCode() {
        BillVO bill = new BillVO(
            new DescriptionVO("issued content"), null, null, TipVO.noTip(),
            new AmountDueVO(new CurrencyAmountVO(1_000L, "USDC"), null),
            PaymentTimingEnum.IMMEDIATE);

        return QRCodeEntity.create(
            UUID::randomUUID, null, VALID_UNTIL, CREDITOR_FIXTURE.creditor(), bill,
            new UnstructuredVO("issued content test"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null),
            List.of(new PaymentMethodVO(
                "USDC", VALID_UNTIL, new AmountVO(1_000L), null,
                new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, null), Map.of()))));
    }

    private QRCodeEntity qrCodeCarrying(String content) {
        QRCodeEntity entity = qrCode();
        entity.updateQrCodeContent(content);
        return entity;
    }

    @Test
    void theContentThisQRCodeWasIssuedWithIsAccepted() {
        String issued = "00020101021226760006org.x90162host/pub/api/v1/loc/ABC6304A1B2";

        assertDoesNotThrow(() -> qrCodeCarrying(issued).requireIssuedQrCodeContent(issued));
    }

    @Test
    void contentThatIsNotTheIssuedOneIsRefused() {
        // Same location in the tag-26 URL, different merchant name — which is exactly what the old
        // check could not see, because it only asked whether the URL contained the location id.
        String issued = "00020101021226760006org.x90162host/pub/api/v1/loc/ABC5911HONEST CO6304A1B2";
        String other = "00020101021226760006org.x90162host/pub/api/v1/loc/ABC5909OTHER CO6304C3D4";

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> qrCodeCarrying(issued).requireIssuedQrCodeContent(other));

        assertTrue(exception.field().contains("qrCodeContent"), exception.field());
    }

    @Test
    void contentDifferingByASingleCharacterIsRefused() {
        String issued = "00020101021226760006org.x90162host/pub/api/v1/loc/ABC6304A1B2";

        assertThrows(BusinessRuleException.class,
            () -> qrCodeCarrying(issued).requireIssuedQrCodeContent(issued.replace("A1B2", "A1B3")));
    }

    @Test
    void anAbsentSubmittedContentIsNotThisRulesBusiness() {
        // Requests that carry no QR content at all are rejected earlier, by the signature layer.
        // This guard stays silent rather than inventing a second opinion about them.
        assertDoesNotThrow(() -> qrCodeCarrying("anything").requireIssuedQrCodeContent(null));
    }

    @Test
    void aQRCodeHoldingNoContentYetAcceptsNothing() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> qrCode().requireIssuedQrCodeContent("00020101021226760006org.x9"));

        assertTrue(exception.field().contains("qrCodeContent"), exception.field());
    }
}
