#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Run by the postgres image on an empty volume's first start (/docker-entrypoint-initdb.d): the
# DBA's one-time step, db/local/bootstrap.sql, with the names db/README.md uses.
set -euo pipefail
psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v owner=x9_qrcode_owner -v schema=x9_qrcode -f /x9/bootstrap.sql
