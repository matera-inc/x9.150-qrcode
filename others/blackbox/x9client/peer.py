# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A test payee: receives this service's outbound notifications, answers them right and wrong.

When the service under test notifies a payee on a payer's behalf (``POST
/api/v1/payment-notification/pre-payment`` and ``post-payment``, ADR-0015), it checks the payee's
answer and reports ``signature`` VERIFIED / ABSENT / INVALID with a fixed ``signatureProblem``
code (INTERPRETATION I-12). To test that check, the payee has to be able to answer in every way an
answer can be wrong. This one does: it runs in the suite's process, on its own test-CA identity,
captures the exact JWS it received, and answers with whatever the test told it to.

Started with ``tls``, the same server is another bank's payload host: the service fetches a
payload location over https from whatever host the scanned EMV names, so a peer that answers
``/pub/api/v1/loc/{id}`` with a signed payload is how the decoder is tested against a payload this
service did not issue. The service must trust the issuing CA for TLS (the black-box deployment
adds the test CA through ``SSL_CERT_DIR``).

The service must be able to reach it: ``X9_SUITE_HOST`` (default ``127.0.0.1``) is this process's
address as the SERVICE sees it, as for the CRL server in ``x9client.jws``.
"""
import http.server
import json
import os
import socketserver
import threading
import time

from x9client import jws

PATH = "/pub/api/v1/payment-notification"


class Received:
    """One notification as the payee received it: the exact body, and its header and claims."""

    def __init__(self, raw, headers):
        self.raw = raw
        self.headers = headers
        try:
            self.header, self.claims = jws.parse(raw)
        except ValueError:
            self.header, self.claims = {}, None

    @property
    def correlation_id(self):
        return self.header.get("correlationId")


class Answer:
    """What the payee sends back: an HTTP status, a body, a content type, extra headers (a
    ``Location`` for a redirect), an optional delay, or nothing at all (``hang_up``: the
    connection is closed without a response)."""

    def __init__(self, status, body=b"", content_type="application/jose", delay=0.0,
                 hang_up=False, headers=None):
        self.status = status
        self.headers = dict(headers or {})
        self.body = body.encode() if isinstance(body, str) else (body or b"")
        self.content_type = content_type
        self.delay = delay
        self.hang_up = hang_up


class TestPayee:
    """An HTTP payee on its own identity. ``answer`` is a function of the :class:`Received`."""

    __test__ = False                                   # not a pytest test class

    def __init__(self, identity, now_ms, host=None, port=None, tls=None):
        self.identity = identity
        self.signer = jws.Signer(identity, now_ms=now_ms)
        self.advertised_host = host or os.environ.get("X9_SUITE_HOST", "127.0.0.1")
        self.received = []
        self.answers_given = []
        self.answer = lambda question: self.yes(question)
        payee = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):                           # noqa: N802 - http.server API
                self.do_POST()                          # a followed 302 may arrive as a GET

            def do_POST(self):                          # noqa: N802 - http.server API
                length = int(self.headers.get("Content-Length") or 0)
                question = Received(self.rfile.read(length).decode(errors="replace"),
                                    dict(self.headers))
                payee.received.append(question)
                answer = payee.answer(question)
                payee.answers_given.append(answer)
                if answer.delay:
                    time.sleep(answer.delay)
                if answer.hang_up:
                    self.close_connection = True
                    return
                try:
                    self.send_response(answer.status)
                    if answer.content_type:
                        self.send_header("Content-Type", answer.content_type)
                    for name, value in answer.headers.items():
                        self.send_header(name, value)
                    self.send_header("Content-Length", str(len(answer.body)))
                    self.end_headers()
                    self.wfile.write(answer.body)
                except OSError:
                    pass                                 # the caller gave up (the timeout case)

            def log_message(self, *args):
                pass

        bind_port = int(port if port is not None else os.environ.get("X9_SUITE_PEER_PORT", "0"))
        self.httpd = socketserver.ThreadingTCPServer(("0.0.0.0", bind_port), Handler)
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]
        if tls is not None:
            self.httpd.socket = _tls_context(tls).wrap_socket(self.httpd.socket, server_side=True)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    @property
    def endpoint(self):
        return f"http://{self.advertised_host}:{self.port}{PATH}"

    def location(self, location_id):
        """A payload location on this peer, as an EMV string carries it: host and path, no scheme
        (the service fetches it over https, so the peer must have been started with ``tls``)."""
        return f"{self.advertised_host}:{self.port}/pub/api/v1/loc/{location_id}"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def reset(self, answer=None):
        self.received.clear()
        self.answers_given.clear()
        self.answer = answer or (lambda question: self.yes(question))

    # ------------------------------------------------------------------ answers

    def signed(self, question, status_code, *, http_status=None, signer=None, **header_args):
        """A signed answer, ``{"statusCode": n}`` in the body and the header, as ANSI X9.150 A.4.5
        and this service's own answers have it. ``header_args`` go to :meth:`jws.Signer.sign`."""
        header_args.setdefault("correlation_id", question.correlation_id)
        header_args.setdefault("crit", jws.CRIT + ["statusCode"])
        extra = dict(header_args.pop("extra", {}) or {})
        extra.setdefault("statusCode", status_code)
        token = (signer or self.signer).sign({"statusCode": status_code}, extra=extra,
                                             **header_args)
        return Answer(http_status if http_status is not None else status_code, token)

    def yes(self, question):
        return self.signed(question, 200)


def _tls_context(identity):
    """A server TLS context presenting ``identity`` (a :class:`jws.Identity` with its chain)."""
    import ssl
    import tempfile
    from cryptography.hazmat.primitives import serialization

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    with tempfile.TemporaryDirectory() as directory:
        chain = os.path.join(directory, "chain.pem")
        key = os.path.join(directory, "key.pem")
        with open(chain, "wb") as f:
            for certificate in identity.chain:
                f.write(certificate.public_bytes(serialization.Encoding.PEM))
        with open(key, "wb") as f:
            f.write(identity.key.private_bytes(serialization.Encoding.PEM,
                                               serialization.PrivateFormat.PKCS8,
                                               serialization.NoEncryption()))
        context.load_cert_chain(chain, key)
    return context


def outbound(phase, *, endpoint, qr_id, correlation_id=None, transaction_id=None,
             payer_info="outbound@example.com"):
    """The body of ``POST /api/v1/payment-notification/{phase}``: this service as the payer."""
    from x9client.samples import BILL, PAYER_WALLET, RECIPIENT

    payment = {"qrcodeId": qr_id, "amount": BILL, "currency": "USDC", "network": "solana"}
    if transaction_id:
        payment["transactionId"] = transaction_id
    body = {"endpoint": endpoint,
            "notification": {"payment": payment, "payer": {"info": payer_info},
                             "blockchain": {"action": "SENT" if transaction_id else
                                            "PAYMENT_INITIATED",
                                            "from": PAYER_WALLET, "to": RECIPIENT}}}
    if correlation_id:
        body["correlationId"] = str(correlation_id)
    return body


def plain_json(status, body):
    return Answer(status, json.dumps(body), content_type="application/json")
