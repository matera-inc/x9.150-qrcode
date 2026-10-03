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
        """X9-SIG-001 — an unsigned body is refused.

        Source: ANSI X9.150-2026 §9 — a payment notification is a signed message. Conformance.

        Why: The endpoint is public and unauthenticated. The signature is the only thing establishing who
    sent this, so an unsigned body must not be read at all, let alone acted on.
        """
        qr = api.create_qr()
        status, body = api.notify(qr, raw="not-a-jws")
        assert status == 401, f"an unsigned notification must not be read: {status} {body}"

    def test_an_empty_body_is_refused(self, api):
        """X9-SIG-002 — an empty body is refused.

        Source: Mechanism.

        Why: An empty body must fail as malformed rather than reaching any rule that might treat missing
    fields as defaults.
        """
        qr = api.create_qr()
        status, _ = api.notify(qr, raw="")
        assert status >= 400

    def test_a_tampered_payload_is_refused(self, api):
        """X9-SIG-003 — a payload edited after signing is refused.

        Source: RFC 7515 — the signature covers the claims. Conformance.

        Why: Flips one character in the claims segment. If this passed, every other check in this file
    would be theatre: an attacker could sign a valid notification and then rewrite the amount.
        """
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
        """X9-LIFE-001 — a notification for an unknown QR Code is 404.

        Source: Mechanism.

        Why: 404 rather than 400, because the request is well-formed and the subject simply is not here.
    A payer PSP retrying on 400 and giving up on 404 needs them told apart.
        """
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
        """X9-AMT-001 — an amount that is not the bill is refused.

        Source: ANSI X9.150-2026 §13.5.1 — the bill states what is owed. Conformance.

        Why: A penny short and a penny over are both wrong, and the second is not generosity: accepting an
    overpayment silently leaves the payee holding money the bill never asked for.
        """
        qr = api.create_qr()
        assert_refused(api.notify(qr, amount=amount), naming="payment.amount")

    def test_a_negative_amount_is_refused(self, api):
        """X9-AMT-002 — a negative amount is refused.

        Source: ANSI X9.150-2026 §2.1 — <i>"SHALL be a 64 bit integer with minimum value = 0"</i>. Conformance.

        Why: Refused in the value object, before any business rule sees it, so no later arithmetic has to
    consider what a negative payment would mean.
        """
        qr = api.create_qr()
        assert_refused(api.notify(qr, amount=-1), naming="negative")

    def test_the_exact_bill_is_accepted(self, api):
        """X9-AMT-003 — the exact bill is accepted.

        Source: Mechanism. The control for this group.

        Why: Without it, every refusal above would still pass against a service that refused all payments.
        """
        qr = api.create_qr()
        status, body = api.notify(qr, amount=BILL)
        assert status == 200, f"{status} {body}"


class TestEditableAmount:

    @pytest.mark.parametrize("amount", [499, 2001])
    def test_an_amount_outside_the_published_range_is_refused(self, api, amount):
        """X9-AMT-004 — an amount outside the published editable range is refused.

        Source: ANSI X9.150-2026 §14.4.1 — when editable is present, range is mandatory. Conformance.

        Why: The range is the promise the QR Code made to the payer. Outside it, neither party agreed.
        """
        qr = api.create_qr(editable=EDITABLE_500_TO_2000)
        assert_refused(api.notify(qr, amount=amount), naming="editable within")

    @pytest.mark.parametrize("amount", [500, 1234, 2000])
    def test_any_amount_inside_the_published_range_is_accepted(self, api, amount):
        """X9-AMT-005 — any amount inside the published range is accepted.

        Source: ANSI X9.150-2026 §14.4 — the presence of editable means the payer chooses. Conformance.

        Why: Demanding the face amount back would refuse every legitimate use of the feature: a donation,
    a top-up, an open tab. Three amounts, including both bounds, because inclusive-or-exclusive is
    exactly the kind of thing that drifts.
        """
        qr = api.create_qr(editable=EDITABLE_500_TO_2000)
        status, body = api.notify(qr, amount=amount)
        assert status == 200, f"{amount} is inside 500..2000: {status} {body}"


# ------------------------------------------------------------------------------------ tip
#
# ANSI X9.150-2026 13.6.2: the notification carries the TOTAL, tip included. The merchant's
# share is `amount - tipAmount`, and that is what the bill is judged against.

class TestTip:

    def test_the_bill_plus_a_tip_inside_the_range_is_accepted(self, api):
        """X9-TIP-101 — the bill plus a tip inside the range is accepted.

        Source: ANSI X9.150-2026 §2.1 with §13.6.2. Conformance.

        Why: The end-to-end acceptance case for tipping, over HTTP rather than in the domain.
        """
        qr = api.create_qr(tip=TIP_10_TO_25)
        status, body = api.notify(qr, amount=BILL + 200, tip=200)
        assert status == 200, f"20% of a 1000 bill is inside 10..25%: {status} {body}"

    def test_a_tip_taken_out_of_the_merchants_share_is_refused(self, api):
        """X9-TIP-102 — a tip taken out of the merchant's share is refused.

        Source: ANSI X9.150-2026 §2.1 with §13.6.2 — the notification carries the total, tip included.
    Conformance.

        Why: 1000 arrives carrying a 200 tip, so the merchant receives 800 on a 1000 bill and the QR Code
    used to be marked paid in full. This is the one that cost a release.
        """
        qr = api.create_qr(tip=TIP_10_TO_25)
        assert_refused(api.notify(qr, amount=BILL, tip=200), naming="payment.amount")

    def test_a_tip_on_a_bill_that_refuses_tips_is_refused(self, api):
        """X9-TIP-103 — a tip on a bill that refuses tips is refused.

        Source: ANSI X9.150-2026 §13.6.1, addressed to the payer app. Enforcing it at the payee is ours — I-10.

        Why: A rule living only in the counterparty's client is not a rule.
        """
        qr = api.create_qr()                       # no tip block -> stored as allowed: false
        assert_refused(api.notify(qr, amount=BILL + 100, tip=100),
                       naming="does not accept tips")

    @pytest.mark.parametrize("tip, why", [
        (50, "5% is below the published 10%"),
        (300, "30% is above the published 25%"),
        (999_999, "absurd"),
    ])
    def test_a_tip_outside_the_published_range_is_refused(self, api, tip, why):
        """X9-TIP-104 — a tip outside the published range is refused.

        Source: ANSI X9.150-2026 A.10, addressed to the payer app. Enforcement at the payee is ours — I-10.

        Why: Below, above, and absurd. The absurd case is the shape of a payer app sending minor units
    where it meant percent.
        """
        qr = api.create_qr(tip=TIP_10_TO_25)
        assert_refused(api.notify(qr, amount=BILL + tip, tip=tip), naming="payment.tipAmount")

    def test_a_tip_of_zero_is_refused_rather_than_ignored(self, api):
        """X9-TIP-105 — a tip of zero is refused rather than ignored.

        Source: Ours. ADR-0017.

        Why: A zero tip is a field the sender did not mean to send. Refusing it is louder than silently
    dropping it, and tells the payer app its tipping UI is wired wrong.
        """
        qr = api.create_qr(tip=TIP_10_TO_25)
        assert_refused(api.notify(qr, amount=BILL, tip=0), naming="greater than zero")

    def test_a_negative_tip_is_refused(self, api):
        """X9-TIP-106 — a negative tip is refused.

        Source: ANSI X9.150-2026 §2.2 — minimum value = 0. Conformance.

        Why: A negative tip would increase the merchant's share above the transfer, inventing money.
        """
        qr = api.create_qr(tip=TIP_10_TO_25)
        assert_refused(api.notify(qr, amount=BILL, tip=-1), naming="negative")

    def test_a_transfer_that_is_entirely_tip_is_refused(self, api):
        """X9-TIP-107 — a transfer that is entirely tip is refused.

        Source: Ours. ADR-0017.

        Why: Nothing reaches the merchant, so there is no bill being settled — only a gratuity attached to
    a payment that did not happen.
        """
        qr = api.create_qr(tip=TIP_10_TO_25)
        assert_refused(api.notify(qr, amount=200, tip=200), naming="payment.amount")

    def test_a_tip_larger_than_the_transfer_carrying_it_is_refused(self, api):
        """X9-TIP-108 — a tip larger than the transfer carrying it is refused.

        Source: Ours. ADR-0017.

        Why: Presets-only on purpose, so no range check fires first and the rule under test is the one
    being measured. A test that passes because a different rule rejected the input is not a test.
        """
        qr = api.create_qr(tip=TIP_PRESETS_ONLY)
        assert_refused(api.notify(qr, amount=BILL, tip=BILL + 1), naming="payment.tipAmount")

    def test_presets_do_not_bind_when_no_range_is_published(self, api):
        """X9-TIP-109 — presets do not bind when no range is published.

        Source: ANSI X9.150-2026 A.10 validates a PRESET-SELECTED tip against min..max too, so presets are
    suggestions. Reading them as advisory is ours — I-10.

        Why: 13% is offered by no preset and is legitimate. Consequence stated plainly in I-10: presets
    with no range accept a tip of any size, so a biller wanting a ceiling must publish a range.
        """
        qr = api.create_qr(tip=TIP_PRESETS_ONLY)
        status, body = api.notify(qr, amount=BILL + 130, tip=130)
        assert status == 200, f"{status} {body}"

    def test_the_tip_percentage_is_taken_against_the_merchants_share(self, api):
        """X9-TIP-110 — the tip percentage is taken against the merchant's share.

        Source: ANSI X9.150-2026 §13.6.2 Note — the percentage applies to the Bill Amount Due. Conformance.

        Why: 200 on a 1000 bill is 20%, not 16.7% of the 1200 transferred. Getting the denominator wrong
    silently moves every range boundary.
        """
        qr = api.create_qr(tip={"allowed": True, "range": {"min": 19, "max": 21},
                                "presets": [20]})
        status, body = api.notify(qr, amount=BILL + 200, tip=200)
        assert status == 200, f"200 on a 1000 bill is 20%, inside 19..21: {status} {body}"

    def test_on_an_editable_amount_the_percentage_is_of_what_is_being_paid(self, api):
        """X9-TIP-111 — on an editable amount the percentage is of what is being paid.

        Source: Ours. ADR-0017. The standard does not say which figure a percentage applies to when
    the payer chooses the amount, and §13.6.2's note is a MAY addressed to the payer's application.
    The payer computes the tip from the amount they selected, so that is the figure we check against.

        Why: An invoice of 1000 settleable from 400, offering 10%. A payer settling 400 may tip 40.
    Taking the percentage against the FACE amount instead let them tip 100 — a quarter of what they
    were actually paying, on a bill that offered ten percent. The merchant's share and the tip basis
    are now the same number, which is also the number the payer used.
        """
        TIP_10 = {"allowed": True, "range": {"min": 0, "max": 10}, "presets": [10]}
        PART = {"range": {"min": 400, "max": BILL}}

        qr = api.create_qr(tip=TIP_10, editable=PART)
        status, body = api.notify(qr, amount=400 + 40, tip=40)
        assert status == 200, f"40 is 10% of the 400 being paid: {status} {body}"

        qr = api.create_qr(tip=TIP_10, editable=PART)
        assert_refused(api.notify(qr, amount=400 + 100, tip=100), naming="payment.tipAmount")

    def test_a_tip_one_minor_unit_off_its_bound_is_tolerated(self, api):
        """X9-TIP-112 — a tip one minor unit outside its bound is tolerated.

        Source: Ours. ADR-0017.

        Why: Tip ranges are percentages and payments are integers in the smallest currency unit, so
    the bound is a rounded product. A payer computing the same percentage — possibly after converting
    from another currency, rounding again, under no obligation to round the way we do — can land one
    unit either side of our figure having done nothing wrong. Refusing that declines a correct payment
    over a cent nobody could have avoided.
        """
        tip_0_to_10 = {"allowed": True, "range": {"min": 0, "max": 10}, "presets": [10]}
        ceiling = BILL // 10

        qr = api.create_qr(tip=tip_0_to_10)
        status, body = api.notify(qr, amount=BILL + ceiling + 1, tip=ceiling + 1)
        assert status == 200, f"one over the bound is rounding, not a breach: {status} {body}"

    def test_a_tip_two_minor_units_off_its_bound_is_refused(self, api):
        """X9-TIP-113 — a tip two minor units outside its bound is refused.

        Source: Ours. ADR-0017.

        Why: The limit of X9-TIP-112. The tolerance absorbs rounding, which is one unit; it is not a
    licence to exceed the published range. Without this test the tolerance could be widened later and
    nothing would notice.
        """
        tip_0_to_10 = {"allowed": True, "range": {"min": 0, "max": 10}, "presets": [10]}
        ceiling = BILL // 10

        qr = api.create_qr(tip=tip_0_to_10)
        assert_refused(api.notify(qr, amount=BILL + ceiling + 2, tip=ceiling + 2),
                       naming="payment.tipAmount")


class TestPayerInfo:

    def test_a_payer_info_at_the_standards_limit_is_accepted(self, api):
        """X9-SIG-110 — a payer.info of 254 characters is accepted.

        Source: ANSI X9.150-2026 Table 4 (Payment Notification Requirements), row 3.1 Payer Info, which
    gives the length as 254; §3.1 repeats it as "SHALL be string with 254 maximum characters".
    Conformance.

        Why: This deployment capped it at 140, so a spec-legal value was refused. 140 is the ISO
    20022 RemittanceInformation limit and belongs to `unstructured`, a different field; it looks to
    have been copied across. It matters more than a stray constant because payer.info is the only
    payer field the standard defines, and the one we ask counterparties to carry an identifier in.
        """
        qr = api.create_qr()
        status, body = api.notify(qr, payer_info="x" * 254)
        assert status == 200, f"254 is the standard's maximum: {status} {body}"

    def test_a_payer_info_beyond_the_standards_limit_is_refused(self, api):
        """X9-SIG-111 — a payer.info of 255 characters is refused.

        Source: ANSI X9.150-2026 Table 4, row 3.1 Payer Info, and §3.1. Conformance.

        Why: The limit of X9-SIG-110. Raising a cap to the right number is only half the change —
    without this the next edit could remove the bound entirely and nothing would notice.
        """
        qr = api.create_qr()
        status, _ = api.notify(qr, payer_info="x" * 255)
        assert status >= 400, f"255 is past the standard's maximum, got {status}"


class TestEventStream:

    @staticmethod
    def _events_for(api, qr_id):
        """Drain is asynchronous, so poll with the long-polling cursor rather than reading blind."""
        seen, cursor = {}, ""
        for _ in range(20):
            status, page = api.call("GET", f"/pub/api/v1/events?after={cursor}&limit=200&wait=2")
            if status != 200 or not isinstance(page, dict):
                break
            for event in page.get("events", []):
                if event.get("qrCodeId") == qr_id:
                    seen[event["eventId"]] = event
            cursor = page.get("nextCursor") or cursor
            if seen:
                break
        return list(seen.values())

    def test_the_event_carries_the_tip_the_payer_reported(self, api):
        """X9-EVT-030 — the payment event carries the tip the payer reported.

        Source: Ours. ADR-0014 — we transport and sequence, the consuming system reconciles. X9.150
    defines no event stream.

        Why: `amount` on an event is the TOTAL, tip included, so without `tipAmount` a consumer
    reading the stream sees one number and cannot tell what part of it settled the bill. It could
    recover the split by fetching every payment request individually, which defeats the stream.

    Reported, not computed: this service neither receives money nor pays anyone. It raises payment
    requests and validates notifications, so the tip is transported exactly as it arrived for the
    consuming system to reconcile against what its accounts actually received.
        """
        tip = BILL // 10
        qr = api.create_qr(tip={"allowed": True, "range": {"min": 0, "max": 10}, "presets": [10]})

        status, body = api.notify(qr, amount=BILL + tip, tip=tip)
        assert status == 200, f"{status} {body}"

        mine = self._events_for(api, qr)
        assert mine, f"no event for {qr}"

        event = mine[-1]
        assert event.get("amount") == BILL + tip, f"amount should be the total: {event}"
        assert event.get("tipAmount") == tip, f"tipAmount should be the reported tip: {event}"
        assert event["amount"] - event["tipAmount"] == BILL, (
            f"the consumer must be able to derive what settled the bill: {event}")

    def test_an_event_for_a_payment_with_no_tip_reports_no_tip(self, api):
        """X9-EVT-031 — an event for a payment with no tip reports no tip.

        Source: Ours. ADR-0014.

        Why: Absent rather than zero. A consumer has to tell "no tip was reported" from "a tip of
    nothing was reported", and defaulting to 0 would quietly make every untipped payment look like an
    explicit decision not to tip.
        """
        qr = api.create_qr()
        status, body = api.notify(qr, amount=BILL)
        assert status == 200, f"{status} {body}"

        mine = self._events_for(api, qr)
        assert mine, f"no event for {qr}"
        assert mine[-1].get("tipAmount") in (None, ), f"expected no tip reported: {mine[-1]}"


# ------------------------------------------------------------------------- currency and rail

class TestCurrencyAndRail:

    def test_a_currency_the_qr_code_does_not_offer_is_refused(self, api):
        """X9-CUR-001 — a currency the QR Code does not offer is refused.

        Source: ANSI X9.150-2026 §14 — the QR Code publishes what it accepts. Conformance.

        Why: Paying in a currency the code never offered has no agreed rate and no agreed rail.
        """
        qr = api.create_qr(currency="USDC")
        assert_refused(api.notify(qr, currency="USD"), naming="currency")

    def test_a_destination_address_the_qr_code_never_published_is_refused(self, api):
        """X9-RAIL-001 — a destination address the QR Code never published is refused.

        Source: Ours. ADR-0012 — refuse what this deployment cannot honour.

        Why: Money sent to an address this QR Code never advertised did not pay this bill, whoever it
    reached. Accepting it would mark the bill paid while the creditor received nothing.
        """
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
        """X9-RAIL-002 — blockchain data is refused on a bank rail.

        Source: ANSI X9.150-2026 §14.5 — the inner object belongs to the named network. Ours in enforcement.

        Why: A FedNow payment carrying a blockchain block is a sender confusing two rails. Accepting it
    would record a settlement whose evidence points at the wrong network.
        """
        qr = api.create_qr(currency="USD", networks={"fednow": FEDNOW})
        status, body = api.notify(qr, currency="USD", network="fednow", blockchain=True)
        assert status >= 400, f"{status} {body}"


# ------------------------------------------------------------------------------- lifecycle

class TestLifecycle:

    def test_a_second_pre_payment_is_refused(self, api):
        """X9-LIFE-002 — a second pre-payment is refused.

        Source: Ours. The reservation is this implementation's, not the standard's. ADR-0002.

        Why: Two payers must not both believe they hold the same QR Code. The first reservation wins and
    the second is told why, rather than both proceeding to pay the same bill.
        """
        qr = api.create_qr()
        first, body = api.notify(qr)
        assert first == 200, f"the first pre-payment should be accepted: {first} {body}"
        assert_refused(api.notify(qr), naming="blockchain.action")

    def test_a_payment_on_a_cancelled_qr_code_is_refused(self, api):
        """X9-LIFE-003 — a payment on a cancelled QR Code is refused.

        Source: ANSI X9.150-2026 §9 — status governs what may still happen. Conformance.

        Why: A cancelled bill is withdrawn. Accepting payment against it takes money for something the
    biller has already said is no longer owed.
        """
        qr = api.create_qr()
        api.call("PUT", f"/api/v1/payment-request/{qr}/status-update",
                 body={"status": "CANCELLED"})
        assert api.status_of(qr) == "CANCELLED"
        assert_refused(api.notify(qr), naming="blockchain.action")

    def test_a_post_payment_before_any_pre_payment_is_refused(self, api):
        """X9-LIFE-004 — a post-payment with no pre-payment is refused.

        Source: Ours — the two-phase sequence is this implementation's. ADR-0002.

        Why: The phases exist so the payee can reserve before money moves. A report of completion for a
    payment never announced means the sequence was skipped, and the reservation never happened.
        """
        qr = api.create_qr()
        assert_refused(api.notify(qr, action="SENT", transaction_id=TX_HASH),
                       naming="blockchain.action")

    def test_a_refused_notification_leaves_the_qr_code_active(self, api):
        """X9-LIFE-005 — a refused notification leaves the QR Code ACTIVE.

        Source: Ours. ADR-0002.

        Why: The half-reservation case. If a refused notification left the code reserved, the payer could
    not retry and nobody else could pay it — a bill bricked by a typo in an amount.
        """
        qr = api.create_qr()
        assert_refused(api.notify(qr, amount=BILL + 1), naming="payment.amount")
        assert api.status_of(qr) == "ACTIVE", "a refused payment must not reserve the QR Code"
