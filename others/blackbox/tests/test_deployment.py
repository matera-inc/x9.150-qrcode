# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The deployment is up (acceptance.py section 0, folded in by Story 3.4)."""


def test_the_deployment_is_ready(api):
    """S3.4-AC1, section 0 — health is UP, or at least readiness is.

    Why the fallback: on Kubernetes the Kubernetes health indicator can drag the aggregate
    ``/actuator/health`` to DOWN while the application serves perfectly well (it stays
    registered with ``SPRING_CLOUD_KUBERNETES_ENABLED=false``; ``MANAGEMENT_HEALTH_KUBERNETES_
    ENABLED=false`` is the flag that removes it). What decides whether there is any point testing
    is the readiness group.
    """
    health = api.get("/actuator/health")
    if health.status != 200:
        readiness = api.get("/actuator/health/readiness")
        assert readiness.status == 200, f"neither health nor readiness is UP: {health} {readiness}"
        return
    assert health.body.get("status") == "UP", health.body
