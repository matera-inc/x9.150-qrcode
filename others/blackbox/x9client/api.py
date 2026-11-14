# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""HTTP on the standard library, with ETag / If-Match and problem+json.

Nothing here knows a rule of X9.150; it knows how to ask and how to read the answer truthfully.
A call never raises on an HTTP error status: a refusal is an answer, and the tests are about
answers. A transport failure (connection refused, DNS, timeout) comes back as status 0, so a
health probe can skip a whole suite with one line instead of one stack trace per test.
"""
import json
import urllib.error
import urllib.request

APPLICATION_JSON = "application/json"
APPLICATION_JOSE = "application/jose"
PROBLEM_JSON = "application/problem+json"


class Response:
    """What came back: status, parsed body, headers.

    Unpacks as ``status, body`` so a test can write ``status, body = api.call(...)``; the headers,
    the ETag and the problem detail are attributes for the tests that need them.
    """

    __slots__ = ("status", "body", "headers", "text")

    def __init__(self, status, body, headers=None, text=""):
        self.status = status
        self.body = body
        self.headers = {k.lower(): v for k, v in (headers or {}).items()}
        self.text = text

    def __iter__(self):
        return iter((self.status, self.body))

    def __getitem__(self, index):
        return (self.status, self.body)[index]

    def __len__(self):
        return 2

    def __repr__(self):
        return f"Response({self.status}, {str(self.body)[:200]!r})"

    def header(self, name, default=None):
        return self.headers.get(name.lower(), default)

    @property
    def content_type(self):
        return (self.header("Content-Type") or "").split(";")[0].strip().lower()

    @property
    def etag(self):
        return self.header("ETag")

    @property
    def is_problem(self):
        return self.content_type == PROBLEM_JSON or (
            isinstance(self.body, dict) and "status" in self.body and "title" in self.body)

    @property
    def problem(self):
        """The problem+json body (RFC 9457) as a dict, or None when the answer is not one."""
        return self.body if self.is_problem and isinstance(self.body, dict) else None

    @property
    def violations(self):
        """The field violations of a problem body: a list of dicts, possibly empty."""
        problem = self.problem or (self.body if isinstance(self.body, dict) else {})
        return list(problem.get("violations") or [])


class Api:
    """The smallest HTTP client that tells the truth about what came back."""

    def __init__(self, base_url, timeout=40):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def call(self, method, path, body=None, raw=None, headers=None, if_match=None):
        """One request. ``body`` is sent as JSON, ``raw`` as given (a JWS, or anything broken)."""
        hdrs = {"Content-Type": APPLICATION_JSON}
        hdrs.update(headers or {})
        if if_match is not None:
            hdrs["If-Match"] = if_match

        if raw is not None:
            data = raw.encode() if isinstance(raw, str) else raw
        elif body is not None:
            data = json.dumps(body).encode()
        else:
            data = None

        url = path if path.startswith("http") else self.base_url + path
        request = urllib.request.Request(url, data=data, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                text = response.read().decode(errors="replace")
                status, response_headers = response.status, dict(response.headers)
        except urllib.error.HTTPError as e:
            text, status, response_headers = e.read().decode(errors="replace"), e.code, dict(e.headers)
        except Exception as e:                                    # noqa: BLE001 - transport failure
            return Response(0, str(e), {}, str(e))

        try:
            parsed = json.loads(text)
        except ValueError:
            parsed = text
        return Response(status, parsed, response_headers, text)

    def get(self, path, **kwargs):
        return self.call("GET", path, **kwargs)

    def post(self, path, body=None, **kwargs):
        return self.call("POST", path, body=body, **kwargs)

    def put(self, path, body=None, **kwargs):
        return self.call("PUT", path, body=body, **kwargs)

    def patch(self, path, body=None, **kwargs):
        return self.call("PATCH", path, body=body, **kwargs)

    def post_jose(self, path, token):
        """POST a compact JWS (or anything pretending to be one) as ``application/jose``."""
        return self.call("POST", path, raw=token if token is not None else "",
                         headers={"Content-Type": APPLICATION_JOSE})

    def etag_of(self, path):
        """The ETag a GET serves for ``path`` (None when it serves none)."""
        return self.get(path).etag

    def healthy(self):
        return self.get("/actuator/health").status == 200


def jws_body(answer):
    """The JSON body of a compact JWS, or the answer unchanged when it is not one.

    Reads only; it verifies nothing. Every answer to a payment notification is signed
    (Story 2.3): ``{"statusCode": n}`` on a yes, plus the error fields on a refusal.
    """
    import base64

    if not isinstance(answer, str) or answer.count(".") != 2:
        return answer
    payload = answer.split(".")[1]
    try:
        return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    except ValueError:
        return answer


class X9Api(Api):
    """The service's API in the words of a scenario: create a QR Code, notify, settle, read.

    Every helper returns the :class:`Response`, except ``create_qr``, which returns the id and
    insists on a 201: a test whose fixture was never built must fail there, not later for a reason
    that looks like the rule under test.
    """

    def __init__(self, base_url, timeout=40, signer=None):
        super().__init__(base_url, timeout)
        self.signer = signer

    # ------------------------------------------------------------------ payment requests

    def create_qr(self, **kwargs):
        response = self.create_qr_detailed(**kwargs)
        assert response.status == 201, \
            f"fixture QR Code could not be created: {response.status} {response.body}"
        return response.body["id"]

    def create_qr_detailed(self, **kwargs):
        """As create_qr, but the whole Response: for the EMV content, the location, the ETag."""
        from x9client import samples
        return self.post("/api/v1/payment-request", body=samples.payment_request(**kwargs))

    def qr(self, qr_id):
        return self.get(f"/api/v1/payment-request/{qr_id}")

    def status_of(self, qr_id):
        response = self.qr(qr_id)
        return response.body.get("status") if isinstance(response.body, dict) else None

    def status_update(self, qr_id, body, if_match=None):
        return self.put(f"/api/v1/payment-request/{qr_id}/status-update", body=body,
                        if_match=if_match)

    def mark_paid(self, qr_id, *, end_to_end_id="E2E-BLACKBOX-1", network="solana"):
        """The payee's order: this bill is settled."""
        return self.status_update(qr_id, {"status": "PAID", "endToEndId": end_to_end_id,
                                          "network": network})

    def set_status(self, qr_id, status):
        return self.status_update(qr_id, {"status": status})

    # ------------------------------------------------------------------ the payer's side

    def sign(self, payload, **kwargs):
        """Sign a payer message. Who signs is the signer's business, never this client's."""
        assert self.signer is not None, "no payer signer configured"
        return self.signer.sign(payload, **kwargs)

    def notify(self, qr_id, *, raw=None, signer=None, **kwargs):
        """Send a payment notification for ``qr_id``; the answer's body is read out of its JWS."""
        from x9client import samples

        token = raw if raw is not None else (signer or self).sign(
            samples.notification(qr_id, **kwargs))
        return self.send_notification(token)

    def send_notification(self, token):
        """POST a (possibly broken) token to the notification endpoint; read the answer's body."""
        response = self.post_jose("/pub/api/v1/payment-notification", token)
        return Response(response.status, jws_body(response.body), response.headers, response.text)

    def fetch_payload(self, location_id, emv, *, signer=None):
        """The payer's fetch of the signed payload at ``/pub/api/v1/loc/{id}``."""
        import base64
        token = (signer or self).sign(
            {"qrCodeContent": base64.b64encode(emv.encode()).decode()})
        return self.post_jose(f"/pub/api/v1/loc/{location_id}", token)


class ServiceSigner:
    """Signs with the deployment's OWN key, through ``POST /api/v1/signature/generate``.

    NON-PRODUCTION and temporary: it lets one deployment stand in for both sides of a payment, and
    it cannot produce any signature the service would refuse. Story 3.2 replaces it with
    independent test-CA payers.
    """

    def __init__(self, api):
        self.api = api

    def sign(self, payload):
        import uuid
        status, body = self.api.call("POST", "/api/v1/signature/generate", body=payload, headers={
            "Correlation-Id": str(uuid.uuid4()), "TTL-Seconds": "300"})
        assert status == 200, f"the deployment would not sign: {status} {body}"
        return body
