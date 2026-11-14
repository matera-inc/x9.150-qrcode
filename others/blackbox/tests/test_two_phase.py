# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A payer notifies the payee in two phases, and the event stream reports it
(acceptance.py sections 3 and 4, folded in by Story 3.4).

X9.150 never sees the funds, so a post-commit leaves the QR Code reserved, never PAID.
"""
import pytest

from x9client.samples import TX_HASH

PAYER = "acceptance@example.com"


@pytest.fixture(scope="module")
def paid_for(api, payer_a, payer_b, events):
    """One QR Code through pre-commit, a second payer's attempt, and post-commit."""
    qr = api.create_qr()
    steps = {
        "pre": api.notify(qr, signer=payer_a, payer_info=PAYER),
        "after_pre": api.status_of(qr),
        "second": api.notify(qr, signer=payer_b, payer_info="someone-else@example.com"),
        "post": api.notify(qr, signer=payer_a, payer_info=PAYER, action="SENT",
                           transaction_id=TX_HASH),
        "after_post": api.status_of(qr),
    }
    steps["events"] = events.for_qr(qr, until=lambda es: {"payment.initiated", "payment.sent"}
                                    <= {e.get("type") for e in es})
    steps["qr"] = qr
    return steps


class TestTwoPhases:

    def test_a_pre_commit_reserves(self, paid_for):
        """S3.4-AC1, section 3 — accepted, and the QR Code is PAYMENT_INITIATED."""
        assert paid_for["pre"].status == 200, paid_for["pre"]
        assert paid_for["after_pre"] == "PAYMENT_INITIATED"

    def test_a_second_payer_is_refused_while_it_is_reserved(self, paid_for):
        """S3.4-AC1, section 3 — another payer, on its own key: 409, the reservation holds."""
        assert paid_for["second"].status == 409, paid_for["second"]

    def test_a_post_commit_is_accepted_and_does_not_mark_paid(self, paid_for):
        """S3.4-AC1, section 3 — the hash is accepted; the QR Code is STILL not PAID."""
        assert paid_for["post"].status == 200, paid_for["post"]
        assert paid_for["after_post"] == "PAYMENT_INITIATED", \
            "X9.150 never sees the funds: only the payment system marks PAID"


class TestTheEventStream:

    def test_both_events_are_published_in_order(self, paid_for):
        """S3.4-AC1, section 4 — payment.initiated then payment.sent; nothing for the refusal."""
        types = [e["type"] for e in paid_for["events"]]
        assert types == ["payment.initiated", "payment.sent"], types

    def test_every_event_has_its_own_event_id(self, paid_for):
        """S3.4-AC1, section 4 — distinct eventIds, the consumer's dedup key."""
        ids = [e["eventId"] for e in paid_for["events"]]
        assert len(set(ids)) == len(ids) == 2, ids

    def test_payment_sent_carries_the_hash(self, paid_for):
        """S3.4-AC1, section 4 — the transaction hash the payer reported."""
        sent = [e for e in paid_for["events"] if e["type"] == "payment.sent"]
        assert sent and sent[0].get("transactionId") == TX_HASH, sent

    def test_the_cursor_advances_and_does_not_repeat(self, api, events, paid_for):
        """S3.4-AC1, section 4 — read to the end, then from there: none of ours again."""
        _, cursor = events.read_all()
        assert cursor, "the cursor must advance"
        again, _ = events.page(after=cursor, wait=0)
        assert [e for e in again if e.get("qrCodeId") == paid_for["qr"]] == []
