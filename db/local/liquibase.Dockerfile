# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Liquibase 4.33.0 with this repository's changelog copied in (db/docker-compose.yml). The changelog
# is copied rather than bind-mounted so compose works the same on every Docker host, including
# those that cannot mount the directory the repository lives in. Rebuild with `--build` after
# editing the changelog.
FROM liquibase/liquibase:4.33.0
COPY changelog /liquibase/x9/changelog
