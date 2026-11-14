-- Copyright © 2026 Matera Systems, Inc.
-- Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
-- Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
-- based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
--
-- The DBA's one-time step, for a THROWAWAY local or CI database only. Run it as a superuser:
--
--   psql -v owner=x9_qrcode_owner -v schema=x9_qrcode -f db/local/bootstrap.sql
--
-- It creates the owner role (LOGIN, with CREATEROLE so the changelog can create the three runtime
-- roles) and the schema, owned by that role. It sets no password: the throwaway container trusts
-- its own network. A bank's DBA does the same by hand, with a password and the house's policies
-- (see db/changelog/db.changelog-master.yaml). Running it twice changes nothing.

\set ON_ERROR_STOP on

SELECT format('CREATE ROLE %I LOGIN CREATEROLE', :'owner')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'owner')
\gexec

SELECT format('CREATE SCHEMA %I AUTHORIZATION %I', :'schema', :'owner')
 WHERE NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = :'schema')
\gexec
