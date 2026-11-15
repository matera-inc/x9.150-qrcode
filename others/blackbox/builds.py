# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Which build of the service the suite is running against: ``"java"`` or ``"go"``.

A module of its own, not a fixture, because a mark is decided when the test file is imported: a
test that Java fails by mistake and Go passes (S9.1-AC5) is a strict expected failure on one build
and an ordinary test on the other.

    import builds
    @pytest.mark.xfail(builds.under_test() == "java", strict=True, reason="BUG-…")
"""
import os


def under_test():
    """``X9_BUILD`` when set, else the kind of ``X9_DATABASE_URL`` (MongoDB is the Java build,
    PostgreSQL the Go build), else ``"java"``."""
    explicit = os.environ.get("X9_BUILD", "").strip().lower()
    if explicit in ("java", "go"):
        return explicit
    scheme = os.environ.get("X9_DATABASE_URL", "").split(":", 1)[0].lower()
    return "go" if scheme in ("postgres", "postgresql") else "java"
