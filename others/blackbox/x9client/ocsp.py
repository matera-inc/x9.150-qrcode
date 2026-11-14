# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""An OCSP responder for the test CA, and payers whose certificates point at it (Story 4.3).

The service checks a CA-issued certificate's revocation with ``PREFER_CRLS``: the CRL from the
certificate's distribution point first, then, when no CRL is obtainable, **OCSP** from its
Authority Information Access (ADR-0027). ``x9client.jws`` serves CRLs; this serves the other
branch, so "revoked by OCSP" is a real revocation the service has to discover, not a flag.

A payer issued here can carry a distribution point, an OCSP responder, both or neither, each
reachable or not, so every branch of the revocation check can be taken on purpose:

- no distribution point, the responder says **good**: accepted, on OCSP alone;
- no distribution point, the responder says **revoked**: refused;
- sources that exist but cannot be reached: the soft-fail case.

The responder signs with the test CA's own key, as an issuing CA's responder may (RFC 6960 §4.2.2.2),
and answers both the GET (short requests, base64 in the path) and POST forms the JDK uses. The
service must reach it, so it is advertised at ``X9_SUITE_HOST`` like the CRL server and the test
payee.
"""
import base64
import datetime
import http.server
import os
import socketserver
import threading
import urllib.parse
import uuid

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.x509 import ocsp
from cryptography.x509.oid import AuthorityInformationAccessOID

from x9client import jws

# A port nothing listens on: a source that exists in the certificate and cannot be reached.
UNREACHABLE = "http://127.0.0.1:9"


class OcspResponder:
    """Answers OCSP for the certificates registered with it: ``good`` unless revoked here."""

    def __init__(self, ca, host=None):
        self.ca = ca
        self.advertised_host = host or os.environ.get("X9_SUITE_HOST", "127.0.0.1")
        self.path = f"/ocsp/{uuid.uuid4().hex}"
        self.known = {}                                  # serial -> certificate
        self.revoked = {}                                # serial -> when
        self.queries = 0
        responder = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):                          # noqa: N802 - http.server API
                length = int(self.headers.get("Content-Length") or 0)
                self._answer(self.rfile.read(length))

            def do_GET(self):                           # noqa: N802 - http.server API
                if not self.path.startswith(responder.path + "/"):
                    self.send_error(404)
                    return
                encoded = urllib.parse.unquote(self.path[len(responder.path) + 1:])
                self._answer(base64.b64decode(encoded + "=" * (-len(encoded) % 4)))

            def _answer(self, der):
                body = responder.respond(der)
                self.send_response(200)
                self.send_header("Content-Type", "application/ocsp-response")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.httpd = socketserver.ThreadingTCPServer(("0.0.0.0", 0), Handler)
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    @property
    def url(self):
        return f"http://{self.advertised_host}:{self.port}{self.path}"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def revoke(self, identity):
        self.revoked[identity.serial] = datetime.datetime.now(datetime.timezone.utc)

    def respond(self, der):
        """The DER OCSP response to a DER OCSP request."""
        self.queries += 1
        try:
            request = ocsp.load_der_ocsp_request(der)
        except ValueError:
            return ocsp.OCSPResponseBuilder.build_unsuccessful(
                ocsp.OCSPResponseStatus.MALFORMED_REQUEST).public_bytes(serialization.Encoding.DER)
        certificate = self.known.get(request.serial_number)
        if certificate is None:
            return ocsp.OCSPResponseBuilder.build_unsuccessful(
                ocsp.OCSPResponseStatus.UNAUTHORIZED).public_bytes(serialization.Encoding.DER)

        now = datetime.datetime.now(datetime.timezone.utc)
        revoked_at = self.revoked.get(request.serial_number)
        builder = ocsp.OCSPResponseBuilder().add_response(
            cert=certificate, issuer=self.ca.certificate, algorithm=request.hash_algorithm,
            cert_status=ocsp.OCSPCertStatus.REVOKED if revoked_at else ocsp.OCSPCertStatus.GOOD,
            this_update=now - datetime.timedelta(minutes=5),
            next_update=now + datetime.timedelta(hours=1),
            revocation_time=revoked_at, revocation_reason=None,
        ).responder_id(ocsp.OCSPResponderEncoding.HASH, self.ca.certificate)
        for extension in request.extensions:
            if isinstance(extension.value, x509.OCSPNonce):
                builder = builder.add_extension(extension.value, critical=False)
        return builder.sign(self.ca.key, hashes.SHA256()).public_bytes(serialization.Encoding.DER)


def issue(ca, name, cn, *, responder=None, ocsp_url=None, crl_url=None, kind="rsa",
          org="Example PSP"):
    """A leaf issued by ``ca`` with any combination of revocation sources.

    ``responder`` registers the leaf with an :class:`OcspResponder` and names it in the AIA;
    ``ocsp_url`` names some other (say, unreachable) responder instead; ``crl_url`` adds a
    distribution point. With none of them the leaf has no revocation source at all.
    """
    key = jws.new_key(kind)
    now = datetime.datetime.now(datetime.timezone.utc)
    builder = (x509.CertificateBuilder()
               .subject_name(jws._name(cn, org)).issuer_name(ca.certificate.subject)
               .public_key(key.public_key()).serial_number(x509.random_serial_number())
               .not_valid_before(now - datetime.timedelta(days=1))
               .not_valid_after(now + datetime.timedelta(days=3650))
               .add_extension(x509.KeyUsage(True, True, False, False, False, False, False,
                                            False, False), critical=True))
    if crl_url:
        builder = builder.add_extension(x509.CRLDistributionPoints([x509.DistributionPoint(
            full_name=[x509.UniformResourceIdentifier(crl_url)], relative_name=None,
            reasons=None, crl_issuer=None)]), critical=False)
    url = responder.url if responder is not None else ocsp_url
    if url:
        builder = builder.add_extension(x509.AuthorityInformationAccess([x509.AccessDescription(
            AuthorityInformationAccessOID.OCSP, x509.UniformResourceIdentifier(url))]),
            critical=False)
    certificate = builder.sign(ca.key, hashes.SHA512())
    if responder is not None:
        responder.known[certificate.serial_number] = certificate
    return jws.Identity(name, key, certificate, [certificate, ca.certificate])
