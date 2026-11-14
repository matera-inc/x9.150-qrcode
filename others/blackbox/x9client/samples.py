# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Sample values and request bodies that a scenario starts from.

Every value here is public and fictitious. The Solana addresses are well-formed base58 that
belong to nobody this suite knows; the bank numbers are the standard's own example shapes.
"""

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
              "networks": networks or {"solana": {"recipient": RECIPIENT}}}
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
            "creditor": CREDITOR,
            "bill": bill,
            "paymentNotification": {"kind": "DEFAULT"},
            "paymentMethods": [payment_method(currency=currency, valid_until=valid_until,
                                              amount=amount, networks=networks,
                                              editable=editable)]}
    body.update(extra)
    return body


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
