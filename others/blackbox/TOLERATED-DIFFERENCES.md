# Tolerated differences between the Java and the Go builds

> **Historical record.** The differential run ended with the Java build's retirement at 3.0.0
> ([ADR-0039](../../docs/adr/0039-java-retired-at-3-0-0.md)): there is no second build on `main`
> to compare, and no CI job reads this file any more. It is kept, unchanged below, as the record of
> how equivalence was proved and why each remaining difference was accepted. Some **Keys** name
> tests that existed only in the Java build; they are in git history under the tag `java-final`.
> The comparison can still be re-run against the `materainc/x9-qrcode:java-final` image (see the
> suite's [README.md](README.md), "Differential run").

ADR-0028 proves the two builds equivalent by a **differential run**: the black-box suite runs against
each, every exchange is recorded, and `differential.py` compares the normalised answers. **A
difference not on this list fails the build.** This file is that list (Story 11.2): every place the
builds answer differently on purpose, with the reason, the tests that show it, and the matcher the
diff applies. ADR-0028's "Known differences between the builds" table now lives here.

How to read a failing run, and how to add an entry: [README.md](README.md), "Differential run".

**What is not here.** Differences the normaliser removes because they change on every run of the
same build (ids, times, cursors, signatures, the order of `violations`, other tests' events) are not
differences between the builds; they are listed in the README's normalisation rules. Where the two
builds were found to differ and one of them was wrong, the build was fixed instead:

- Go wrote `paymentNotification.data.payer.info: null` where Java omits it (ADR-0028's NON_NULL):
  fixed in Go (`X9-HOLD-506`).
- Java wrote `signatureProblem: null`, `signatureDetail: null` and `body: null` in the payer-side
  pre-payment and post-payment answers: fixed in Java (`X9-SIG-130`, `X9-SIG-136`).
- The decoder: Java added `bill.paymentTiming: immediate` to every bill another bank sent without
  one, even beside an `invoice.dueDate`; Go presented none. Both now follow the owner's rule of
  2026-11-16: `immediate` when there is neither `paymentTiming` nor a `dueDate` (ANSI X9.150-2026
  §13.1 requires the member; our contract documents the default), nothing guessed beside a
  `dueDate` (`X9-DEC-516`, `X9-DEC-027`, `X9-DEC-517`). This was entry 25; the number is retired.
- Java answered a verified JWS that is not a notification (a payload request or response posted to
  `/pub/api/v1/payment-notification`) with a 500: fixed in Java, now Go's 400 naming `payment`
  (`X9-SIG-159`).

**Entry format.** `## N. Title`, then the two answers, the reason and a `**Keys:**` line naming at
least one test (until 3.0.0, `docs/check/check_rule_coverage.py` checked that each key exists), then one fenced
`json` block, the matcher (its members are described in `differential.py`'s docstring):

- `differences`: selectors matching rows of the report by `test` (a regular expression searched in
  the pytest node id), and optionally `request`, `field`, `kind`, `java`, `go`, `javaPattern`,
  `goPattern`, `present`;
- `oneBuild`: selectors matching a test only one build recorded (`go_only`, `java_only`);
- `proof`: `go-test` when no black-box exchange can show the difference (a Go unit test proves it,
  named under **Keys**), `normalised` when the normaliser removes it; such an entry has no selector;
- `status`: `tolerated`, or `pending` (not yet ruled on by the owner: tolerated so that the run is
  not red, and printed loudly by every run until it is decided);
- `mayNotOccur` on a selector whose difference depends on the run.

A selector that matches nothing in a run is **stale** and fails the run: a difference that has gone
away leaves the list. Numbers 1 to 22 are ADR-0028's; number 8 is retired (an unknown field in a
bank rail: both builds refuse it in the same words since 2026-11-15), and so is 25 (above).

## 1. Whitespace

**Java:** pretty-printed JSON (`INDENT_OUTPUT`). **Go:** compact JSON.

**Reason:** whitespace is not part of a JSON contract, and a signed payload is verified over the
bytes actually signed (ADR-0028, intended difference 1). The diff compares JSON as values, so it
never sees this.

**Keys:** `X9-SUITE-518`

```json
{"entry": 1, "proof": "normalised"}
```

## 2. An expired QR Code is kept

**Java:** MongoDB's TTL monitor deletes the QR Code about 30 s after `validUntil`; GET is then 404.
**Go:** the QR Code is kept, readable with its expiry reason, its location released, and removed by
the retention job only (ADR-0029).

**Reason:** ADR-0028's intended difference 2: time is applied on read, and retention is the only job.
Each build has its own test of it, so these tests are recorded by one build only.

**Keys:** `X9-TIME-012`, `X9-TIME-013`, `X9-TIME-014`, `X9-TIME-015`

```json
{"entry": 2, "oneBuild": [
  {"test": "test_t11_java_deletes_an_expired_qr_code_on_the_real_clock$", "recordedBy": "java", "why": "java_only"},
  {"test": "test_t11_go_keeps_an_expired_qr_code_and_releases_its_location$", "recordedBy": "go", "why": "go_only"},
  {"test": "test_t12_retention_purges_events_then_qr_codes_by_the_test_clock$", "recordedBy": "go", "why": "go_only"}
]}
```

## 3. A cursor ahead of the log is a 409

**Java:** `GET /pub/api/v1/events?after=` past the end of the log is an empty page (the cursor is
compared as a string). **Go:** **409 "Cursor Ahead Of Log"**, carrying the current `lastSeq`.

**Reason:** a cursor ahead of the log means the consumer is reading the wrong deployment or a
restored database; an empty page would hide it (ADR-0031). Story 10.1.

**Keys:** `X9-EVT-058`

```json
{"entry": 3, "oneBuild": [
  {"test": "test_a_cursor_ahead_of_the_log_is_a_conflict$", "recordedBy": "go", "why": "go_only"}
]}
```

## 4. A malformed cursor is a 400

**Java:** an `after=` that is neither empty, a decimal `seq` nor a ULID is compared as a string: an
empty page, or whatever sorts after it. **Go:** **400 naming `after`**.

**Reason:** a malformed cursor is the consumer's bug, and it should hear about it. Story 10.1.

**Keys:** `X9-EVT-505`

```json
{"entry": 4, "proof": "go-test"}
```

## 5. Every 500 is the `internal-error` problem

**Java:** Spring Boot's error body (only the signed notification answer uses `internal-error`).
**Go:** every 500 is the `internal-error` problem, with no stack, SQL or internal detail.

**Reason:** one shape for "our fault". Story 9.2. The suite causes no 500 on purpose, so a Go test
shows it.

**Keys:** `X9-SIG-535`

```json
{"entry": 5, "proof": "go-test"}
```

## 6. A JSON body over 1 MiB is refused

**Java:** read. **Go:** **400 `malformed-json`**.

**Reason:** the largest legitimate body is a few kilobytes; the cap bounds memory. Story 9.2.

**Keys:** `X9-SIG-536`

```json
{"entry": 6, "proof": "go-test"}
```

## 7. JSON edge cases Jackson lets through are malformed

**Java:** a lone surrogate escape (`"\ud800"`) and a date written as an array (`[2030,1,10]`) are
read. **Go:** **400 `malformed-json`**.

**Reason:** Go's decoder is stricter; a body a strict parser refuses is not one a payer can rely on
elsewhere. Story 9.2.

**Keys:** `X9-SIG-532`

```json
{"entry": 7, "oneBuild": [
  {"test": "test_a_json_edge_case_jackson_reads_is_malformed(\\[|$)", "recordedBy": "go", "why": "go_only"}
]}
```

## 9. The create and a PATCH answer with an ETag

**Java:** no `ETag` header on `POST /api/v1/payment-request` (create) or on an accepted `PATCH`.
**Go:** `ETag: "<revision>-<STATUS>"`, as GET serves it: `"0-ACTIVE"` on create, the new revision
on PATCH.

**Reason:** the next conditional request can use it: the first PATCH can send `If-Match` without a
GET first, and so can the one after a PATCH (ADR-0016). Story 9.6; the PATCH case was found by the
first differential run (Story 11.1); the owner accepted both on 2026-11-16.

**Keys:** `X9-PATCH-521`, `X9-PATCH-522`, `X9-PATCH-501`

```json
{"entry": 9, "differences": [
  {"test": ".", "kind": "header:ETag", "request": "POST /api/v1/payment-request", "java": null, "goPattern": "\"0-ACTIVE\""},
  {"test": ".", "kind": "header:ETag", "request": "PATCH /api/v1/payment-request/<id>", "java": null, "goPattern": "\"\\d+-[A-Z_]+\""}
]}
```

## 10. A database statement timeout while serving the payload is a 503

**Java:** the generic 400 (every exception is caught). **Go:** **503**.

**Reason:** "try again" is not "your request is wrong" (ADR-0025). Story 10.3. Only a Go test on
PostgreSQL can make the database time out.

**Keys:** `X9-LOC-514`

```json
{"entry": 10, "proof": "go-test"}
```

## 11. Only `x9-key.pem` is published

**Java:** `GET /pub/.well-known/certificate/{name}` answers 200 with the certificate, whatever the
name. **Go:** **404** for any other name.

**Reason:** only the name every `x5u` gives is published. Story 10.2.

**Keys:** `X9-SIG-537`

```json
{"entry": 11, "proof": "go-test"}
```

## 12. `signature/generate` refuses the nil UUID

**Java:** `POST /api/v1/signature/generate` with the nil UUID as `Correlation-Id` is signed.
**Go:** **400**, the missing-`correlationId` refusal.

**Reason:** the nil UUID identifies no exchange. Story 10.2. The suite never signs through the
service under test (ADR-0034), so a Go test shows it.

**Keys:** `X9-SIG-538`

```json
{"entry": 12, "proof": "go-test"}
```

## 13. `signature/generate` signs a number as it was sent

**Java:** Jackson's spelling (`1e2` becomes `100.0`). **Go:** as sent (`1e2`).

**Reason:** both are the same JSON number; a token from either build verifies on the other.
Story 10.2.

**Keys:** `X9-SIG-534`

```json
{"entry": 13, "proof": "go-test"}
```

## 14. A blank `transactionId` is left off the wire

**Java:** a blank `transactionId` (`""`, spaces) on an outbound notification is signed verbatim.
**Go:** left off the wire.

**Reason:** a blank is never a reference, and the phase is read from its absence (ADR-0004).
Story 10.6.

**Keys:** `X9-HOLD-505`

```json
{"entry": 14, "proof": "go-test"}
```

## 15. A payee answer over 1 MiB is a 502

**Java:** read. **Go:** **502**, as if the payee had not answered.

**Reason:** a signed `{"statusCode": n}` is a few kilobytes; the cap bounds memory. Story 10.6.

**Keys:** `X9-SIG-533`

```json
{"entry": 15, "oneBuild": [
  {"test": "test_a_payee_answer_over_1_mib_is_502$", "recordedBy": "go", "why": "go_only"}
]}
```

## 16. GET shows the holder's `NOT_SENT` once a hash is registered

**Java:** `paymentNotification.data` stays the earlier notification (the `SENT` with its
`transactionId`): the accepted `NOT_SENT` is not recorded. **Go:** the `NOT_SENT`.

**Reason:** GET shows the latest accepted notification; an accepted notification that goes
unrecorded is a Java defect, not a contract (ADR-0036). Story 10.5. `X9-HOLD-504` is a strict
expected failure on Java; `X9-MAP-043` shows the same thing from the transition map. Tolerated by the
owner, 2026-11-16.

**Keys:** `X9-HOLD-504`, `X9-MAP-043`

```json
{"entry": 16, "differences": [
  {"test": "::test_get_shows_the_holders_not_sent_after_a_hash_is_registered$", "request": "GET /api/v1/payment-request/<id>", "field": "body\\.paymentNotification\\.data\\..+"},
  {"test": "::test_map_043_not_sent_once_a_hash_is_registered$", "request": "GET /api/v1/payment-request/<id>", "field": "body\\.paymentNotification\\.data\\..+"}
]}
```

## 17. The decoder's fetch is bounded

**Java:** `POST /api/v1/qrcode-emv-decoder` fetches the payee's payload with the shared HTTP client,
with no timeout. **Go:** 5 s to connect, 15 s for the whole answer, a 1 MiB cap, no redirect
followed, no proxy; past any of them, `502 payload-unreachable`.

**Reason:** a payee that never answers must not hold a request, or memory, without end. Story 10.7.

**Keys:** `X9-DEC-511`, `X9-DEC-512`

```json
{"entry": 17, "proof": "go-test"}
```

## 18. The decoder: a payload that is not a JSON object

**Java:** 500 (the mapping fails). **Go:** **400 business-rule**, naming the payload.

**Reason:** the payee's fault is not ours; a 500 says it is. Story 10.7.

**Keys:** `X9-DEC-513`

```json
{"entry": 18, "proof": "go-test"}
```

## 19. The decoder: a member of the wrong JSON type

**Java:** 500: the typed mapping fails the whole decode. **Go:** the member is dropped; an
unreadable date-time is presented as written.

**Reason:** strict to generate, tolerant to read (INTERPRETATION I-16): another bank's slip in one
member does not hide the rest of its payload. Story 10.7.

**Keys:** `X9-DEC-514`

```json
{"entry": 19, "proof": "go-test"}
```

## 20. The decoder refuses the nil UUID

**Java:** `correlationId` the nil UUID is signed and sent. **Go:** **400**, nothing fetched.

**Reason:** as entry 12: the nil UUID identifies no exchange. Story 10.7.

**Keys:** `X9-DEC-515`

```json
{"entry": 20, "proof": "go-test"}
```

## 21. The decoder: a location URL with a non-numeric port

**Java:** `java.net.URI` accepts it: fetched, or failed later. **Go:** **400 `validation-error`** in
Java's words (`Invalid URI syntax: …`), nothing fetched.

**Reason:** a URL that cannot name one host and port is not fetched. Story 10.7.

**Keys:** `X9-DEC-509`

```json
{"entry": 21, "oneBuild": [
  {"test": "test_a_location_url_with_a_non_numeric_port_is_refused_before_any_fetch$", "recordedBy": "go", "why": "go_only"}
]}
```

## 22. The decoder does not follow a redirect

**Java:** a 3xx from the payee is followed. **Go:** not followed: its body is checked as the
payload's JWS and refused as an invalid signature.

**Reason:** a payload is served where the QR Code says; a redirect could send the fetch anywhere.
Story 10.7.

**Keys:** `X9-DEC-510`

```json
{"entry": 22, "oneBuild": [
  {"test": "test_a_redirect_from_the_payload_host_is_not_followed(\\[|$)", "recordedBy": "go", "why": "go_only"}
]}
```

## 23. An expired `ACTIVE` holder gives up its location (BUG-004)

**Java:** a create naming the location of an `ACTIVE` QR Code of the same creditor that is past its
`validUntil` is refused, 400 "The QRCode needs to be inactive to reuse location". **Go:** accepted,
201; the expired holder moves to a fresh location, and no event is written.

**Reason:** a Java defect (`known-bugs.md`, BUG-004): it reads the stored status, not the effective
one, so an expired holder still looks `ACTIVE`. `X9-LOC-421` is a strict expected failure on Java;
per ADR-0028's note of 2026-11-15, Java's known breaks are not compared. The test's later exchanges
(the GET and the event read Go answers) exist only on Go, since the Java run stops at the refusal.

**Keys:** `X9-LOC-421`

```json
{"entry": 23, "differences": [
  {"test": "::test_an_expired_active_holder_of_the_same_creditor_gives_up_its_location$"}
]}
```

## 24. The words of `signatureDetail` in the payer-side answers

**Java:** its verifier's own words (`JWS signature verification failed.`). **Go:** its own (`the
signature does not verify`). The fixed code, `signatureProblem`, is the same on both.

**Reason:** `signatureDetail` is free explanatory text, not frozen by ADR-0028: a client acts on
`signatureProblem`, the closed list of codes, and reads `signatureDetail` only to understand it
(openapi.yaml says so). `signatureProblem` is still compared: only the detail is tolerated, only in
the pre-payment and post-payment answers, and only when both builds give one. Accepted by the owner on
2026-11-16: the Go build keeps its own words, which is less complexity and less risk than copying
another verifier's.

**Keys:** `X9-SIG-502`, `X9-SIG-506`

```json
{"entry": 24, "differences": [
  {"test": ".", "request": "POST /api/v1/payment-notification/(pre|post)-payment", "field": "body\\.signatureDetail", "present": true}
]}
```

## 26. Which of 32 concurrent payers wins

**Java and Go:** exactly one of the 32 racing pre-commits is accepted and the 31 others are refused
409; which one wins is decided by the race, on each run of either build.

**Reason:** not a difference between the builds but between runs: the test proves that one wins,
not which. Tolerated only in that test, and only in the fields that name the winner: the status and
signed answer of each racer's notification, the event's `payerInfo` and GET's
`paymentNotification.data.payer.info`. Each selector may match nothing (the same racer can win on
both). Tolerated by the owner, 2026-11-16: a race cannot be made
deterministic.

**Keys:** `X9-HOLD-406`

```json
{"entry": 26, "differences": [
  {"test": "::test_32_concurrent_pre_commits_reserve_once$", "request": "POST /pub/api/v1/payment-notification", "field": "status|body\\.<jws>\\..+", "mayNotOccur": true},
  {"test": "::test_32_concurrent_pre_commits_reserve_once$", "request": "GET /pub/api/v1/events\\?.*", "field": "body\\.events\\[\\d+\\]\\.payerInfo", "mayNotOccur": true},
  {"test": "::test_32_concurrent_pre_commits_reserve_once$", "request": "GET /api/v1/payment-request/<id>", "field": "body\\.paymentNotification\\.data\\.payer\\.info", "mayNotOccur": true}
]}
```

## 27. A ULID cursor later than every migrated one

**Java:** `GET /pub/api/v1/events?after=7ZZZZZZZZZZZZZZZZZZZZZZZZZ` (a ULID after every cursor it
handed out) is an empty page, with no `nextCursor`. **Go:** the page from the start of retained
history, with a `nextCursor`, as ADR-0026 (owner, 2026-11-15) says: a ULID later than every
migrated cursor resumes after the last migrated event, the start when nothing was migrated.

**Reason:** tolerated by the owner on 2026-11-16. There are no real users of the Java build's event
cursors yet, so a Java-era cursor later than every migrated one is not a situation to design for
now; the Go build keeps ADR-0026's rule as amended. Not to be confused with entry 3: a *decimal*
cursor ahead of the log is a 409.

The first differential run found it through `X9-EVT-056`, which used that ULID to mean "the end of
the stream". That test now takes the end from the stream itself on each build, so it means the same
on both, and no black-box exchange shows this difference any more: the Go test of the translation
proves it.

**Keys:** `X9-EVT-506`

```json
{"entry": 27, "proof": "go-test"}
```
