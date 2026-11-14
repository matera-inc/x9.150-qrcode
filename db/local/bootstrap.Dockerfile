# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# psql with the local bootstrap script (db/docker-compose.yml): the DBA's one-time step, for a
# throwaway database only.
FROM postgres:16
COPY local/bootstrap.sql /x9/bootstrap.sql
