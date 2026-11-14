# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Sample values and request bodies that a scenario starts from.

Every value here is public and fictitious. The Solana addresses are well-formed base58 that
belong to nobody this suite knows; the bank numbers are the standard's own example shapes.
"""
import copy

RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
PAYER_WALLET = "5xoBq7f7CDgZwqHrDBdRWM84ExRetg4gZq93dyJtoSwp"
TX_HASH = "3n8kLq2Vb7yTfXpR9sWzM4cJhG6dEuA1"
FEDNOW = {"routingNumber": "021000021", "accountNumber": "1234567890",
          "protectionType": "plaintext"}
BILL = 1000
FAR_FUTURE = "2030-12-31T23:59:59Z"
CREDITOR = {"name": "Blackbox Co",
            "address": {"line1": "1 A St", "city": "Los Angeles", "country": "US"},
            "MCC": "4900"}


def payment_method(*, currency="USDC", valid_until=FAR_FUTURE, amount=BILL, networks=None,
                   editable=None):
    method = {"currency": currency, "validUntil": valid_until, "amount": amount,
              "networks": copy.deepcopy(networks) or {"solana": {"recipient": RECIPIENT}}}
    if editable:
        method["editable"] = editable
    return method


def payment_request(*, tip=None, editable=None, networks=None, currency="USDC",
                    valid_until=FAR_FUTURE, amount=BILL, description="blackbox", **extra):
    """A create body: one payment method, one bill. ``extra`` adds or replaces top-level fields."""
    bill = {"description": description, "amountDue": {"amount": amount, "currency": currency}}
    if tip:
        bill["tip"] = tip

    body = {"validUntil": valid_until,
            "creditor": copy.deepcopy(CREDITOR),
            "bill": bill,
            "paymentNotification": {"kind": "DEFAULT"},
            "paymentMethods": [payment_method(currency=currency, valid_until=valid_until,
                                              amount=amount, networks=networks,
                                              editable=editable)]}
    body.update(extra)
    return body


ADDRESS = {"line1": "1 A St", "line2": "Suite 2", "city": "Los Angeles", "state": "CA",
           "postalCode": "90012", "country": "US"}
PARTY = {"name": "Blackbox Party", "phone": "+15552223333", "email": "party@example.com",
         "address": ADDRESS}


def full_payment_request(*, valid_until=FAR_FUTURE):
    """A create body with EVERY optional object present, so that a constraint anywhere in the
    request schema has a parent to live in. Two methods in one peg group: USD on the three bank
    rails, USDC on Solana. Used by ``contract.py``, whose control case proves it is accepted.
    """
    return copy.deepcopy({
        "validUntil": valid_until,
        "creditor": {**PARTY, "name": "Blackbox Co", "MCC": "4900",
                     "ultimateCreditor": {**PARTY, "account": {"id": "ACC-1",
                                                               "schemaName": "internal"}}},
        "bill": {"description": "blackbox contract", "paymentTiming": "immediate",
                 "order": {"number": "ORD-1", "date": "2030-01-10"},
                 "invoice": {"number": "INV-1", "date": "2030-01-10",
                             "dueDate": "2030-12-31T23:59:59Z", "invoicee": PARTY},
                 "tip": {"allowed": True, "range": {"min": 0, "max": 25}, "presets": [10, 15]},
                 "amountDue": {"amount": BILL, "currency": "USD", "adjustments": {
                     "formula": "FixedDiscountLateFeeLinearInterest",
                     "parameters": {
                         "discounts": [{"daysBefore": 5, "discount": 50,
                                        "explanation": "Early payment"}],
                         "lateFees": {"fixed": 100, "perDay": 10, "explanation": "Late fee"}}}}},
        "unstructured": "blackbox",
        "additionalInformation": [{"key": "Note", "value": "contract"}],
        "paymentNotification": {"kind": "DEFAULT"},
        "paymentMethods": [
            {"currency": "USD", "validUntil": valid_until, "amount": BILL,
             "editable": {"range": {"min": 500, "max": 2000}},
             "networks": {"fednow": FEDNOW, "rtp": FEDNOW, "ach": FEDNOW}},
            {"currency": "USDC", "validUntil": valid_until, "amount": BILL,
             "networks": {"solana": {"recipient": RECIPIENT, "memo": "blackbox"}}},
        ],
    })


def full_patch(created):
    """A PATCH body restating everything a patch may carry, for a QR Code made from
    :func:`full_payment_request`."""
    return {key: copy.deepcopy(created[key]) for key in
            ("bill", "unstructured", "additionalInformation", "paymentMethods")}


def notification(qr_id, *, amount=BILL, tip=None, currency="USDC", network="Solana",
                 action="PAYMENT_INITIATED", transaction_id=None, blockchain=True,
                 payer_info="blackbox@example.com", sender=PAYER_WALLET, recipient=RECIPIENT):
    """A payment notification body. ``payer_info=None`` sends a payer object with no info."""
    payment = {"qrcodeId": qr_id, "amount": amount, "currency": currency, "network": network}
    if tip is not None:
        payment["tipAmount"] = tip
    if transaction_id:
        payment["transactionId"] = transaction_id

    body = {"payment": payment, "payer": {"info": payer_info}}
    if blockchain:
        body["blockchain"] = {"action": action, "from": sender, "to": recipient}
    return body
