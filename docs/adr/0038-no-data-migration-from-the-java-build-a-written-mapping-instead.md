# 38. No data migration from the Java build; a written mapping replaces the converter

Date: 2026-11-16

## Status

Accepted 2026-11-16 (owner). Supersedes the migration parts of [0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md)
and [0031](0031-same-image-lighter-and-3-0-is-a-configuration-migration.md) where they describe the
migration tools, which are marked amended by it. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).

## Context

ADR-0030 and ADR-0031 planned two tools for moving a deployment from the Java build to the Go build,
shipped together in a second image tag, `materainc/x9-qrcode:<version>-migrate`:

- **`x9-migrate`** would copy every MongoDB document into PostgreSQL: QR Codes with their revision
  and printed EMV string, the last stored notification, and the event log with each event's Java
  cursor kept for translation.
- **`x9-config-convert`** would read an operator's Spring YAML, `application-secrets.yml`, the
  Jasypt password and the keystores, and write the Go configuration file, the decrypted secrets
  and PKCS#12 keystores.

Both exist to protect data and configuration that someone depends on. The Java build has no
production users: in the owner's words, "we don't have real users using it yet". There is nothing
to move, and nobody to convert configuration for.

## Decision

**Neither tool is built.** An operator who tried the Java build starts the Go build on an empty
PostgreSQL schema and configures it by hand, following a written mapping.

**Not built:**

- `x9-migrate` (`cmd/x9-migrate`), and with it ADR-0030's migration rules: copying documents into
  rows, the recomputed-EMV check, the empty-outbox and empty-schema refusals, `--dry-run`;
- `x9-config-convert` (`cmd/x9-config-convert`);
- the `<version>-migrate` image tag and its `Dockerfile` target. There is one image.

**Instead:**

- **[`docs/CONFIGURATION-FROM-JAVA.md`](../CONFIGURATION-FROM-JAVA.md)** lists every property and
  environment variable the Java build reads, each with its Go key and environment variable, or
  "dropped" and why. It shows how to turn a JKS keystore into PKCS#12 with `keytool`, and how to
  replace a Jasypt `ENC(…)` value with the plain secret, supplied through an environment variable
  or a mounted file.
- **The start-up refusals stay** (ADR-0031): a Spring-style file, a `SPRING_*`, `MONGO*` or
  `JASYPT_*` variable, an `ENC(…)` value or a JKS keystore still stops the Go build at start. The
  message now names that document instead of a converter.

**What stays, already built and harmless:**

- **The legacy event-cursor translation of [0026](0026-identifiers-and-the-event-cursor.md):** the
  `payment_event_legacy_cursor` table, and the reading of a ULID `after=` cursor. With nothing
  migrated the table is empty, and a ULID cursor resumes at the start of the retained log, which is
  what ADR-0026 already says for that case. Removing it would change the schema and the cursor
  reader for no gain, and keeping it keeps the door open below.
- **The rest of ADR-0030** (the black-box suite defines "done", the differential run) and **the rest
  of ADR-0031** (same image name, 3.0.0, one YAML file plus the same environment variables, the
  start-up refusals, `java-final`) are unchanged.

**The cutover** (Epic 16) therefore has no migration step: start Go on an empty schema; the Java
build and its MongoDB are left as they are, so `java-final` remains a way back.

**When to revisit:** if a production user of the Java build appears before the cutover. Then the
data migration is designed again, starting from ADR-0030's rules, which stay in that ADR as a
record of what was worked out.

## Consequences

- No MongoDB driver and no Spring parser enter the Go module, even in a second image.
- Phase 9 of the plan shrinks to this decision and the mapping document.
- An operator of the Java build re-creates any QR Code they still need; QR Codes printed by the Java
  build do not resolve on the Go build. With no production users, nobody is affected.
- An event consumer of the Java build starts over: its stored ULID cursor reads the Go log from the
  start.
- The mapping document must change with the configuration: a Go key added, renamed or removed is
  reflected there.

## Alternatives rejected

- **Build the tools anyway, for future users.** The users they would serve do not exist, and the
  tools would be the only code in the Go module that reads MongoDB or Spring.
- **Build only the converter.** The configuration is short enough to map by hand once, and a table
  is easier to review than a tool.
- **Drop the refusals together with the converter.** Then a Java configuration would be half-read
  in silence; naming the mapping is as useful as naming a tool.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
