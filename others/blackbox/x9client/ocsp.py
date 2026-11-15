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
- sources that exist but cannot be reached: the soft-fail case;
- the responder answers, but with an answer ADR-0027 refuses (an error status other than
  ``tryLater`` or ``internalError``, an unauthorised or unqualified signer, an out-of-date answer,
  no answer for the certificate asked about): see :data:`MODES` and :meth:`OcspResponder.answer`.

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
from cryptography.x509.oid import AuthorityInformationAccessOID, ExtendedKeyUsageOID

from x9client import jws

# A port nothing listens on: a source that exists in the certificate and cannot be reached.
UNREACHABLE = "http://127.0.0.1:9"

# How the responder may answer for one certificate (:meth:`OcspResponder.answer`). ``None`` is the
# normal answer: good, or revoked once :meth:`OcspResponder.revoke` was called.
MODES = (
    None,
    # An unsuccessful response status (RFC 6960 §4.2.1), no signed body at all.
    "malformedRequest", "internalError", "tryLater", "sigRequired", "unauthorized",
    "unauthorised-signer",   # signed by a responder neither the issuer nor delegated by it
    "delegated-no-eku",      # delegated by the issuer, but without the OCSPSigning key usage
    "delegated",             # delegated by the issuer, with OCSPSigning: a valid answer
    "next-update-passed",    # thisUpdate and nextUpdate both an hour or more in the past
    "this-update-future",    # thisUpdate an hour in the future
    "other-certificate",     # a good answer, for a serial that is not the one asked about
)
_STATUSES = {
    "malformedRequest": ocsp.OCSPResponseStatus.MALFORMED_REQUEST,
    "internalError": ocsp.OCSPResponseStatus.INTERNAL_ERROR,
    "tryLater": ocsp.OCSPResponseStatus.TRY_LATER,
    "sigRequired": ocsp.OCSPResponseStatus.SIG_REQUIRED,
    "unauthorized": ocsp.OCSPResponseStatus.UNAUTHORIZED,
}


class OcspResponder:
    """Answers OCSP for the certificates registered with it: ``good`` unless revoked here."""

    def __init__(self, ca, host=None):
        self.ca = ca
        self.advertised_host = host or os.environ.get("X9_SUITE_HOST", "127.0.0.1")
        self.path = f"/ocsp/{uuid.uuid4().hex}"
        self.known = {}                                  # serial -> certificate
        self.revoked = {}                                # serial -> when
        self.modes = {}                                  # serial -> one of MODES
        self._signers = {}                               # mode -> (key, certificate)
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

    def answer(self, identity, mode):
        """From now on, answer for ``identity`` as ``mode`` (one of :data:`MODES`)."""
        if mode not in MODES:
            raise ValueError(f"unknown OCSP mode {mode!r}; one of {MODES}")
        self.modes[identity.serial] = mode

    def _signer(self, mode):
        """The key and certificate that sign an answer in ``mode``: the CA itself, or a responder
        certificate made once for that mode."""
        if mode not in ("unauthorised-signer", "delegated-no-eku", "delegated"):
            return self.ca.key, None
        if mode not in self._signers:
            key = jws.new_key("rsa")
            now = datetime.datetime.now(datetime.timezone.utc)
            if mode == "unauthorised-signer":
                # Issued by itself: in no chain to the test CA, so not authorised by it.
                issuer_name, signing_key = jws._name("Blackbox Rogue OCSP Responder",
                                                     "Example Org"), key
            else:
                issuer_name, signing_key = self.ca.certificate.subject, self.ca.key
            builder = (x509.CertificateBuilder()
                       .subject_name(jws._name(f"Blackbox OCSP Responder {mode}", "Example Org"))
                       .issuer_name(issuer_name)
                       .public_key(key.public_key()).serial_number(x509.random_serial_number())
                       .not_valid_before(now - datetime.timedelta(days=1))
                       .not_valid_after(now + datetime.timedelta(days=3650))
                       .add_extension(x509.KeyUsage(True, False, False, False, False, False,
                                                    False, False, False), critical=True)
                       .add_extension(x509.OCSPNoCheck(), critical=False))
            if mode != "delegated-no-eku":
                builder = builder.add_extension(
                    x509.ExtendedKeyUsage([ExtendedKeyUsageOID.OCSP_SIGNING]), critical=False)
            self._signers[mode] = (key, builder.sign(signing_key, hashes.SHA256()))
        return self._signers[mode]

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

        mode = self.modes.get(request.serial_number)
        if mode in _STATUSES:
            return ocsp.OCSPResponseBuilder.build_unsuccessful(
                _STATUSES[mode]).public_bytes(serialization.Encoding.DER)

        # The JDK checks an answer's validity interval against its own wall clock, not against
        # the service's business clock, so the interval is the wall clock's too.
        now = datetime.datetime.now(datetime.timezone.utc)
        this_update = now - datetime.timedelta(minutes=5)
        next_update = now + datetime.timedelta(hours=1)
        hour = datetime.timedelta(hours=1)
        if mode == "next-update-passed":
            this_update, next_update = now - 2 * hour, now - hour
        elif mode == "this-update-future":
            this_update, next_update = now + hour, now + 2 * hour
        if mode == "other-certificate":
            # The CA's own certificate has the same issuer hashes and another serial: a
            # well-formed answer about a certificate nobody asked about.
            certificate = self.ca.certificate
        revoked_at = self.revoked.get(request.serial_number)
        key, responder_certificate = self._signer(mode)
        builder = ocsp.OCSPResponseBuilder().add_response(
            cert=certificate, issuer=self.ca.certificate, algorithm=request.hash_algorithm,
            cert_status=ocsp.OCSPCertStatus.REVOKED if revoked_at else ocsp.OCSPCertStatus.GOOD,
            this_update=this_update, next_update=next_update,
            revocation_time=revoked_at, revocation_reason=None,
        ).responder_id(ocsp.OCSPResponderEncoding.HASH,
                       responder_certificate or self.ca.certificate)
        if responder_certificate is not None:
            builder = builder.certificates([responder_certificate])
        for extension in request.extensions:
            if isinstance(extension.value, x509.OCSPNonce):
                builder = builder.add_extension(extension.value, critical=False)
        return builder.sign(key, hashes.SHA256()).public_bytes(serialization.Encoding.DER)


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
