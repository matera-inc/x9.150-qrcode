-- Copyright © 2026 Matera Systems, Inc.
-- Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
-- Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
-- based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
--
-- Story 6.3 smoke query (S6.3-AC2): the changelog left the tables, comments, roles and search_path
-- it promises, and nothing outside its schema. Run as a superuser right after an apply:
--
--   psql -X -v schema=x9_qrcode -v owner=x9_qrcode_owner -v app=x9_qrcode_app \
--        -v readonly=x9_qrcode_readonly -v event_reader=x9_qrcode_event_reader -f db/test/smoke.sql
--
-- Prints one line per check and fails (exit 3, ON_ERROR_STOP) on the first one that does not hold.

\set ON_ERROR_STOP on
\pset footer off

SELECT set_config('x9.schema', :'schema', false), set_config('x9.owner', :'owner', false),
       set_config('x9.app', :'app', false), set_config('x9.readonly', :'readonly', false),
       set_config('x9.event_reader', :'event_reader', false) \g /dev/null

DO $$
DECLARE
  s      text := current_setting('x9.schema');
  n      bigint;
  names  text;
  r      text;
BEGIN
  SELECT string_agg(tablename, ',' ORDER BY tablename) INTO names FROM pg_tables WHERE schemaname = s;
  IF names IS DISTINCT FROM 'databasechangelog,databasechangeloglock,event_read_status,event_sequence,event_type,'
      'payment_event,payment_event_legacy_cursor,qr_code,qr_code_audit_log,qr_code_notification,qr_code_status' THEN
    RAISE EXCEPTION 'tables of %: %', s, names;
  END IF;
  RAISE NOTICE 'ok  the schema holds exactly the X9.150 tables and Liquibase''s two';

  SELECT count(*) INTO n FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
   WHERE ns.nspname = s AND c.relkind = 'r' AND c.relname NOT LIKE 'databasechangelog%'
     AND coalesce(btrim(obj_description(c.oid, 'pg_class')), '') = '';
  IF n > 0 THEN RAISE EXCEPTION '% table(s) without a COMMENT', n; END IF;
  SELECT count(*) INTO n FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
   WHERE ns.nspname = s AND c.relkind = 'r' AND c.relname NOT LIKE 'databasechangelog%'
     AND coalesce(btrim(col_description(c.oid, a.attnum)), '') = '';
  IF n > 0 THEN RAISE EXCEPTION '% column(s) without a COMMENT', n; END IF;
  RAISE NOTICE 'ok  every table and column carries a COMMENT';

  EXECUTE format('SELECT (SELECT count(*) FROM %1$I.qr_code_status) || ''/'' || (SELECT count(*) FROM %1$I.event_type)
                    || ''/'' || (SELECT count(*) FROM %1$I.event_read_status)', s) INTO names;
  IF names <> '4/5/2' THEN RAISE EXCEPTION 'lookup rows (statuses/types/read statuses): %', names; END IF;
  EXECUTE format('SELECT count(*) FROM %I.event_sequence WHERE id = 1', s) INTO n;
  IF n <> 1 THEN RAISE EXCEPTION 'event_sequence must hold exactly its one row'; END IF;
  RAISE NOTICE 'ok  lookups seeded (4 statuses, 5 event types, 2 read statuses) and the event sequence row exists';

  SELECT count(*) INTO n FROM pg_indexes WHERE schemaname = s AND tablename = 'payment_event'
     AND indexdef LIKE '%(read_status_code, seq)%';
  IF n <> 1 THEN RAISE EXCEPTION 'payment_event has no (read_status_code, seq) index'; END IF;
  RAISE NOTICE 'ok  payment_event has its claim index (read_status_code, seq)';

  FOREACH r IN ARRAY ARRAY[current_setting('x9.owner'), current_setting('x9.app'), current_setting('x9.readonly'),
                           current_setting('x9.event_reader')] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN RAISE EXCEPTION 'role % is missing', r; END IF;
  END LOOP;
  IF (SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = s) <> current_setting('x9.owner') THEN
    RAISE EXCEPTION 'schema % is not owned by %', s, current_setting('x9.owner');
  END IF;
  RAISE NOTICE 'ok  the four roles exist and the owner owns the schema';

  FOREACH r IN ARRAY ARRAY[current_setting('x9.app'), current_setting('x9.readonly'), current_setting('x9.event_reader')] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_db_role_setting d JOIN pg_roles ro ON ro.oid = d.setrole
                    WHERE ro.rolname = r AND d.setdatabase = 0 AND ('search_path=' || s) = ANY (d.setconfig)) THEN
      RAISE EXCEPTION 'role % has no search_path = %', r, s;
    END IF;
  END LOOP;
  RAISE NOTICE 'ok  the three runtime roles have search_path = %', s;

  SELECT count(*) INTO n FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace WHERE ns.nspname = 'public';
  IF n > 0 THEN RAISE EXCEPTION '% object(s) in public', n; END IF;
  RAISE NOTICE 'ok  nothing in public';
END $$;
