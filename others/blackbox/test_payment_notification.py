"""
Every way a payment notification can be wrong, and what the payee answers.

Black-box: this drives the published API of a running deployment and never imports the service.
It complements the JUnit tests rather than replacing them — JUnit proves a rule in isolation,
this proves the rule survives serialisation, validation order, HTTP and the signature layer.

The house rule here is that **a refusal must be refused for the right reason**. Asserting only
`status >= 400` is how a check passes because the fixture was never built, or because an
unrelated rule fired first — both of which have happened in this repo. `assert_refused` insists
on the field named in the violation.
"""
import pytest

from conftest import BILL, FAR_FUTURE, FEDNOW, PAYER_WALLET, RECIPIENT, TX_HASH, assert_refused

TIP_10_TO_25 = {"allowed": True, "range": {"min": 10, "max": 25}, "presets": [10, 15, 20]}
TIP_PRESETS_ONLY = {"allowed": True, "presets": [10, 15, 20]}
EDITABLE_500_TO_2000 = {"range": {"min": 500, "max": 2000}}


# ------------------------------------------------------------------ transport and signature

class TestTransport:

    def test_an_unsigned_body_is_refused(self, api):
        qr = api.create_qr()
        status, body = api.notify(qr, raw="not-a-jws")
        assert status == 401, f"an unsigned notification must not be read: {status} {body}"

    def test_an_empty_body_is_refused(self, api):
        qr = api.create_qr()
        status, _ = api.notify(qr, raw="")
        assert status >= 400

    def test_a_tampered_payload_is_refused(self, api):
        """Flip a character in the claims segment — the signature must stop covering it."""
        qr = api.create_qr()
        token = api.sign({"payment": {"qrcodeId": qr, "amount": BILL,
                                      "currency": "USDC", "network": "Solana"},
                          "payer": {"info": "blackbox@example.com"},
                          "blockchain": {"action": "PAYMENT_INITIATED",
                                         "from": PAYER_WALLET, "to": RECIPIENT}})
        header, claims, signature = token.split(".")
        tampered = f"{header}.{claims[:-2]}{'A' if claims[-2] != 'A' else 'B'}{claims[-1]}.{signature}"

        status, _ = api.call("POST", "/pub/api/v1/payment-notification", raw=tampered,
                             headers={"Content-Type": "application/jose"})
        assert status >= 400, "a payload edited after signing must not be accepted"


# ------------------------------------------------------------------------------- identity

class TestIdentity:

    def test_an_unknown_qr_code_is_404(self, api):
        status, body = api.notify("0" * 32)
        assert status == 404, f"{status} {body}"


# --------------------------------------------------------------------------------- amount

class TestFixedAmount:

    @pytest.mark.parametrize("amount, why", [
        (BILL - 1, "a penny short is not the bill"),
        (BILL + 1, "a penny over is not the bill either"),
        (0, "nothing was paid"),
    ])
    def test_an_amount_that_is_not_the_bill_is_refused(self, api, amount, why):
        qr = api.create_qr()
        assert_refused(api.notify(qr, amount=amount), naming="payment.amount")

    def test_a_negative_amount_is_refused(self, api):
        qr = api.create_qr()
        assert_refused(api.notify(qr, amount=-1), naming="negative")

    def test_the_exact_bill_is_accepted(self, api):
        """The control. Without it, the refusals above could all be refusing everything."""
        qr = api.create_qr()
        status, body = api.notify(qr, amount=BILL)
        assert status == 200, f"{status} {body}"


class TestEditableAmount:

    @pytest.mark.parametrize("amount", [499, 2001])
    def test_an_amount_outside_the_published_range_is_refused(self, api, amount):
        qr = api.create_qr(editable=EDITABLE_500_TO_2000)
        assert_refused(api.notify(qr, amount=amount), naming="editable within")

    @pytest.mark.parametrize("amount", [500, 1234, 2000])
    def test_any_amount_inside_the_published_range_is_accepted(self, api, amount):
        qr = api.create_qr(editable=EDITABLE_500_TO_2000)
        status, body = api.notify(qr, amount=amount)
        assert status == 200, f"{amount} is inside 500..2000: {status} {body}"


# ------------------------------------------------------------------------------------ tip
#
# ANSI X9.150-2026 13.6.2: the notification carries the TOTAL, tip included. The merchant's
# share is `amount - tipAmount`, and that is what the bill is judged against.

class TestTip:

    def test_the_bill_plus_a_tip_inside_the_range_is_accepted(self, api):
        qr = api.create_qr(tip=TIP_10_TO_25)
        status, body = api.notify(qr, amount=BILL + 200, tip=200)
        assert status == 200, f"20% of a 1000 bill is inside 10..25%: {status} {body}"

    def test_a_tip_taken_out_of_the_merchants_share_is_refused(self, api):
        """
        The money-losing case. 1000 arrives carrying a 200 tip, so the merchant receives 800
        on a 1000 bill — and before this rule existed the QR Code was marked paid in full.
        """
        qr = api.create_qr(tip=TIP_10_TO_25)
        assert_refused(api.notify(qr, amount=BILL, tip=200), naming="payment.amount")

    def test_a_tip_on_a_bill_that_refuses_tips_is_refused(self, api):
        qr = api.create_qr()                       # no tip block -> stored as allowed: false
        assert_refused(api.notify(qr, amount=BILL + 100, tip=100),
                       naming="does not accept tips")

    @pytest.mark.parametrize("tip, why", [
        (50, "5% is below the published 10%"),
        (300, "30% is above the published 25%"),
        (999_999, "absurd"),
    ])
    def test_a_tip_outside_the_published_range_is_refused(self, api, tip, why):
        qr = api.create_qr(tip=TIP_10_TO_25)
        assert_refused(api.notify(qr, amount=BILL + tip, tip=tip), naming="payment.tipAmount")

    def test_a_tip_of_zero_is_refused_rather_than_ignored(self, api):
        qr = api.create_qr(tip=TIP_10_TO_25)
        assert_refused(api.notify(qr, amount=BILL, tip=0), naming="greater than zero")

    def test_a_negative_tip_is_refused(self, api):
        qr = api.create_qr(tip=TIP_10_TO_25)
        assert_refused(api.notify(qr, amount=BILL, tip=-1), naming="negative")

    def test_a_transfer_that_is_entirely_tip_is_refused(self, api):
        """Nothing reaches the merchant, so there is no bill being settled."""
        qr = api.create_qr(tip=TIP_10_TO_25)
        assert_refused(api.notify(qr, amount=200, tip=200), naming="payment.amount")

    def test_a_tip_larger_than_the_transfer_carrying_it_is_refused(self, api):
        """Presets-only, so no range fires first and this rule is the one under test."""
        qr = api.create_qr(tip=TIP_PRESETS_ONLY)
        assert_refused(api.notify(qr, amount=BILL, tip=BILL + 1), naming="payment.tipAmount")

    def test_presets_do_not_bind_when_no_range_is_published(self, api):
        """
        A.10 validates even a preset-selected tip against min..max, so the range is the rule
        and the presets are the suggested buttons. 13% is offered by no preset and is legal.
        """
        qr = api.create_qr(tip=TIP_PRESETS_ONLY)
        status, body = api.notify(qr, amount=BILL + 130, tip=130)
        assert status == 200, f"{status} {body}"

    def test_the_tip_percentage_is_taken_against_the_merchants_share(self, api):
        """
        A 1000 bill tipped 200 arrives as 1200, and that is 20% — not 16.7% of the transfer.
        """
        qr = api.create_qr(tip={"allowed": True, "range": {"min": 19, "max": 21},
                                "presets": [20]})
        status, body = api.notify(qr, amount=BILL + 200, tip=200)
        assert status == 200, f"200 on a 1000 bill is 20%, inside 19..21: {status} {body}"

    def test_on_an_editable_amount_the_percentage_is_against_the_face_amount(self, api):
        """
        CURRENT BEHAVIOUR, pinned deliberately rather than endorsed.

        With a face amount of 1000 editable to 500..2000 and a tip range of 0..25%, a payer who
        chooses 2000 and tips 400 — 20% of what they are actually paying — is REFUSED, because
        400 is 40% of the face amount. The ceiling follows the biller's reference figure, not
        the payer's chosen one.

        Whether that is the right basis is a business question, not a settled one. This test
        exists so the answer cannot change by accident.
        """
        qr = api.create_qr(tip={"allowed": True, "range": {"min": 0, "max": 25},
                                "presets": [10, 15, 20]},
                           editable=EDITABLE_500_TO_2000)
        assert_refused(api.notify(qr, amount=2000 + 400, tip=400), naming="payment.tipAmount")

        accepted = api.create_qr(tip={"allowed": True, "range": {"min": 0, "max": 25},
                                      "presets": [10, 15, 20]},
                                 editable=EDITABLE_500_TO_2000)
        status, body = api.notify(accepted, amount=2000 + 250, tip=250)
        assert status == 200, f"250 is 25% of the face amount: {status} {body}"


# ------------------------------------------------------------------------- currency and rail

class TestCurrencyAndRail:

    def test_a_currency_the_qr_code_does_not_offer_is_refused(self, api):
        qr = api.create_qr(currency="USDC")
        assert_refused(api.notify(qr, currency="USD"), naming="currency")

    def test_a_destination_address_the_qr_code_never_published_is_refused(self, api):
        qr = api.create_qr()
        token = api.sign({"payment": {"qrcodeId": qr, "amount": BILL,
                                      "currency": "USDC", "network": "Solana"},
                          "payer": {"info": "blackbox@example.com"},
                          "blockchain": {"action": "PAYMENT_INITIATED", "from": PAYER_WALLET,
                                         "to": "11111111111111111111111111111111"}})
        assert_refused(api.call("POST", "/pub/api/v1/payment-notification", raw=token,
                                headers={"Content-Type": "application/jose"}),
                       naming="destination address")

    def test_blockchain_data_is_refused_on_a_bank_rail(self, api):
        qr = api.create_qr(currency="USD", networks={"fednow": FEDNOW})
        status, body = api.notify(qr, currency="USD", network="fednow", blockchain=True)
        assert status >= 400, f"{status} {body}"


# ------------------------------------------------------------------------------- lifecycle

class TestLifecycle:

    def test_a_second_pre_payment_is_refused(self, api):
        qr = api.create_qr()
        first, body = api.notify(qr)
        assert first == 200, f"the first pre-payment should be accepted: {first} {body}"
        assert_refused(api.notify(qr), naming="blockchain.action")

    def test_a_payment_on_a_cancelled_qr_code_is_refused(self, api):
        qr = api.create_qr()
        api.call("PUT", f"/api/v1/payment-request/{qr}/status-update",
                 body={"status": "CANCELLED"})
        assert api.status_of(qr) == "CANCELLED"
        assert_refused(api.notify(qr), naming="blockchain.action")

    def test_a_post_payment_before_any_pre_payment_is_refused(self, api):
        qr = api.create_qr()
        assert_refused(api.notify(qr, action="SENT", transaction_id=TX_HASH),
                       naming="blockchain.action")

    def test_a_refused_notification_leaves_the_qr_code_active(self, api):
        """
        The half-reservation check. A notification refused on its amount must not leave the
        QR Code reserved, or the payer cannot retry and nobody else can pay it either.
        """
        qr = api.create_qr()
        assert_refused(api.notify(qr, amount=BILL + 1), naming="payment.amount")
        assert api.status_of(qr) == "ACTIVE", "a refused payment must not reserve the QR Code"
