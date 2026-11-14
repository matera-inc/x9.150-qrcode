# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""
Fixtures for the black-box suite. The protocol lives in ``x9client``; this wires it to pytest.

    make blackbox                                   # against http://localhost:8080
    X9_BASE_URL=http://localhost:8070 pytest others/blackbox

Environment:

- ``X9_BASE_URL``: the deployment's API (default ``http://localhost:8080``).
- ``X9_CLOCK_URL``: its test-clock listener (default ``http://127.0.0.1:8099``). Time tests skip
  with "test clock not enabled" when nothing answers there.
- ``X9_DATABASE_URL``: optional read-only database connection. Database checks skip, with the
  reason, when it is unset or its driver is not installed.
- ``X9_REQUIRE_ALL=1``: turn every skip for a missing capability into a failure. CI sets it, so a
  suite that could not run is a red job, never a green one.
- ``X9_SUITE_HOST``: how the SERVICE reaches this process, for the CRL it serves (default
  ``127.0.0.1``). ``X9_SUITE_CRL_PORT`` fixes its port (default: any free one).
- ``X9_TEST_CA_DIR``: where the bundled test CA lives (default: the repository's
  ``x9-qrcode-infrastructure/src/test/resources/certificate``).

The deployment must trust the bundled test CA: start it with
``X9_CERTIFICATE_TRUSTSTORE_LOCATION`` pointing at ``x9-test-truststore.jks`` (password
``x9test123``, alias ``x9-test-ca``). Payers are never signed for by the service under test.
"""
import os
import time

import pytest

from x9client import clock as clock_module
from x9client import db as db_module
from x9client import jws
from x9client.api import X9Api
from x9client.events import EventReader

REQUIRE_ALL = os.environ.get("X9_REQUIRE_ALL", "") not in ("", "0", "false")


def unavailable(reason):
    """Skip, or fail when the run requires every capability (``X9_REQUIRE_ALL``)."""
    if REQUIRE_ALL:
        pytest.fail(f"{reason} (X9_REQUIRE_ALL is set, so this is a failure, not a skip)")
    pytest.skip(reason)


@pytest.fixture(scope="session")
def pki():
    """The payers of this run, issued now by the bundled test CA (``x9client.jws``).

    - ``a`` and ``b``: two distinct payer PSPs (RSA PS512 and EC ES256), trusted by a test
      deployment;
    - ``revoked``: issued by the same CA and revoked on its CRL;
    - ``untrusted``: issued by a throwaway CA that no truststore holds.

    Their certificates point at a CRL this process serves (``X9_SUITE_HOST``).
    """
    ca = jws.CertificateAuthority.bundled()
    crl = jws.CrlServer(ca)
    parties = {
        "ca": ca,
        "crl": crl,
        "a": ca.issue("a", "Blackbox Payer A", org="Example PSP A", kind="rsa", crl_url=crl.url),
        "b": ca.issue("b", "Blackbox Payer B", org="Example PSP B", kind="ec", crl_url=crl.url),
        "revoked": ca.issue("revoked", "Blackbox Payer Revoked", kind="rsa", crl_url=crl.url),
        "untrusted": jws.CertificateAuthority.throwaway().issue(
            "untrusted", "Blackbox Untrusted Payer", org="Example Org", crl_url=crl.url),
    }
    ca.revoke(parties["revoked"])
    yield parties
    crl.close()


@pytest.fixture(scope="session")
def business_now(_clock):
    """``now`` in milliseconds as the service sees it: the test clock when there is one."""
    if _clock.available():
        return _clock.now_millis
    return lambda: int(time.time() * 1000)


@pytest.fixture(scope="session")
def payer_a(pki, business_now):
    return jws.Signer(pki["a"], now_ms=business_now)


@pytest.fixture(scope="session")
def payer_b(pki, business_now):
    return jws.Signer(pki["b"], now_ms=business_now)


@pytest.fixture(scope="session")
def tamperer(pki, payer_a):
    return jws.Tamperer(payer_a, pki["untrusted"])


@pytest.fixture(scope="session")
def api(payer_a, pki):
    """The deployment's API. Payer messages are signed by payer A unless a test says otherwise.

    Before any test, one pre-commit signed by payer A must be accepted. If it is refused with 401
    the deployment does not trust the bundled test CA, or cannot fetch the suite's CRL; every
    payer test would then fail for that one reason, so the suite says so once instead.
    """
    base_url = os.environ.get("X9_BASE_URL", "http://localhost:8080")
    client = X9Api(base_url, signer=payer_a)

    status, _ = client.get("/actuator/health")
    if status != 200:
        unavailable(f"no deployment answering at {base_url} (health returned {status})")

    probe = client.notify(client.create_qr(), payer_info="trust-probe@example.com")
    if probe.status == 401:
        unavailable(
            f"the deployment at {base_url} refused a payer signed by the bundled test CA (401). "
            f"Start it with the black-box truststore (others/blackbox/deployment/"
            f"x9-blackbox-truststore.jks) and let it reach this suite's CRL at {pki['crl'].url} "
            f"(set X9_SUITE_HOST to this machine's address as the service sees it)")
    assert probe.status == 200, f"the trust probe was refused for another reason: {probe}"
    return client


@pytest.fixture(scope="session")
def payee_certificate(api):
    """The certificate the service signs its answers with, from its own JWK set."""
    response = api.get("/pub/.well-known/jwks")
    assert response.status == 200, f"the deployment publishes no JWK set: {response}"
    return jws.certificate_from_jwks(response.body)


@pytest.fixture(scope="session")
def events(api):
    """A reader that starts at the end of the stream as it stood when the session began."""
    reader = EventReader(api)
    reader.mark()
    return reader


@pytest.fixture(scope="session")
def _clock():
    return clock_module.Clock(os.environ.get("X9_CLOCK_URL", "http://127.0.0.1:8099"))


@pytest.fixture
def clock(_clock):
    """The test clock, reset before and after the test. Skips when the deployment has none."""
    if not _clock.available():
        unavailable(f"{clock_module.NOT_ENABLED} (nothing answers at {_clock.url})")
    _clock.reset()
    yield _clock
    _clock.reset()


@pytest.fixture(scope="session")
def db():
    inspector, reason = db_module.connect()
    if inspector is None:
        unavailable(f"database checks skipped: {reason}")
    return inspector
