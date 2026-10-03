"""
Fixtures for the black-box suite.

Deliberately zero dependencies beyond pytest: the suite has to run against a container on a
laptop, in CI, and against a deployed instance, and a dependency that fails to install is a
suite that silently does not run.

    pytest others/blackbox                      # against http://localhost:8080
    X9_BASE_URL=http://localhost:8070 pytest others/blackbox
"""
import base64
import json
import os
import urllib.error
import urllib.request
import uuid

import pytest

RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
PAYER_WALLET = "5xoBq7f7CDgZwqHrDBdRWM84ExRetg4gZq93dyJtoSwp"
TX_HASH = "3n8kLq2Vb7yTfXpR9sWzM4cJhG6dEuA1"
FEDNOW = {"routingNumber": "021000021", "accountNumber": "1234567890",
          "protectionType": "plaintext"}
BILL = 1000
FAR_FUTURE = "2030-12-31T23:59:59Z"


class Api:
    """The smallest HTTP client that tells the truth about what came back."""

    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")

    def call(self, method, path, body=None, raw=None, headers=None):
        hdrs = {"Content-Type": "application/json"}
        hdrs.update(headers or {})

        if raw is not None:
            data = raw.encode()
        elif body is not None:
            data = json.dumps(body).encode()
        else:
            data = None

        request = urllib.request.Request(self.base_url + path, data=data,
                                         headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(request, timeout=40) as response:
                text, status = response.read().decode(), response.status
        except urllib.error.HTTPError as e:
            text, status = e.read().decode(errors="replace"), e.code
        except Exception as e:
            # Connection refused, DNS, timeout. Status 0 so the health probe can SKIP the suite
            # rather than every test erroring individually — 34 stack traces saying "connection
            # refused" tell you less than one line saying nothing is listening.
            return 0, str(e)

        try:
            return status, json.loads(text)
        except ValueError:
            return status, text

    def sign(self, payload):
        """Sign JSON with the deployment's own key. Both headers are required."""
        status, body = self.call("POST", "/api/v1/signature/generate", body=payload, headers={
            "Correlation-Id": str(uuid.uuid4()), "TTL-Seconds": "300"})
        assert status == 200, f"the deployment would not sign: {status} {body}"
        return body

    # ------------------------------------------------------------------ fixtures of convenience

    def create_qr(self, *, tip=None, editable=None, networks=None, currency="USDC",
                  valid_until=FAR_FUTURE, amount=BILL):
        status, created = self.create_qr_detailed(
            tip=tip, editable=editable, networks=networks, currency=currency,
            valid_until=valid_until, amount=amount)
        assert status == 201, f"fixture QR Code could not be created: {status} {created}"
        return created["id"]

    def create_qr_detailed(self, *, tip=None, editable=None, networks=None, currency="USDC",
                           valid_until=FAR_FUTURE, amount=BILL):
        """As create_qr, but returns the whole created object — callers that need the EMV content
        or the location, rather than only the id."""
        method = {"currency": currency, "validUntil": valid_until, "amount": amount,
                  "networks": networks or {"solana": {"recipient": RECIPIENT}}}
        if editable:
            method["editable"] = editable

        bill = {"description": "blackbox", "amountDue": {"amount": amount, "currency": currency}}
        if tip:
            bill["tip"] = tip

        return self.call("POST", "/api/v1/payment-request", body={
            "validUntil": valid_until,
            "creditor": {"name": "Blackbox Co",
                         "address": {"line1": "1 A St", "city": "Los Angeles", "country": "US"},
                         "MCC": "4900"},
            "bill": bill,
            "paymentNotification": {"kind": "DEFAULT"},
            "paymentMethods": [method]})
        assert status == 201, f"fixture QR Code could not be created: {status} {created}"
        return created["id"]

    def notify(self, qr_id, *, amount=BILL, tip=None, currency="USDC", network="Solana",
               action="PAYMENT_INITIATED", transaction_id=None, blockchain=True, raw=None,
               payer_info="blackbox@example.com"):
        payment = {"qrcodeId": qr_id, "amount": amount, "currency": currency, "network": network}
        if tip is not None:
            payment["tipAmount"] = tip
        if transaction_id:
            payment["transactionId"] = transaction_id

        notification = {"payment": payment, "payer": {"info": payer_info}}
        if blockchain:
            notification["blockchain"] = {"action": action,
                                          "from": PAYER_WALLET, "to": RECIPIENT}

        token = raw if raw is not None else self.sign(notification)
        return self.call("POST", "/pub/api/v1/payment-notification", raw=token,
                         headers={"Content-Type": "application/jose"})

    def mark_paid(self, qr_id, *, end_to_end_id="E2E-BLACKBOX-1", network="solana"):
        """The payee's order: this bill is settled."""
        return self.call("PUT", f"/api/v1/payment-request/{qr_id}/status-update",
                         body={"status": "PAID", "endToEndId": end_to_end_id, "network": network})

    def set_status(self, qr_id, status):
        return self.call("PUT", f"/api/v1/payment-request/{qr_id}/status-update",
                         body={"status": status})

    def status_of(self, qr_id):
        _, current = self.call("GET", f"/api/v1/payment-request/{qr_id}")
        return current.get("status")


@pytest.fixture(scope="session")
def api():
    base_url = os.environ.get("X9_BASE_URL", "http://localhost:8080")
    client = Api(base_url)

    status, _ = client.call("GET", "/actuator/health")
    if status != 200:
        pytest.skip(f"no deployment answering at {base_url} (health returned {status})")

    return client


def refusal(response):
    """The violations of a problem+json body, as one searchable string."""
    status, body = response
    if isinstance(body, dict):
        return json.dumps(body.get("violations") or body)
    return str(body)


def assert_refused(response, *, naming):
    """
    A refusal has to be refused FOR THE RIGHT REASON.

    Asserting only on the status code is how a check passes because the fixture was never
    built, or because an unrelated rule fired first. Every refusal here names the field it
    is about, so the test can insist on it.
    """
    status, body = response
    assert status >= 400, f"expected a refusal, got {status}: {body}"
    text = refusal(response)
    assert naming in text, f"refused, but not for the expected reason.\n  wanted: {naming}\n  got:    {text}"
