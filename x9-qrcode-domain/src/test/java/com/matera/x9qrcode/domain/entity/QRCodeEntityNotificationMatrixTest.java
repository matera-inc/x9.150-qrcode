/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.event.PaymentEvent;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.exception.QRCodeStatusConflictException;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BankPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.BlockchainVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.NotificationReceiptVO;
import com.matera.x9qrcode.domain.vo.PaymentDetailsVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPayerVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPaymentVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.ReservationHolderVO;
import com.matera.x9qrcode.domain.vo.SolanaPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.enumerated.ActionEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationDeviationEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationPhaseEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentEventTypeEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0036's "Every case, by message and status" matrix, cell by cell, on the entity.
 *
 * <p>Columns: ACTIVE, held by the sender, held by someone else, PAID or CANCELLED. Rows: pre-commit,
 * post-commit, contradictory {@code PAYMENT_INITIATED} with a hash, {@code SENT} without a hash,
 * {@code NOT_SENT}. The acceptance gate (amount and time) is not in the entity; the black-box tests
 * in the infrastructure module cover it.
 */
class QRCodeEntityNotificationMatrixTest extends AbstractTest {

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String PAYER_WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU";
    private static final String HASH_1 = "5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f";
    private static final String HASH_2 = "3kXz8YtWdq1uQvBmn9Lr2HfP6sJcEgTaZo4NyRbVi7Dw5MKeUhGpQjAxCs8tLoWn";
    private static final String E2E = "20261114021000021P1BRJQ0000000001";
    private static final Duration TTL = Duration.ofSeconds(90);
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
    private static final String SUBJECT = "CN=payer-psp.example.com";

    private static final ReservationHolderVO A = ReservationHolderVO.of("payer-a", SUBJECT);
    private static final ReservationHolderVO B = ReservationHolderVO.of("payer-b", SUBJECT);

    private QRCodeEntity qrCode() {
        BillVO bill = new BillVO(
            new DescriptionVO("matrix"), null, null, TipVO.noTip(),
            new AmountDueVO(new CurrencyAmountVO(1_000L, "USD"), null),
            PaymentTimingEnum.IMMEDIATE);

        OffsetDateTime validUntil = NOW.plusDays(30);

        QRCodeEntity qrCode = QRCodeEntity.create(
            UUID::randomUUID, null, validUntil, CREDITOR_FIXTURE.creditor(), bill,
            new UnstructuredVO("matrix test"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null),
            List.of(
                new PaymentMethodVO("USD", validUntil, new AmountVO(1_000L), null,
                    new NetworksVO(new BankPaymentAddressVO("021000021", "1234567890"), null, null, null, Map.of())),
                new PaymentMethodVO("USDC", validUntil, new AmountVO(1_000L), null,
                    new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, null), Map.of()))));

        return qrCode;
    }

    private static PaymentNotificationDataVO solana(ActionEnum action, String hash, ReservationHolderVO from) {
        CryptoWalletPaymentAddressVO to = new CryptoWalletPaymentAddressVO(WALLET);

        return new PaymentNotificationDataVO(
            new PaymentNotificationPaymentVO(new AmountVO(1_000L), null, "USDC", "solana", hash),
            from.payerInfo() == null ? null : new PaymentNotificationPayerVO(from.payerInfo()),
            null,
            new BlockchainVO(action, new CryptoWalletPaymentAddressVO(PAYER_WALLET), to));
    }

    private static PaymentNotificationDataVO fedNow(String e2e, ReservationHolderVO from) {
        return new PaymentNotificationDataVO(
            new PaymentNotificationPaymentVO(new AmountVO(1_000L), null, "USD", "fednow", e2e),
            from.payerInfo() == null ? null : new PaymentNotificationPayerVO(from.payerInfo()),
            null,
            null);
    }

    /** A successful pre-commit, as the use case drives it once the gate said yes. */
    private static void preCommit(QRCodeEntity qrCode, PaymentNotificationDataVO data, ReservationHolderVO from,
                                  Duration ttl) {
        qrCode.admitPreCommit(data, from);
        qrCode.acceptPreCommit(data, from, ttl);
    }

    private static QRCodeEntity heldBy(QRCodeEntity qrCode, ReservationHolderVO holder) {
        preCommit(qrCode, solana(ActionEnum.PAYMENT_INITIATED, null, holder), holder, TTL);
        return qrCode;
    }

    private static List<PaymentEventTypeEnum> types(QRCodeEntity qrCode) {
        return qrCode.getPendingEvents().stream().map(PaymentEvent::type).toList();
    }

    private static PaymentEvent last(QRCodeEntity qrCode) {
        List<PaymentEvent> events = qrCode.getPendingEvents();
        return events.get(events.size() - 1);
    }

    private static OffsetDateTime aDayLater() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
    }

    // ------------------------------------------------------------------------- the phase

    @Nested
    class ThePhaseComesFromTheEvidence {

        /**
         * <b>X9-HOLD-101</b> — a transaction hash makes a post-commit on every rail, Solana included, whatever the action says.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC5). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> Solana used to read the phase from blockchain.action, so a
         * PAYMENT_INITIATED carrying a hash was judged as a pre-commit and could be refused
         * although the money had moved.
         */
        @Test
        void aHashMakesAPostCommitOnEveryRail() {
            assertEquals(NotificationPhaseEnum.POST_COMMIT, solana(ActionEnum.SENT, HASH_1, A).phase());
            assertEquals(NotificationPhaseEnum.POST_COMMIT, solana(ActionEnum.PAYMENT_INITIATED, HASH_1, A).phase());
            assertEquals(NotificationPhaseEnum.POST_COMMIT, fedNow(E2E, A).phase());
        }

        /**
         * <b>X9-HOLD-102</b> — no transaction id makes a pre-commit on every rail, FedNow and RTP included.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC1). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> FedNow and RTP required an End-to-End ID on every notification, so they
         * had no pre-commit and no reservation: two payers could pay one QR Code.
         */
        @Test
        void noHashIsAPreCommitOnEveryRail() {
            assertEquals(NotificationPhaseEnum.PRE_COMMIT, solana(ActionEnum.PAYMENT_INITIATED, null, A).phase());
            assertEquals(NotificationPhaseEnum.PRE_COMMIT, fedNow(null, A).phase());
        }

        /**
         * <b>X9-HOLD-103</b> — NOT_SENT is the one phase the action decides on its own.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (the matrix). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> NOT_SENT has no transaction by its nature; keying it on the hash would
         * make the only way to give a QR Code back unreachable.
         */
        @Test
        void notSentIsKeyedOnTheAction() {
            assertEquals(NotificationPhaseEnum.RELEASE, solana(ActionEnum.NOT_SENT, null, A).phase());
        }
    }

    // ------------------------------------------------------------------------- pre-commit

    @Nested
    class PreCommit {

        /**
         * <b>X9-HOLD-104</b> — a pre-commit on an ACTIVE QR Code reserves it for the sender.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC1). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> The reservation is what stops a second payer; without it two payers can
         * both be told yes.
         */
        @Test
        void onActiveTheSenderTakesTheReservation() {
            QRCodeEntity qrCode = qrCode();

            preCommit(qrCode, fedNow(null, A), A, TTL);

            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCode.effectiveStatus());
            assertTrue(qrCode.isHeldBy(A));
            assertEquals(List.of(PaymentEventTypeEnum.PAYMENT_INITIATED), types(qrCode));
            assertEquals(QRCodeStatusEnum.ACTIVE, last(qrCode).receivedInStatus());
            assertNull(last(qrCode).deviations(), "a payer that followed the rules carries no deviations");
        }

        /**
         * <b>X9-HOLD-105</b> — the holder's pre-commit is a retry: the window moves and nothing is emitted.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC3). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> A payer whose answer was lost must be able to ask again without losing the
         * QR Code, and a second payment.initiated would read as a second payer.
         */
        @Test
        void fromTheHolderItIsARetryThatMovesTheWindowAndEmitsNothing() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);
            OffsetDateTime firstWindow = qrCode.getInitiatedExpiresAt();

            assertTrue(qrCode.admitPreCommit(solana(ActionEnum.PAYMENT_INITIATED, null, A), A));
            qrCode.acceptPreCommit(solana(ActionEnum.PAYMENT_INITIATED, null, A), A, Duration.ofSeconds(900));

            assertTrue(qrCode.getInitiatedExpiresAt().isAfter(firstWindow), "the window must move");
            assertEquals(List.of(PaymentEventTypeEnum.PAYMENT_INITIATED), types(qrCode), "the retry emits nothing");
        }

        /**
         * <b>X9-HOLD-106</b> — another payer's pre-commit on a held QR Code is a 409.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC2). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> Two payers reaching for one bill is the race the reservation exists to
         * stop; the second must be told to come back, not that its request is wrong.
         */
        @Test
        void fromAnotherPayerItIsAConflict() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);

            assertThrows(QRCodeStatusConflictException.class,
                () -> qrCode.admitPreCommit(solana(ActionEnum.PAYMENT_INITIATED, null, B), B));
            assertTrue(qrCode.isHeldBy(A));
        }

        /**
         * <b>X9-HOLD-107</b> — a pre-commit on a PAID or CANCELLED QR Code is a 400.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (the matrix). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> A closed bill must not take a new payment; it is the request that is
         * wrong, so 400, not 409.
         */
        @Test
        void onAClosedQRCodeItIsRefusedWith400() {
            QRCodeEntity paid = qrCode();
            paid.pay(new PaymentDetailsVO(E2E, "fednow"));
            QRCodeEntity cancelled = qrCode();
            cancelled.cancel(null);

            BusinessRuleException onPaid = assertThrows(BusinessRuleException.class,
                () -> paid.admitPreCommit(fedNow(null, A), A));
            BusinessRuleException onCancelled = assertThrows(BusinessRuleException.class,
                () -> cancelled.admitPreCommit(fedNow(null, A), A));

            assertFalse(onPaid instanceof QRCodeStatusConflictException);
            assertFalse(onCancelled instanceof QRCodeStatusConflictException);
        }

        /**
         * <b>X9-HOLD-108</b> — a takeover of a lapsed reservation emits payment.initiated.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC5). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> The transition used to be decided on the stored status, so a new payer
         * taking a lapsed reservation emitted nothing and the stream never learned who held the QR
         * Code.
         */
        @Test
        void aTakeoverOfALapsedReservationEmits() {
            QRCodeEntity qrCode = qrCode();
            preCommit(qrCode, solana(ActionEnum.PAYMENT_INITIATED, null, A), A, Duration.ofSeconds(-1));
            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCode.getStatus(), "stored status still PI");
            assertEquals(QRCodeStatusEnum.ACTIVE, qrCode.effectiveStatus(), "but the window has passed");

            preCommit(qrCode, solana(ActionEnum.PAYMENT_INITIATED, null, B), B, TTL);

            assertTrue(qrCode.isHeldBy(B));
            assertEquals(List.of(PaymentEventTypeEnum.PAYMENT_INITIATED, PaymentEventTypeEnum.PAYMENT_INITIATED),
                types(qrCode));
            assertEquals("payer-b", last(qrCode).payerInfo());
            assertEquals(QRCodeStatusEnum.ACTIVE, last(qrCode).receivedInStatus());
        }
    }

    // ------------------------------------------------------------------------- post-commit

    @Nested
    class PostCommit {

        /**
         * <b>X9-HOLD-109</b> — a post-commit on an ACTIVE QR Code on a chain reserves it and registers the hash, flagged.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC4). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> A payer that skipped the pre-commit has still paid; refusing it left the
         * QR Code open to a second payer and the payment system without the hash.
         */
        @Test
        void onActiveOnAChainItReservesAndRegistersTheHash() {
            QRCodeEntity qrCode = qrCode();

            NotificationReceiptVO receipt = qrCode.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);

            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCode.getStatus());
            assertTrue(qrCode.isHeldBy(A));
            assertEquals(List.of(PaymentEventTypeEnum.PAYMENT_INITIATED, PaymentEventTypeEnum.PAYMENT_SENT),
                types(qrCode));
            qrCode.getPendingEvents().forEach(event -> {
                assertEquals(List.of(NotificationDeviationEnum.RECEIVED_IN_WRONG_STATUS), event.deviations());
                assertEquals(QRCodeStatusEnum.ACTIVE, event.receivedInStatus());
                assertEquals(HASH_1, event.transactionId());
            });
            assertEquals(QRCodeStatusEnum.ACTIVE, receipt.receivedInStatus());
            assertEquals("1.1", PaymentEvent.SCHEMA_VERSION);
        }

        /**
         * <b>X9-HOLD-110</b> — a post-commit on an ACTIVE QR Code on a bank rail reserves it with payment.initiated only.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC4). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> The FedNow courtesy notification used to change nothing, so a paid QR Code
         * stayed open to a second payer; bank rails carry QRCD in the payment, so no payment.sent.
         */
        @Test
        void onActiveOnABankRailItReservesWithPaymentInitiatedOnly() {
            QRCodeEntity qrCode = qrCode();

            qrCode.registerPostCommit(fedNow(E2E, A), A);

            assertTrue(qrCode.isHeldBy(A));
            assertEquals(List.of(PaymentEventTypeEnum.PAYMENT_INITIATED), types(qrCode),
                "bank rails never emit payment.sent");
            assertEquals(List.of(NotificationDeviationEnum.RECEIVED_IN_WRONG_STATUS), last(qrCode).deviations());
        }

        /**
         * <b>X9-HOLD-111</b> — after a post-commit reserved it, another payer's pre-commit is a 409.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC4). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> Reserving is the point of accepting the post-commit: an honest second
         * payer is stopped before it pays.
         */
        @Test
        void afterAPostCommitReservedItAnotherPreCommitIsAConflict() {
            QRCodeEntity qrCode = qrCode();
            qrCode.registerPostCommit(fedNow(E2E, A), A);

            assertThrows(QRCodeStatusConflictException.class, () -> qrCode.admitPreCommit(fedNow(null, B), B));
        }

        /**
         * <b>X9-HOLD-112</b> — the holder's bank-rail post-commit is recorded with no event.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC3). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> The holder reporting its own payment is the protocol followed; it carries
         * no deviation and, on a bank rail, nothing to register.
         */
        @Test
        void fromTheHolderOnABankRailItIsRecordedWithNoEvent() {
            QRCodeEntity qrCode = qrCode();
            preCommit(qrCode, fedNow(null, A), A, TTL);

            NotificationReceiptVO receipt = qrCode.registerPostCommit(fedNow(E2E, A), A);

            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCode.getStatus());
            assertEquals(List.of(PaymentEventTypeEnum.PAYMENT_INITIATED), types(qrCode));
            assertFalse(receipt.hasDeviations());
            assertEquals(E2E, qrCode.getPaymentNotification().data().payment().transactionId());
        }

        /**
         * <b>X9-HOLD-113</b> — the holder's post-commit on a chain emits payment.sent with no deviations.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (the matrix). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> The payment.sent is the registration: it tells the payment system which QR
         * Code the hash pays.
         */
        @Test
        void fromTheHolderOnAChainItEmitsPaymentSent() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);

            qrCode.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);

            assertEquals(List.of(PaymentEventTypeEnum.PAYMENT_INITIATED, PaymentEventTypeEnum.PAYMENT_SENT),
                types(qrCode));
            assertNull(last(qrCode).deviations());
            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, last(qrCode).receivedInStatus());
        }

        /**
         * <b>X9-HOLD-114</b> — another payer's post-commit on a held QR Code is registered, flagged, and the holder keeps it.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC2). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> Java used to answer 409 and drop the hash of money that had already moved;
         * now it is registered, flagged, and the holder keeps its reservation.
         */
        @Test
        void fromAnotherPayerItIsRegisteredAndTheHolderKeepsIt() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);

            qrCode.registerPostCommit(solana(ActionEnum.SENT, HASH_2, B), B);

            assertTrue(qrCode.isHeldBy(A), "the holder keeps the reservation");
            assertEquals(PaymentEventTypeEnum.PAYMENT_SENT, last(qrCode).type());
            assertEquals(HASH_2, last(qrCode).transactionId());
            assertEquals("payer-b", last(qrCode).payerInfo());
            assertEquals(List.of(NotificationDeviationEnum.RECEIVED_IN_WRONG_STATUS), last(qrCode).deviations());
            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, last(qrCode).receivedInStatus());
        }

        /**
         * <b>X9-HOLD-115</b> — a post-commit on a PAID or CANCELLED QR Code is registered, flagged, and nothing reopens.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC7). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> Money that arrives for a bill already closed still needs a link to its QR
         * Code; reopening the bill would be worse than the duplicate.
         */
        @Test
        void onAClosedQRCodeItIsRegisteredAndNothingReopens() {
            QRCodeEntity paid = qrCode();
            paid.pay(new PaymentDetailsVO(E2E, "fednow"));
            QRCodeEntity cancelled = qrCode();
            cancelled.cancel(null);

            paid.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);
            cancelled.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);

            assertEquals(QRCodeStatusEnum.PAID, paid.getStatus());
            assertEquals(QRCodeStatusEnum.CANCELLED, cancelled.getStatus());
            assertEquals(PaymentEventTypeEnum.PAYMENT_SENT, last(paid).type());
            assertEquals(QRCodeStatusEnum.PAID, last(paid).receivedInStatus());
            assertEquals(QRCodeStatusEnum.CANCELLED, last(cancelled).receivedInStatus());
            assertEquals(List.of(NotificationDeviationEnum.RECEIVED_IN_WRONG_STATUS), last(paid).deviations());
        }

        /**
         * <b>X9-HOLD-116</b> — a bank-rail post-commit on a closed QR Code publishes nothing.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC7). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> Bank rails never publish payment.sent; the payment itself carries QRCD,
         * and a closed QR Code never reopens.
         */
        @Test
        void onAClosedQRCodeABankRailPostCommitPublishesNothing() {
            QRCodeEntity paid = qrCode();
            paid.pay(new PaymentDetailsVO(E2E, "fednow"));
            int before = paid.getPendingEvents().size();

            NotificationReceiptVO receipt = paid.registerPostCommit(fedNow(E2E, A), A);

            assertEquals(QRCodeStatusEnum.PAID, paid.getStatus());
            assertEquals(before, paid.getPendingEvents().size());
            assertEquals(List.of(NotificationDeviationEnum.RECEIVED_IN_WRONG_STATUS), receipt.deviations());
        }

        /**
         * <b>X9-HOLD-117</b> — every hash is registered as its own payment.sent, none overwritten.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC6). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> Two hashes for one QR Code are the payment system's only signal of a
         * duplicate payment; overwriting one hides it.
         */
        @Test
        void everyHashIsRegisteredAndNoneOverwritten() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);

            qrCode.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);
            qrCode.registerPostCommit(solana(ActionEnum.SENT, HASH_2, A), A);

            List<String> sentHashes = qrCode.getPendingEvents().stream()
                .filter(event -> PaymentEventTypeEnum.PAYMENT_SENT.equals(event.type()))
                .map(PaymentEvent::transactionId)
                .toList();
            assertEquals(List.of(HASH_1, HASH_2), sentHashes);
            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCode.getStatus(), "X9.150 never marks PAID");
        }
    }

    // ------------------------------------------------------- the contradiction, and SENT without a hash

    @Nested
    class Contradictions {

        /**
         * <b>X9-HOLD-118</b> — a PAYMENT_INITIATED carrying a hash on an ACTIVE QR Code carries both deviation codes.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC5). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> The payer skipped the question and mislabelled its report; both facts must
         * reach the payee so it can take them back to the payer.
         */
        @Test
        void aPaymentInitiatedWithAHashOnActiveCarriesBothCodes() {
            QRCodeEntity qrCode = qrCode();

            qrCode.registerPostCommit(solana(ActionEnum.PAYMENT_INITIATED, HASH_1, A), A);

            assertEquals(List.of(PaymentEventTypeEnum.PAYMENT_INITIATED, PaymentEventTypeEnum.PAYMENT_SENT),
                types(qrCode));
            qrCode.getPendingEvents().forEach(event -> assertEquals(
                List.of(NotificationDeviationEnum.RECEIVED_IN_WRONG_STATUS,
                    NotificationDeviationEnum.ACTION_CONTRADICTS_HASH),
                event.deviations()));
            assertEquals(ActionEnum.PAYMENT_INITIATED, qrCode.getPaymentNotification().data().blockchain().action(),
                "recorded as received, its declared action included");
        }

        /**
         * <b>X9-HOLD-119</b> — a PAYMENT_INITIATED carrying a hash from the holder carries ACTION_CONTRADICTS_HASH only.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC5). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> The holder reporting in the right status broke only one rule, and the
         * flags must say which.
         */
        @Test
        void aPaymentInitiatedWithAHashFromTheHolderCarriesOnlyTheContradiction() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);

            qrCode.registerPostCommit(solana(ActionEnum.PAYMENT_INITIATED, HASH_1, A), A);

            assertEquals(List.of(NotificationDeviationEnum.ACTION_CONTRADICTS_HASH), last(qrCode).deviations());
        }

        /**
         * <b>X9-HOLD-120</b> — a PAYMENT_INITIATED carrying a hash on a held or closed QR Code carries both codes.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.4-AC5). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> The flags are computed from the status found and the message, the same way
         * in every column.
         */
        @Test
        void aPaymentInitiatedWithAHashOnAHeldOrClosedQRCodeCarriesBothCodes() {
            QRCodeEntity held = heldBy(qrCode(), A);
            QRCodeEntity paid = qrCode();
            paid.pay(new PaymentDetailsVO(E2E, "fednow"));

            held.registerPostCommit(solana(ActionEnum.PAYMENT_INITIATED, HASH_1, B), B);
            paid.registerPostCommit(solana(ActionEnum.PAYMENT_INITIATED, HASH_1, B), B);

            List<NotificationDeviationEnum> both = List.of(NotificationDeviationEnum.RECEIVED_IN_WRONG_STATUS,
                NotificationDeviationEnum.ACTION_CONTRADICTS_HASH);
            assertEquals(both, last(held).deviations());
            assertEquals(both, last(paid).deviations());
            assertTrue(held.isHeldBy(A));
        }

        /**
         * <b>X9-HOLD-121</b> — SENT without a hash is a 400 in every status.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (the matrix). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> A committed transaction with no hash cannot exist, and there is nothing to
         * record.
         */
        @Test
        void sentWithoutAHashIsRefusedInEveryStatus() {
            QRCodeEntity active = qrCode();
            QRCodeEntity heldByA = heldBy(qrCode(), A);
            QRCodeEntity paid = qrCode();
            paid.pay(new PaymentDetailsVO(E2E, "fednow"));

            PaymentNotificationDataVO sentNoHash = solana(ActionEnum.SENT, null, A);
            assertEquals(NotificationPhaseEnum.PRE_COMMIT, sentNoHash.phase(), "no hash, so not a post-commit");

            for (QRCodeEntity qrCode : List.of(active, heldByA, paid)) {
                BusinessRuleException refused =
                    assertThrows(BusinessRuleException.class, () -> qrCode.admitPreCommit(sentNoHash, A));
                assertFalse(refused instanceof QRCodeStatusConflictException, "400, not 409");
            }
            assertThrows(BusinessRuleException.class, () -> heldBy(qrCode(), B).admitPreCommit(sentNoHash, A));
        }
    }

    // ------------------------------------------------------------------------- NOT_SENT

    @Nested
    class NotSent {

        /**
         * <b>X9-HOLD-122</b> — NOT_SENT from the holder releases the QR Code.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (the matrix). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> NOT_SENT is the only way a payer can give a reserved bill back before its
         * window lapses.
         */
        @Test
        void fromTheHolderItReleases() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);

            qrCode.releaseByPayer(solana(ActionEnum.NOT_SENT, null, A), A);

            assertEquals(QRCodeStatusEnum.ACTIVE, qrCode.getStatus());
            assertNull(qrCode.getReservedBy());
            assertEquals(PaymentEventTypeEnum.PAYMENT_FAILED, last(qrCode).type());
        }

        /**
         * <b>X9-HOLD-123</b> — NOT_SENT on an ACTIVE QR Code is a 400, unless from the holder of a lapsed reservation.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (the matrix). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> Nobody has taken a lapsed reservation since, so its holder may still give
         * it back; anybody else has nothing to release.
         */
        @Test
        void onActiveItIsRefusedUnlessFromTheHolderOfALapsedReservation() {
            QRCodeEntity never = qrCode();
            assertThrows(BusinessRuleException.class,
                () -> never.releaseByPayer(solana(ActionEnum.NOT_SENT, null, A), A));

            QRCodeEntity lapsed = qrCode();
            preCommit(lapsed, solana(ActionEnum.PAYMENT_INITIATED, null, A), A, Duration.ofSeconds(-1));

            assertThrows(BusinessRuleException.class,
                () -> lapsed.releaseByPayer(solana(ActionEnum.NOT_SENT, null, B), B));

            lapsed.releaseByPayer(solana(ActionEnum.NOT_SENT, null, A), A);
            assertEquals(PaymentEventTypeEnum.PAYMENT_FAILED, last(lapsed).type());
            assertEquals(QRCodeStatusEnum.ACTIVE, lapsed.getStatus());
        }

        /**
         * <b>X9-HOLD-124</b> — NOT_SENT from anybody but the holder is a 409.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (the matrix). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> A stranger must not release a reservation it never took.
         */
        @Test
        void fromAnybodyButTheHolderItIsAConflict() {
            QRCodeEntity heldByA = heldBy(qrCode(), A);

            assertThrows(QRCodeStatusConflictException.class,
                () -> heldByA.releaseByPayer(solana(ActionEnum.NOT_SENT, null, B), B));
            assertTrue(heldByA.isHeldBy(A));
        }

        /**
         * <b>X9-HOLD-125</b> — NOT_SENT on a PAID or CANCELLED QR Code is a 400.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (the matrix). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> A closed bill must not take a new payment; it is the request that is
         * wrong, so 400, not 409.
         */
        @Test
        void onAClosedQRCodeItIsRefusedWith400() {
            QRCodeEntity paid = qrCode();
            paid.pay(new PaymentDetailsVO(E2E, "fednow"));

            BusinessRuleException refused = assertThrows(BusinessRuleException.class,
                () -> paid.releaseByPayer(solana(ActionEnum.NOT_SENT, null, A), A));
            assertFalse(refused instanceof QRCodeStatusConflictException);
        }
    }

    // ------------------------------------------------------------------------- the window

    @Nested
    class TheWindow {

        /**
         * <b>X9-HOLD-126</b> — a hash from the holder stops the window.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC6). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> Once money has moved for a QR Code, letting the reservation lapse would
         * let a second payer be approved for a bill already paid.
         */
        @Test
        void aHashFromTheHolderStopsTheWindow() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);

            qrCode.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);

            assertEquals(QRCodeEntity.NO_LAPSE, qrCode.getInitiatedExpiresAt());
            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCode.effectiveStatus(aDayLater()));
            assertTrue(qrCode.isHeldBy(A));
        }

        /**
         * <b>X9-HOLD-127</b> — a hash from anybody stops the window, and the holder does not change.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC6). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> It does not matter who reported the money: the QR Code must stay out of
         * circulation until the payment system decides.
         */
        @Test
        void aHashFromAnybodyStopsTheWindow() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);

            qrCode.registerPostCommit(fedNow(E2E, B), B);

            assertTrue(qrCode.isWindowStopped());
            assertTrue(qrCode.isHeldBy(A));
            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCode.effectiveStatus(aDayLater()));
        }

        /**
         * <b>X9-HOLD-128</b> — after a hash, even the holder's retried pre-commit is a 409 and changes nothing.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (§, owner 2026-11-14). X9.150 defines no reservation.
         *
         * <p><b>Why:</b> Once money has moved no payer message may change the QR Code's status; a
         * retry that was judged again could be refused and release a QR Code already paid, or
         * restart the window the hash had stopped.
         */
        @Test
        void afterAHashTheHoldersRetryIsAConflict() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);
            qrCode.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);

            assertThrows(QRCodeStatusConflictException.class,
                () -> qrCode.admitPreCommit(solana(ActionEnum.PAYMENT_INITIATED, null, A), A));

            assertTrue(qrCode.isWindowStopped());
            assertTrue(qrCode.isHeldBy(A));
        }

        /**
         * <b>X9-HOLD-138</b> — after a hash, the holder's NOT_SENT is accepted, flagged ACTION_CONTRADICTS_HASH, and changes nothing.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (§, owner 2026-11-14).
         *
         * <p><b>Why:</b> The payer says "not sent" for money it already reported as moved. Releasing
         * would let a second payer pay a QR Code already paid; only the payment system may end it.
         */
        @Test
        void afterAHashTheHoldersNotSentChangesNothing() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);
            qrCode.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);
            int events = qrCode.getPendingEvents().size();

            NotificationReceiptVO receipt = qrCode.releaseByPayer(solana(ActionEnum.NOT_SENT, null, A), A);

            assertEquals(List.of(NotificationDeviationEnum.ACTION_CONTRADICTS_HASH), receipt.deviations());
            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCode.getStatus());
            assertTrue(qrCode.isHeldBy(A));
            assertTrue(qrCode.isHashRegistered());
            assertEquals(events, qrCode.getPendingEvents().size(), "no payment.failed");
        }

        /**
         * <b>X9-HOLD-139</b> — a NOT_SENT carrying a transaction id is a post-commit, flagged ACTION_CONTRADICTS_HASH.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (owner 2026-11-14): the evidence decides the phase.
         *
         * <p><b>Why:</b> Keyed on the action it released the QR Code and threw the hash away, the
         * one link between the funds and the bill.
         */
        @Test
        void aNotSentCarryingAHashIsAPostCommit() {
            PaymentNotificationDataVO notSentWithHash = solana(ActionEnum.NOT_SENT, HASH_1, A);
            assertEquals(NotificationPhaseEnum.POST_COMMIT, notSentWithHash.phase());

            QRCodeEntity qrCode = heldBy(qrCode(), A);
            qrCode.registerPostCommit(notSentWithHash, A);

            assertEquals(PaymentEventTypeEnum.PAYMENT_SENT, last(qrCode).type());
            assertEquals(List.of(NotificationDeviationEnum.ACTION_CONTRADICTS_HASH), last(qrCode).deviations());
            assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCode.getStatus());
        }

        /**
         * <b>X9-HOLD-140</b> — the status-update API's revert clears the registered hash with the reservation.
         *
         * <p><b>Source:</b> Ours. ADR-0036.
         *
         * <p><b>Why:</b> A flag left behind would block the next payer's retries on a reservation
         * that never saw a hash.
         */
        @Test
        void theRevertClearsTheRegisteredHash() {
            QRCodeEntity qrCode = heldBy(qrCode(), A);
            qrCode.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);

            qrCode.reactivate(null);
            heldBy(qrCode, B);

            assertFalse(qrCode.isHashRegistered());
            assertTrue(qrCode.admitPreCommit(solana(ActionEnum.PAYMENT_INITIATED, null, B), B));
        }

        /**
         * <b>X9-HOLD-129</b> — only the status-update API ends a reservation whose window a hash stopped.
         *
         * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC6). X9.150 defines no reservation and no phase marker.
         *
         * <p><b>Why:</b> Once a hash is registered only the payment system can see whether funds
         * arrived, so only it may end the reservation.
         */
        @Test
        void onlyTheStatusUpdateApiEndsAStoppedReservation() {
            QRCodeEntity reactivated = heldBy(qrCode(), A);
            reactivated.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);
            reactivated.reactivate(null);
            assertEquals(QRCodeStatusEnum.ACTIVE, reactivated.effectiveStatus());
            assertNull(reactivated.getReservedBy());

            QRCodeEntity paid = heldBy(qrCode(), A);
            paid.registerPostCommit(solana(ActionEnum.SENT, HASH_1, A), A);
            paid.pay(new PaymentDetailsVO(HASH_1, "solana"));
            assertEquals(QRCodeStatusEnum.PAID, paid.effectiveStatus());
            assertNull(paid.getInitiatedExpiresAt());
        }

    }

}
