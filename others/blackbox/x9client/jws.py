# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Independent payers: X9.150 JWS signing on the test CA, a tamperer, and a verifier.

The suite never asks the service under test to sign for it. Payers are issued at run time by the
repository's bundled test CA (``x9-test-ca.p12``, NON-PRODUCTION, its key is public), each with
its own key and subject, exactly as two PSPs would be. Their certificates name a CRL distribution
point served by the suite itself, so a revoked certificate is a real revocation the service has
to discover, not a flag.

The profile (ANSI X9.150 §8, as this service reads it): compact JWS, ``typ: JOSE``, the chain in
``x5c`` with ``x5t#S256`` naming the signing certificate, and ``correlationId``, ``iat`` and
``ttl`` (milliseconds) as protected headers listed in ``crit``.

Needs ``cryptography`` (pinned in requirements.txt). The standard library cannot produce an RSA
or EC signature, and the refusal cases cannot be tested without one (ADR-0034).
"""
import base64
import datetime
import hashlib
import http.server
import json
import os
import socketserver
import threading
import time
import uuid

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.hazmat.primitives.asymmetric.utils import (decode_dss_signature,
                                                             encode_dss_signature)
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

CRIT = ["correlationId", "iat", "ttl"]
DEFAULT_TTL_MS = 300_000
STOREPASS = b"x9test123"

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CERT_DIR = os.path.normpath(os.path.join(
    HERE, "..", "..", "..", "x9-qrcode-infrastructure", "src", "test", "resources", "certificate"))


# ------------------------------------------------------------------------------- encoding

def b64u(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def b64u_decode(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def b64u_json(obj):
    return b64u(json.dumps(obj, separators=(",", ":")).encode())


def parse(token):
    """``(header, payload)`` of a compact JWS, unverified. Raises ValueError when it is not one."""
    if not isinstance(token, str) or token.count(".") != 2:
        raise ValueError(f"not a compact JWS: {str(token)[:60]!r}")
    header, payload, _ = token.split(".")
    return json.loads(b64u_decode(header)), json.loads(b64u_decode(payload))


def der(certificate):
    return certificate.public_bytes(serialization.Encoding.DER)


def thumbprint(certificate):
    """``x5t#S256``: base64url of the SHA-256 of the certificate's DER."""
    return b64u(hashlib.sha256(der(certificate)).digest())


# ------------------------------------------------------------------------------- keys and algs

def alg_for(key):
    return "ES256" if isinstance(key, ec.EllipticCurvePrivateKey) else "PS512"


def raw_sign(key, alg, data):
    if alg.startswith("ES"):
        r, s = decode_dss_signature(key.sign(data, ec.ECDSA(hashes.SHA256())))
        return r.to_bytes(32, "big") + s.to_bytes(32, "big")
    if alg.startswith("PS"):
        return key.sign(data, padding.PSS(mgf=padding.MGF1(hashes.SHA512()), salt_length=64),
                        hashes.SHA512())
    if alg.startswith("RS"):
        return key.sign(data, padding.PKCS1v15(), hashes.SHA256())
    raise ValueError(f"no signer for alg {alg}")


_HASHES = {"256": hashes.SHA256, "384": hashes.SHA384, "512": hashes.SHA512}
_EC_CURVE_BITS = {"256": 256, "384": 384, "512": 521}


def raw_verify(public_key, alg, data, signature):
    """Raises InvalidSignature when the signature does not verify."""
    digest = _HASHES[alg[2:]]()
    if alg.startswith("ES"):
        # RFC 7518 §3.4 binds each ES algorithm to one curve. ECDSA itself does not: SHA-384 over
        # P-256 verifies, and Java refuses it (golden ``ec-alg-ES384-on-a-p256-key``).
        if public_key.curve.key_size != _EC_CURVE_BITS[alg[2:]]:
            raise InvalidSignature(f"{alg} on a {public_key.curve.name} key")
        half = len(signature) // 2
        public_key.verify(encode_dss_signature(int.from_bytes(signature[:half], "big"),
                                               int.from_bytes(signature[half:], "big")),
                          data, ec.ECDSA(digest))
    elif alg.startswith("PS"):
        public_key.verify(signature, data,
                          padding.PSS(mgf=padding.MGF1(digest), salt_length=padding.PSS.AUTO), digest)
    elif alg.startswith("RS"):
        public_key.verify(signature, data, padding.PKCS1v15(), digest)
    else:
        raise InvalidSignature(f"unsupported alg {alg}")


# ------------------------------------------------------------------------------- identities

class Identity:
    """A party: a private key, its certificate, and the chain it publishes in ``x5c``."""

    def __init__(self, name, key, certificate, chain):
        self.name = name
        self.key = key
        self.certificate = certificate
        self.chain = chain                      # leaf first, then its issuers

    @property
    def subject(self):
        return self.certificate.subject.rfc4514_string()

    @property
    def serial(self):
        return self.certificate.serial_number

    def x5c(self):
        return [base64.b64encode(der(c)).decode() for c in self.chain]

    def __repr__(self):
        return f"Identity({self.name}: {self.subject})"


def _name(cn, org):
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn),
                      x509.NameAttribute(NameOID.ORGANIZATION_NAME, org),
                      x509.NameAttribute(NameOID.COUNTRY_NAME, "US")])


def new_key(kind="rsa"):
    if kind == "ec":
        return ec.generate_private_key(ec.SECP256R1())
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


class CertificateAuthority:
    """Issues leaves and CRLs. Either the bundled test CA, or a throwaway one nobody trusts."""

    def __init__(self, key, certificate):
        self.key = key
        self.certificate = certificate
        self.revoked = {}

    @classmethod
    def bundled(cls, cert_dir=None):
        """The repository's test root CA (``x9-test-ca.p12``): trusted by a test deployment."""
        cert_dir = cert_dir or os.environ.get("X9_TEST_CA_DIR") or DEFAULT_CERT_DIR
        with open(os.path.join(cert_dir, "x9-test-ca.p12"), "rb") as f:
            key, certificate, _ = pkcs12.load_key_and_certificates(f.read(), STOREPASS)
        return cls(key, certificate)

    @classmethod
    def throwaway(cls, cn="X9 Blackbox Untrusted Test CA"):
        """A CA made now and in no truststore. Its name carries "X9", so the only thing wrong
        with what it issues is that nobody trusts it."""
        key = new_key("rsa")
        name = _name(cn, "Example Org")
        now = datetime.datetime.now(datetime.timezone.utc)
        certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                       .public_key(key.public_key()).serial_number(x509.random_serial_number())
                       .not_valid_before(now - datetime.timedelta(days=1))
                       .not_valid_after(now + datetime.timedelta(days=3650))
                       .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
                       .add_extension(x509.KeyUsage(False, False, False, False, False, True, True,
                                                    False, False), critical=True)
                       .sign(key, hashes.SHA512()))
        return cls(key, certificate)

    def issue(self, name, cn, *, org="Example PSP", kind="rsa", crl_url=None):
        """A leaf for ``cn``, signed by this CA, with a CRL distribution point when given one.

        Without a reachable distribution point the service cannot determine revocation and
        refuses the certificate (see :class:`CrlServer`).
        """
        key = new_key(kind)
        now = datetime.datetime.now(datetime.timezone.utc)
        builder = (x509.CertificateBuilder()
                   .subject_name(_name(cn, org)).issuer_name(self.certificate.subject)
                   .public_key(key.public_key()).serial_number(x509.random_serial_number())
                   .not_valid_before(now - datetime.timedelta(days=1))
                   .not_valid_after(now + datetime.timedelta(days=3650))
                   .add_extension(x509.KeyUsage(True, True, False, False, False, False, False,
                                                False, False), critical=True))
        if crl_url:
            builder = builder.add_extension(x509.CRLDistributionPoints([x509.DistributionPoint(
                full_name=[x509.UniformResourceIdentifier(crl_url)], relative_name=None,
                reasons=None, crl_issuer=None)]), critical=False)
        certificate = builder.sign(self.key, hashes.SHA512())
        return Identity(name, key, certificate, [certificate, self.certificate])

    def revoke(self, identity):
        self.revoked[identity.serial] = datetime.datetime.now(datetime.timezone.utc)

    def crl(self):
        now = datetime.datetime.now(datetime.timezone.utc)
        builder = (x509.CertificateRevocationListBuilder()
                   .issuer_name(self.certificate.subject)
                   .last_update(now - datetime.timedelta(minutes=5))
                   .next_update(now + datetime.timedelta(days=30)))
        for serial, when in self.revoked.items():
            builder = builder.add_revoked_certificate(
                x509.RevokedCertificateBuilder().serial_number(serial).revocation_date(when).build())
        return builder.sign(self.key, hashes.SHA512()).public_bytes(serialization.Encoding.DER)


class CrlServer:
    """Serves the test CA's CRLs over HTTP, at the address the service fetches them from.

    The service checks revocation of every CA-issued certificate it verifies, and a CRL it cannot
    fetch is NOT forgiven (with no OCSP responder to fall back on, the status is undeterminable
    and the certificate is refused). So this server must be reachable from the service:

    - ``X9_SUITE_HOST`` is how the SERVICE reaches this process (default ``127.0.0.1``, right when
      both run on one host or share a network namespace);
    - ``X9_SUITE_CRL_PORT`` is the port (default: any free one). Set it to ``18081``, the
      distribution point baked into the bundled ``x9-test-*.p12`` fixtures, when the deployment
      itself signs with one of them and must check its own certificate. When the port is taken,
      any free port is used.

    It serves the run's own CRL under a per-run path (the JVM caches a fetched CRL by URL), and the
    bundled ``test-ca.crl`` and ``test-ca-revoked.crl`` at the root.
    """

    BUNDLED = ("/test-ca.crl", "/test-ca-revoked.crl")

    def __init__(self, ca, host=None, port=None, cert_dir=None):
        self.ca = ca
        self.cert_dir = cert_dir or os.environ.get("X9_TEST_CA_DIR") or DEFAULT_CERT_DIR
        self.advertised_host = host or os.environ.get("X9_SUITE_HOST", "127.0.0.1")
        bind_port = int(port if port is not None else os.environ.get("X9_SUITE_CRL_PORT", "0"))
        self.path = f"/crl/{uuid.uuid4().hex}.crl"
        self.fetches = 0
        server = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):                       # noqa: N802 - http.server API
                if self.path in CrlServer.BUNDLED:
                    with open(os.path.join(server.cert_dir, self.path.lstrip("/")), "rb") as f:
                        body = f.read()
                elif self.path == server.path:
                    server.fetches += 1
                    body = server.ca.crl()
                else:
                    self.send_response(404)
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/pkix-crl")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        socketserver.ThreadingTCPServer.allow_reuse_address = True
        try:
            self.httpd = socketserver.ThreadingTCPServer(("0.0.0.0", bind_port), Handler)
        except OSError:
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


# ------------------------------------------------------------------------------- signing

def _now_ms():
    return int(time.time() * 1000)


class Signer:
    """Signs as one identity, in the X9.150 profile.

    ``now_ms`` decides ``iat``. Give it the test clock's ``now_millis`` so a payer moves in time
    with the service under test (ADR-0033); otherwise it is this machine's clock.
    """

    def __init__(self, identity, now_ms=None, ttl_ms=DEFAULT_TTL_MS):
        self.identity = identity
        self.now_ms = now_ms or _now_ms
        self.ttl_ms = ttl_ms
        self.last_correlation_id = None

    def header(self, *, correlation_id=None, iat=None, ttl=None, crit=None, alg=None, x5c=True,
               extra=None, drop=()):
        header = {"alg": alg or alg_for(self.identity.key), "typ": "JOSE"}
        if x5c:
            header["x5c"] = self.identity.x5c()
        header["x5t#S256"] = thumbprint(self.identity.certificate)
        header["crit"] = list(CRIT if crit is None else crit)
        header["correlationId"] = str(correlation_id or uuid.uuid4())
        header["iat"] = self.now_ms() if iat is None else iat
        header["ttl"] = self.ttl_ms if ttl is None else ttl
        header.update(extra or {})
        for name in drop:
            header.pop(name, None)
        return header

    def sign(self, payload, *, key=None, **header_args):
        """A compact JWS of ``payload`` (a dict, or bytes already serialised)."""
        header = self.header(**header_args)
        self.last_correlation_id = header.get("correlationId")
        body = payload if isinstance(payload, (bytes, bytearray)) else \
            json.dumps(payload, separators=(",", ":")).encode()
        signing_input = f"{b64u_json(header)}.{b64u(body)}".encode()
        signature = raw_sign(key or self.identity.key, header["alg"], signing_input)
        return f"{signing_input.decode()}.{b64u(signature)}"


class Tamperer:
    """Every way a payer message can be wrong in its signature, one method per refusal case.

    Each returns a token for ``payload``; the expected ``signatureProblem`` code is in
    :data:`CASES`. None of these is a token the service under test would ever produce.
    """

    # case name -> the service's fixed code for it (OpenAPI SignatureProblem)
    CASES = {
        "wrong_key": "BAD_SIGNATURE",
        "untrusted_ca": "UNTRUSTED_CHAIN",
        "expired": "EXPIRED",
        "future_iat": "FUTURE_IAT",
        "missing_crit": "BAD_HEADER",
        "unknown_crit": "BAD_HEADER",
        "missing_x5c": "BAD_HEADER",
        "altered_payload": "BAD_SIGNATURE",
        "alg_none": "ALG_NONE",
    }

    def __init__(self, signer, untrusted_identity):
        self.signer = signer
        self.untrusted = untrusted_identity

    def token(self, case, payload, **header_args):
        """The ``case`` token for ``payload``. ``header_args`` (``correlation_id``, ``crit``,
        ``extra``...) shape the rest of the header, so a payee's answer can be tampered with too:
        the one defect stays the only thing wrong with it."""
        return getattr(self, case)(payload, **header_args)

    def wrong_key(self, payload, **h):
        """The certificate is the signer's; the signature is another key's."""
        is_ec = isinstance(self.signer.identity.key, ec.EllipticCurvePrivateKey)
        return self.signer.sign(payload, key=new_key("ec" if is_ec else "rsa"), **h)

    def untrusted_ca(self, payload, **h):
        return Signer(self.untrusted, self.signer.now_ms).sign(payload, **h)

    def expired(self, payload, **h):
        """Dated ten minutes ago with a one-minute ttl: iat + ttl passed nine minutes ago."""
        return self.signer.sign(payload, **{**h, "iat": self.signer.now_ms() - 600_000,
                                            "ttl": 60_000})

    def future_iat(self, payload, **h):
        """Dated five minutes ahead: beyond the 60 s skew the service forgives."""
        return self.signer.sign(payload, **{**h, "iat": self.signer.now_ms() + 300_000})

    def missing_crit(self, payload, **h):
        return self.signer.sign(payload, **{**h, "drop": ("crit",)})

    def unknown_crit(self, payload, **h):
        crit = list(h.get("crit") or CRIT) + ["x9blackbox"]
        extra = {**(h.get("extra") or {}), "x9blackbox": True}
        return self.signer.sign(payload, **{**h, "crit": crit, "extra": extra})

    def missing_x5c(self, payload, **h):
        """No x5c, no x5u, no jku: nothing to verify with."""
        return self.signer.sign(payload, **{**h, "x5c": False})

    def altered_payload(self, payload, **h):
        """Signed, then one claim changed: the amount, the field an attacker would change."""
        header, _, signature = self.signer.sign(payload, **h).split(".")
        altered = json.loads(json.dumps(payload))
        if "payment" in altered and "amount" in altered["payment"]:
            altered["payment"]["amount"] = altered["payment"]["amount"] + 1
        else:
            altered["x9blackbox"] = "altered"
        return f"{header}.{b64u_json(altered)}.{signature}"

    @staticmethod
    def alg_none(payload, **_):
        """An unsecured JOSE object: ``alg: none`` and no signature at all."""
        return f"{b64u_json({'alg': 'none'})}.{b64u_json(payload)}."


# ------------------------------------------------------------------------------- verifying

class VerificationError(AssertionError):
    def __init__(self, problem, detail):
        super().__init__(f"{problem}: {detail}")
        self.problem = problem
        self.detail = detail


def certificate_from_jwks(jwks, kid=None):
    """The signing certificate of a JWK set (its ``x5c[0]``), by ``kid`` when given."""
    keys = jwks.get("keys") or []
    for key in keys:
        if (kid is None or key.get("kid") == kid) and key.get("x5c"):
            return x509.load_der_x509_certificate(base64.b64decode(key["x5c"][0]))
    raise VerificationError("CERTIFICATE_UNAVAILABLE", f"no key with kid {kid} and an x5c in {jwks}")


def verify(token, certificate, *, expected_correlation_id=None, now_ms=None, skew_ms=60_000):
    """Verify a compact JWS against ``certificate``, in the X9.150 profile.

    Checks, in order: it is a JWS; ``crit`` names correlationId, iat and ttl; ``x5t#S256`` names
    this certificate; the signature verifies; the time window holds; the correlationId is the
    expected one. Returns ``(header, payload)``; raises :class:`VerificationError` with the same
    fixed codes the service uses.
    """
    try:
        header_b64, payload_b64, signature_b64 = token.split(".")
        header = json.loads(b64u_decode(header_b64))
        payload = json.loads(b64u_decode(payload_b64))
    except (AttributeError, ValueError) as e:
        raise VerificationError("NOT_JWS", str(e)) from e

    if header.get("alg", "none") == "none":
        raise VerificationError("ALG_NONE", "unsecured JWS")
    crit = header.get("crit") or []
    if not set(CRIT) <= set(crit):
        raise VerificationError("BAD_HEADER", f"crit must name {CRIT}, has {crit}")
    for name in crit:
        if name not in header:
            raise VerificationError("BAD_HEADER", f"crit names {name}, the header lacks it")
    if header.get("x5t#S256") != thumbprint(certificate):
        raise VerificationError("UNTRUSTED_CHAIN", "x5t#S256 does not name the expected certificate")

    try:
        raw_verify(certificate.public_key(), header["alg"],
                   f"{header_b64}.{payload_b64}".encode(), b64u_decode(signature_b64))
    except InvalidSignature as e:
        raise VerificationError("BAD_SIGNATURE", "the signature does not verify") from e

    now = (now_ms or _now_ms)()
    iat, ttl = int(header["iat"]), int(header["ttl"])
    if iat > now + skew_ms:
        raise VerificationError("FUTURE_IAT", f"iat {iat} is ahead of {now}")
    if now >= iat + ttl:
        raise VerificationError("EXPIRED", f"iat {iat} + ttl {ttl} <= {now}")

    if expected_correlation_id is not None and header["correlationId"] != str(expected_correlation_id):
        raise VerificationError("CORRELATION_ID_MISMATCH",
                                f"expected {expected_correlation_id}, got {header['correlationId']}")
    return header, payload
