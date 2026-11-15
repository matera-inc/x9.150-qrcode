#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "pytest==9.2.0",
#     "cryptography==50.0.2",
#     "pyyaml==6.0.3",
# ]
# ///
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Run the black-box suite with no virtualenv ceremony (PEP 723).

    uv run others/blackbox/run.py                      # against http://localhost:8080
    X9_BASE_URL=http://localhost:8070 uv run others/blackbox/run.py -k signatures

Arguments are passed to pytest. ``make blackbox`` does the same from a pinned virtualenv.
The dependencies above are the pinned ones in requirements.txt; keep the two in step.
"""
import os
import sys

import pytest

def wait_for_health(seconds):
    """With ``X9_WAIT_SECONDS``, wait that long for the deployment's health before testing (a JVM
    starting in a container). True once it is UP. Never fails here: if it is still down, the suite
    says so (or, under ``X9_REQUIRED_KEYS``, the caller stops the run)."""
    import time
    import urllib.request

    url = os.environ.get("X9_BASE_URL", "http://localhost:8080").rstrip("/") + "/actuator/health"
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                if response.status == 200:
                    print(f"{url} is UP", flush=True)
                    return True
        except Exception:                                           # noqa: BLE001 - not up yet
            pass
        time.sleep(2)
    print(f"{url} did not come UP within {seconds} s", flush=True)
    return False


if __name__ == "__main__":
    if os.environ.get("X9_WAIT_SECONDS"):
        up = wait_for_health(int(os.environ["X9_WAIT_SECONDS"]))
        # Under a required-keys list every unlisted test is informative, so a service that never
        # came up could otherwise leave the run green: it fails here instead (S9.1-AC3).
        if not up and os.environ.get("X9_REQUIRED_KEYS"):
            sys.exit("the service never came UP: the run fails (X9_REQUIRED_KEYS is set)")
    here = os.path.dirname(os.path.abspath(__file__))
    args = sys.argv[1:]
    if not any(os.path.exists(arg.split("::")[0]) for arg in args if not arg.startswith("-")):
        args.append(here)                  # options only: run the whole suite, not the cwd
    sys.exit(pytest.main(["-c", os.path.join(here, "pytest.ini"), "--rootdir", here, *args]))
