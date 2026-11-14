#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "pytest==9.2.0",
#     "cryptography==50.0.2",
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

if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    args = sys.argv[1:]
    if not any(os.path.exists(arg.split("::")[0]) for arg in args if not arg.startswith("-")):
        args.append(here)                  # options only: run the whole suite, not the cwd
    sys.exit(pytest.main(["-c", os.path.join(here, "pytest.ini"), "--rootdir", here, *args]))
