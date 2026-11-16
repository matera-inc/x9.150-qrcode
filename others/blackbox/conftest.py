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
- ``X9_RECORD_DIR``: record every exchange of every test there, one JSON-lines file per test,
  for the differential run (``x9client.recording``, ``differential.py``). Off when unset.
- ``X9_TEST_CA_DIR``: where the bundled test CA lives (default: the repository's
  ``x9-qrcode-infrastructure/src/test/resources/certificate``).

The deployment must trust the bundled test CA: start it with
``X9_CERTIFICATE_TRUSTSTORE_LOCATION`` pointing at ``x9-test-truststore.jks`` (password
``x9test123``, alias ``x9-test-ca``). Payers are never signed for by the service under test.
"""
import os
import time

import pytest

import builds
from x9client import clock as clock_module
from x9client import db as db_module
from x9client import jws
from x9client.api import X9Api
from x9client.events import EventReader

pytest_plugins = ["required_keys", "recorder"]

REQUIRE_ALL = os.environ.get("X9_REQUIRE_ALL", "") not in ("", "0", "false")


_CONTRACT = {"executed": 0, "known_gaps": 0, "skipped": 0, "failed": 0}


def pytest_runtest_logreport(report):
    """Counts the generated contract cases as they run (S3.3-AC3)."""
    if "test_a_contract_constraint_is_refused" not in report.nodeid:
        return
    if report.when == "setup" and report.skipped:
        _CONTRACT["skipped"] += 1
    elif report.when == "call":
        if report.skipped and hasattr(report, "wasxfail"):
            _CONTRACT["executed"] += 1
            _CONTRACT["known_gaps"] += 1
        elif report.skipped:
            _CONTRACT["skipped"] += 1
        else:
            _CONTRACT["executed"] += 1
            _CONTRACT["failed"] += int(report.failed)


def pytest_terminal_summary(terminalreporter):
    """One line: how many contract cases were generated, executed and skipped (S3.3-AC3)."""
    if not any(_CONTRACT.values()):
        return
    import contract
    generated = len(contract.cases())
    terminalreporter.write_line(
        f"contract.py: {generated} cases generated, {_CONTRACT['executed']} executed "
        f"({_CONTRACT['known_gaps']} known gaps, {_CONTRACT['failed']} failed), "
        f"{_CONTRACT['skipped']} skipped")


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
    - ``untrusted``: issued by a throwaway CA that no truststore holds;
    - ``payee`` and ``payee_revoked``: the test payee's identity (``x9client.peer``), and a
      revoked one for the answer the service must refuse to trust.

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
        "payee": ca.issue("payee", "Blackbox Test Payee", org="Example Payee Bank",
                          crl_url=crl.url),
        "payee_revoked": ca.issue("payee_revoked", "Blackbox Test Payee Revoked",
                                  org="Example Payee Bank", crl_url=crl.url),
    }
    ca.revoke(parties["revoked"])
    ca.revoke(parties["payee_revoked"])
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
    return jws.Tamperer(payer_a, pki["untrusted"], pki["ca"])


@pytest.fixture(scope="session")
def deployment():
    """The deployment's API with nothing asked of it beyond answering: no QR Code is created and
    no payer is probed. For the test that the deployment is up (``X9-LIFE-502``), which a build
    with no payment-request endpoint yet must be able to pass (Story 9.1)."""
    return X9Api(os.environ.get("X9_BASE_URL", "http://localhost:8080"))


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
    if status != 200 and client.get("/actuator/health/readiness").status != 200:
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
def test_payee(api, pki, business_now):
    """A test payee this service can notify (``x9client.peer``), answering right by default.

    Before any test uses it, one pre-payment is sent to it through the service; if the service
    cannot reach it (502), the tests that need it stop with that diagnosis.
    """
    from x9client import peer as peer_module

    payee = peer_module.TestPayee(pki["payee"], now_ms=business_now)
    probe = api.post("/api/v1/payment-notification/pre-payment", body=peer_module.outbound(
        "pre-payment", endpoint=payee.endpoint, qr_id=api.create_qr()))
    if probe.status == 502 or not payee.received:
        payee.close()
        unavailable(f"the service cannot reach the test payee at {payee.endpoint} ({probe.status}); "
                    f"set X9_SUITE_HOST to this machine's address as the service sees it")
    yield payee
    payee.close()


@pytest.fixture(scope="session")
def other_bank(api, pki, business_now):
    """Another bank's payload host (``x9client.peer`` over https), on the test payee's identity.

    It serves whatever signed payload a test gives it at any ``/pub/api/v1/loc/{id}``. Before any
    test uses it, one payload this service issued is decoded through it; if the service cannot
    fetch from it (502: it does not trust the test CA for TLS, or cannot reach this process), the
    tests that need it stop with that diagnosis.
    """
    from x9client import emv
    from x9client import peer as peer_module

    host = os.environ.get("X9_SUITE_HOST", "127.0.0.1")
    bank = peer_module.TestPayee(pki["payee"], now_ms=business_now,
                                 tls=pki["ca"].issue_tls_server("Blackbox Other Bank", host))
    signer = jws.Signer(pki["payee"], now_ms=business_now)
    # Signed under the request's correlationId, as every answer must be (ADR-0027, 2026-11-15).
    bank.answer = lambda question: peer_module.Answer(
        200, signer.sign({"id": "probe", "paymentMethods": []},
                         correlation_id=question.correlation_id))
    created = api.create_qr_detailed().body
    probe = api.post("/api/v1/qrcode-emv-decoder", body={
        "qrCode": emv.with_location(created["qrCode"], bank.location("A" * 32))})
    if probe.status != 200:
        bank.close()
        unavailable(f"the service could not decode a payload served by another bank at "
                    f"https://{bank.location('A' * 32)} ({probe.status} {probe.body}); the "
                    f"deployment must trust the test CA for TLS (others/blackbox/deployment/"
                    f"x9-test-ca.pem through SSL_CERT_DIR) and reach X9_SUITE_HOST")
    yield bank
    bank.close()


@pytest.fixture
def peer(test_payee):
    """The test payee, reset: nothing received, answering a signed yes until told otherwise."""
    test_payee.reset()
    yield test_payee
    test_payee.reset()


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


def build_under_test():
    """``"java"`` or ``"go"`` (``builds.under_test``: ``X9_BUILD``, else the kind of
    ``X9_DATABASE_URL``)."""
    return builds.under_test()


@pytest.hookimpl(trylast=True)
def pytest_collection_modifyitems(items):
    """Tests marked ``runs_last`` run after every other test, in their collected order, whatever
    the file order or a ``-k`` selection. X9-TIME-015 moves the test clock past both retentions,
    so the Go build's retention job purges every earlier test's events and short-lived QR Codes;
    run last, it cannot take a row another test still needs."""
    last = [item for item in items if item.get_closest_marker("runs_last") is not None]
    if last:
        items[:] = [item for item in items if item.get_closest_marker("runs_last") is None] + last


def pytest_runtest_setup(item):
    """``go_only`` skips on Java and ``java_only`` on Go, with the reason (S4.5-AC3). Not a
    missing capability, so ``X9_REQUIRE_ALL`` does not turn it into a failure."""
    build = build_under_test()
    for marker, runs_on in (("go_only", "go"), ("java_only", "java")):
        found = item.get_closest_marker(marker)
        if found is not None and build != runs_on:
            reason = found.kwargs.get("reason") or f"{marker}: pins the {runs_on} build"
            pytest.skip(f"{reason} (this run is against the {build} build; X9_BUILD overrides)")
