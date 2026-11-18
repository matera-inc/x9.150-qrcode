# Test index

Every test, by key. The keys are stable: a bug report, an ADR or a message to an adopter can point
at one and still mean the same thing after the method has been renamed.

Each test's docstring or doc comment carries **Source** — whether the rule is the standard's, **ours**,
or a mechanism being pinned — and **Why** it matters. See [TEST-CONVENTIONS.md](TEST-CONVENTIONS.md).

**360 tests documented** — every black-box case, and the Go tests that prove
a difference between the builds no black-box case can drive, a fix the differential run found
(ADR-0028, [TOLERATED-DIFFERENCES.md](../others/blackbox/TOLERATED-DIFFERENCES.md)), or what an
operator's monitoring reads ([METRICS.md](METRICS.md)); their key starts the test's doc comment,
and the "Where" cell names the `_test.go` file.

**From a rule to its tests:** [RULE-COVERAGE.md](RULE-COVERAGE.md) lists every documented rule
(STATE-MACHINE, ENDPOINTS, INTERPRETATION, ADRs 0004 to 0021, ADR-0035's map, ADR-0036, ADR-0027's
EMV and JWS verification rules, ADR-0028, ADR-0029 and ADR-0031's metrics and actuator), the
tests that prove it and the acceptance criteria they cite, and the rules that have no test yet. A
check in CI keeps it true (`docs/check/check_rule_coverage.py`).

A reader looking for *what the standard requires* should read the **Source** lines first. A great
many of these rules are ours rather than X9.150's, and keeping that distinction visible is the
single most important thing this index exists for.

## Amounts and the bill

| Key | What it proves | Where |
|---|---|---|
| `X9-AMT-001` | an amount that is not the bill is refused | `test_payment_notification.py` |
| `X9-AMT-002` | a negative amount is refused | `test_payment_notification.py` |
| `X9-AMT-003` | the exact bill is accepted | `test_payment_notification.py` |
| `X9-AMT-004` | an amount outside the published editable range is refused | `test_payment_notification.py` |
| `X9-AMT-005` | any amount inside the published range is accepted | `test_payment_notification.py` |
| `X9-AMT-006` | a notified amount or tip amount of 19 digits is refused | `test_payment_notification.py` |

## Currencies

| Key | What it proves | Where |
|---|---|---|
| `X9-CUR-001` | a currency the QR Code does not offer is refused | `test_payment_notification.py` |
| `X9-CUR-520` | a pre-commit in a currency the QR Code does not offer is a signed 400 naming it (with the rail too when both); nothing recorded | `test_payers.py` |
| `X9-CUR-521` | the holder's post-commit in an unoffered currency is a signed 400, recorded on a `payment.sent` flagged `NOT_OFFERED_CURRENCY`; the reservation untouched, no hash registered | `test_payers.py` |
| `X9-CUR-522` | USD on Solana, when the QR Code offers USD on FedNow and USDC on Solana, is not offered: a pre-commit is a signed 400 recording nothing; the holder's post-commit is a signed 400 recorded on a `payment.sent` flagged `NOT_OFFERED_CURRENCY`, the QR Code unchanged | `test_payers.py` |
| `X9-CUR-501` | a currency outside supported-currencies.json is refused at create, naming it, with no id | `test_create_refusals.py` |

## The event stream

| Key | What it proves | Where |
|---|---|---|
| `X9-EVT-030` | the payment event carries the tip the payer reported | `test_payment_notification.py` |
| `X9-EVT-031` | an event for a payment with no tip reports no tip | `test_payment_notification.py` |
| `X9-EVT-501` | payment.initiated then payment.sent are published in order; a refusal publishes nothing | `test_two_phase.py` |
| `X9-EVT-502` | every event has its own eventId | `test_two_phase.py` |
| `X9-EVT-503` | payment.sent carries the transaction hash the payer reported | `test_two_phase.py` |
| `X9-EVT-504` | reading on from the cursor returns none of the events already read | `test_two_phase.py` |
| `X9-EVT-505` | Go only: an `after=` that is neither empty, a decimal seq nor a ULID is a 400 naming `after` (TOLERATED-DIFFERENCES.md, entry 4) | `eventstream_test.go` |
| `X9-EVT-506` | Go only: a ULID cursor later than every migrated one resumes after the last migrated event, the beginning when nothing was migrated (ADR-0026; TOLERATED-DIFFERENCES.md, entry 27) | `events_postgres_test.go` |

## Lifecycle, status and delivery

| Key | What it proves | Where |
|---|---|---|
| `X9-LIFE-001` | a notification for an unknown QR Code is 404 | `test_payment_notification.py` |
| `X9-LIFE-002` | a second pre-payment from a DIFFERENT payer is refused | `test_payment_notification.py` |
| `X9-LIFE-003` | a payment on a cancelled QR Code is refused | `test_payment_notification.py` |
| `X9-LIFE-004` | a post-payment with no pre-payment is refused | `test_payment_notification.py` |
| `X9-LIFE-005` | a refused notification leaves the QR Code ACTIVE | `test_payment_notification.py` |
| `X9-LIFE-120` | the payee may settle an ACTIVE bill | `test_payment_notification.py` |
| `X9-LIFE-121` | the payee may settle a bill a payer has reserved | `test_payment_notification.py` |
| `X9-LIFE-122` | the payee may settle a bill whose validUntil has passed | `test_payment_notification.py` |
| `X9-LIFE-123` | a cancelled bill cannot be marked paid | `test_payment_notification.py` |
| `X9-LIFE-124` | once PAID, payers are refused | `test_payment_notification.py` |
| `X9-LIFE-126` | a payer cannot fetch the payload of an expired QR Code | `test_payment_notification.py` |
| `X9-LIFE-127` | X to X is accepted, on every status | `test_payment_notification.py` |
| `X9-LIFE-504` | the control of the contract cases: each operation's unbroken sample is accepted | `test_contract.py` |
| `X9-LIFE-505` | every length, pattern, required and numeric bound in openapi.yaml, and an unknown field in every closed object, is refused: 400, the field's path (and, for a closed object, its own fields), nothing changed | `test_contract.py` |
| `X9-LIFE-501` | a GET of an id nobody issued is 404 | `test_create_refusals.py` |
| `X9-LIFE-506` | a create refused for its currency leaves the store exactly as it was | `test_database.py` |
| `X9-LIFE-507` | a notification refused for its amount stores nothing and publishes nothing | `test_database.py` |
| `X9-LIFE-502` | the deployment under test reports health or readiness UP | `test_deployment.py` |
| `X9-LIFE-503` | status-update PAID is accepted, and a PAID QR Code stays PAID | `test_settlement.py` |

### Who holds a reservation

Only the party that announced a payment may re-announce it, report it, or give it back. Identity is
`payer.info` paired with the subject of the certificate that signed the JWS — see ADR-0021.

| Key | What it proves | Where |
|---|---|---|
| `X9-HOLD-010` | a repeated announcement from the same payer is accepted | `test_payment_notification.py` |
| `X9-HOLD-011` | a second payer is refused, and told it is a conflict | `test_payment_notification.py` |
| `X9-HOLD-012` | a repeat does not appear on the event stream | `test_payment_notification.py` |
| `X9-HOLD-013` | payment events say who paid | `test_payment_notification.py` |
| `X9-HOLD-014` | a payer who sent no info reports null, never empty | `test_payment_notification.py` |
| `X9-HOLD-020` | NOT_SENT releases the reservation | `test_payment_notification.py` |
| `X9-HOLD-021` | a released QR Code is payable by somebody else | `test_payment_notification.py` |
| `X9-HOLD-022` | the release is reported, not silent | `test_payment_notification.py` |
| `X9-HOLD-023` | only the holder may give the bill back | `test_payment_notification.py` |
| `X9-HOLD-024` | only the holder may report a payment as sent | `test_payment_notification.py` |
| `X9-HOLD-401` | a rail and a currency in another case are accepted and echoed as sent, stored and in the event | `test_payers.py` |
| `X9-HOLD-402` | no post-commit marks a QR Code PAID, on any rail, the holder's or a second hash or one on ACTIVE | `test_payers.py` |
| `X9-HOLD-403` | a QR Code created without paymentNotification refuses notifications, signed, and stays ACTIVE | `test_payers.py` |
| `X9-HOLD-404` | paymentNotification EXTERNAL: the payload names the external endpoint; a notification here is refused | `test_payers.py` |
| `X9-HOLD-405` | every answer to a payer is signed, refusals included, statusCode equal to the HTTP status | `test_payers.py` |
| `X9-HOLD-406` | 32 concurrent pre-commits: exactly one 200, 31 signed 409s, one payment.initiated | `test_payers.py` |
| `X9-HOLD-407` | an ACH notification without `payer.info` (no payer, or no info) or without `expectedDate` is refused, signed, and reserves nothing | `test_payers.py` |
| `X9-HOLD-408` | two post-commits from the holder, two hashes: two `payment.sent`, each carrying its own hash | `test_payers.py` |
| `X9-HOLD-501` | a pre-commit is accepted and the QR Code is PAYMENT_INITIATED | `test_two_phase.py` |
| `X9-HOLD-502` | a second payer, on its own key, is refused with 409 while the QR Code is reserved | `test_two_phase.py` |
| `X9-HOLD-503` | a post-commit is accepted and does not mark the QR Code PAID | `test_two_phase.py` |
| `X9-HOLD-504` | the holder's `NOT_SENT`, accepted once a hash is registered, is what GET shows as `paymentNotification.data` (ADR-0028 difference 16) | `test_payers.py` |
| `X9-HOLD-505` | Go only: a blank `transactionId` on an outbound notification is left off the wire (TOLERATED-DIFFERENCES.md, entry 14) | `outbound_test.go` |
| `X9-HOLD-506` | Go: a member the payer sent as null is left out of `paymentNotification.data` on GET, as Java's NON_NULL writes it (found by the first differential run) | `view_test.go` |

## The transition map (ADR-0035)

One black-box test per row of ADR-0035's map (Story 4.1); the row-to-test table is in the ADR.

| Key | What it proves | Where |
|---|---|---|
| `X9-MAP-001` | create: ACTIVE, no event | `test_transition_map.py` |
| `X9-MAP-002` | PATCH: allowed from ACTIVE and PAYMENT_INITIATED, status unchanged, revision moves, no event; refused from PAID and CANCELLED | `test_transition_map.py` |
| `X9-MAP-010` | status-update PAYMENT_INITIATED: from ACTIVE, unattributed reservation, `payment.initiated`; on a live reservation a retry (200, nothing changes); 409 from PAID and CANCELLED; a lapsed reservation is reserved again (BUG-002, fixed) | `test_transition_map.py` |
| `X9-MAP-011` | status-update PAID: from ACTIVE or PAYMENT_INITIATED, `payment.cleared` with the last notification's body; 400 from CANCELLED; PAID → PAID is a retry, 200 and nothing changes | `test_transition_map.py` |
| `X9-MAP-012` | status-update CANCELLED: from ACTIVE or PAYMENT_INITIATED, `payment.cancelled`; 400 from PAID; CANCELLED → CANCELLED is a retry, 200 and nothing changes | `test_transition_map.py` |
| `X9-MAP-013` | status-update ACTIVE: from PAYMENT_INITIATED, no event, the reservation is gone; 400 from PAID, CANCELLED and a lapsed reservation; ACTIVE → ACTIVE is a retry, 200 and nothing changes | `test_transition_map.py` |
| `X9-MAP-020` | a pre-commit on ACTIVE, every rail, takes the reservation with `payment.initiated`, a lapsed one's takeover included | `test_transition_map.py` |
| `X9-MAP-021` | the holder's retry changes nothing, emits nothing, and refreshes the window | `test_transition_map.py` |
| `X9-MAP-022` | a pre-commit once a hash is registered is 409, the holder's included | `test_transition_map.py` |
| `X9-MAP-023` | the holder's retry refused by the gate releases the reservation; `payment.failed` on a chain with the stored notification's body | `test_transition_map.py` |
| `X9-MAP-024` | a pre-commit while another payer holds the QR Code is 409, a payer without `payer.info` included | `test_transition_map.py` |
| `X9-MAP-025` | a pre-commit on PAID or CANCELLED is 400 | `test_transition_map.py` |
| `X9-MAP-030` | a bank-rail post-commit on ACTIVE reserves, stops the window, `payment.initiated` flagged | `test_transition_map.py` |
| `X9-MAP-031` | a blockchain post-commit on ACTIVE reserves, stops the window, `payment.initiated` then `payment.sent`, both flagged | `test_transition_map.py` |
| `X9-MAP-032` | the holder's post-commit: unchanged, window stopped, clean `payment.sent` on a chain | `test_transition_map.py` |
| `X9-MAP-033` | a post-commit while someone else holds it is registered, flagged, and the holder keeps it | `test_transition_map.py` |
| `X9-MAP-034` | a post-commit on PAID or CANCELLED is registered, flagged, and never reopens it | `test_transition_map.py` |
| `X9-MAP-040` | PAYMENT_INITIATED carrying a hash is a post-commit, flagged ACTION_CONTRADICTS_HASH | `test_transition_map.py` |
| `X9-MAP-041` | SENT without a hash is 400 in every status | `test_transition_map.py` |
| `X9-MAP-042` | the holder's NOT_SENT releases with `payment.failed`, a lapsed reservation's included | `test_transition_map.py` |
| `X9-MAP-043` | the holder's NOT_SENT once a hash is registered changes nothing and emits nothing | `test_transition_map.py` |
| `X9-MAP-044` | NOT_SENT carrying a transactionId is a post-commit, flagged ACTION_CONTRADICTS_HASH | `test_transition_map.py` |
| `X9-MAP-045` | NOT_SENT not from the holder: 409 while held, 400 on ACTIVE, PAID, CANCELLED | `test_transition_map.py` |
| `X9-MAP-050` | a location released by a rebind: the donor keeps its status, gets a new location, no event | `test_transition_map.py` |
| `X9-MAP-051` | a lapse reads ACTIVE, writes nothing, emits nothing; never once a hash is registered | `test_transition_map.py` |

## Status update and the event stream (Story 4.4)

What the transition map does not reach (S4.4-AC1) and the properties of the stream (S4.4-AC2).
The status-update matrix itself is `X9-MAP-010` to `-013` above.

| Key | What it proves | Where |
|---|---|---|
| `X9-LIFE-170` | past `validUntil` the payee may still mark PAID, with `payment.cleared`, while a payer is refused at the same instant | `test_status_update_and_events.py` |
| `X9-LIFE-171` | PAID on a lapsed reservation is accepted; `payment.cleared` carries the lapsed holder's notification | `test_status_update_and_events.py` |
| `X9-LIFE-172` | CANCELLED is final: every status-update, pre-commit, NOT_SENT and PATCH, tried on one QR Code, changes nothing | `test_status_update_and_events.py` |
| `X9-LIFE-173` | a status-update reservation is adopted by no payer, whatever identity it presents: 409 | `test_status_update_and_events.py` |
| `X9-LIFE-174` | a status-update reservation lapses like any other; the next payer's pre-commit takes it | `test_status_update_and_events.py` |
| `X9-LIFE-175` | a status-update with an If-Match stale by revision or by status is 412 and changes nothing; the current tag is accepted | `test_status_update_and_events.py` |
| `X9-LIFE-176` | a PAID retried with the first attempt's If-Match is 412 today (pinned, not decided) | `test_status_update_and_events.py` |
| `X9-EVT-050` | every status-update transition emits exactly its event, in order per QR Code, interleaved with another | `test_status_update_and_events.py` |
| `X9-EVT-051` | paging two at a time from an old cursor yields exactly what one large read yields: no gap, no repeat | `test_status_update_and_events.py` |
| `X9-EVT-052` | `hasMore` is true on a page cut at `limit`; a read past the end is empty, `hasMore` false, no `nextCursor` | `test_status_update_and_events.py` |
| `X9-EVT-053` | `wait` above 30 is refused at once with 400 naming `wait` (the contract's `maximum: 30`) | `test_status_update_and_events.py` |
| `X9-EVT-054` | an empty long poll is held for `wait` seconds: 0 at once, 2, and 30 | `test_status_update_and_events.py` |
| `X9-EVT-055` | a replay from an old cursor returns the same events, ids and bodies, so deduplication by `eventId` applies each once | `test_status_update_and_events.py` |
| `X9-EVT-056` | `limit` 0 and 501 are refused with 400 naming `limit`; 1 and 500 are accepted | `test_status_update_and_events.py` |
| `X9-EVT-057` | a read without `limit` returns 100 events and `hasMore` when more stand behind the cursor | `test_status_update_and_events.py` |
| `X9-EVT-058` | Go only: a decimal cursor after the last event is a 409 "cursor ahead of log" carrying `lastSeq` (ADR-0031) | `test_status_update_and_events.py` |

## Locations and payload retrieval

| Key | What it proves | Where |
|---|---|---|
| `X9-LOC-301` | a location no QR Code holds is refused (400, generic reason; pinned) | `test_payers.py` |
| `X9-LOC-302` | content not byte-equal to the issued EMV is refused | `test_payers.py` |
| `X9-LOC-303` | the issued content, signed by a trusted payer, is served (control) | `test_payers.py` |
| `X9-LOC-304` | a payload request failing any signature check is refused, 400, serving nothing | `test_payers.py` |
| `X9-LOC-305` | a payer revoked on the CRL cannot fetch a payload | `test_payers.py` |
| `X9-LOC-306` | with no CRL, OCSP decides: good accepted, revoked refused, on both payer endpoints | `test_payers.py` |
| `X9-LOC-307` | an unreachable revocation source soft-fails: CRL and OCSP, OCSP only, or CRL only (BUG-003, fixed); so does a CRL that answers with something that is not a CRL, alone or with an unreachable OCSP responder | `test_payers.py` |
| `X9-LOC-308` | a certificate naming no revocation source is refused | `test_payers.py` |
| `X9-LOC-309` | an expired payment method is dropped from the payload and the others stay | `test_payers.py` |
| `X9-LOC-310` | a PAID or CANCELLED QR Code serves no payload and says which | `test_payers.py` |
| `X9-LOC-311` | a CRL that is not a CRL hands over to OCSP: good accepted, revoked refused, on both payer endpoints | `test_payers.py` |
| `X9-LOC-312` | an `x5u` over plain http that only mentions the own host in its query is not fetched over http: `CERTIFICATE_UNAVAILABLE` (BUG-005) | `test_payers.py` |
| `X9-LOC-313` | an OCSP answer ADR-0027 refuses is refused, not soft-failed, on both payer endpoints: `malformedRequest`, `sigRequired`, `unauthorized`; an unauthorised signer; a delegated responder without `OCSPSigning`; `nextUpdate` passed or `thisUpdate` in the future; no answer for the certificate | `test_payers.py` |
| `X9-LOC-314` | controls of `X9-LOC-313`: `tryLater` and `internalError` soft-fail, and a delegated responder with `OCSPSigning` is relied on, on both payer endpoints | `test_payers.py` |
| `X9-LOC-501` | a live QR Code keeps its location: another create cannot take it | `test_location_handover.py` |
| `X9-LOC-502` | a CANCELLED QR Code's location goes to the taker, and the donor gets a new one | `test_location_handover.py` |
| `X9-LOC-503` | the payload is a JWS that verifies against the payee's certificate | `test_payload.py` |
| `X9-LOC-504` | the payload states the amount, the currency and the Solana recipient that were created | `test_payload.py` |
| `X9-LOC-505` | the payload names the notification endpoint | `test_payload.py` |
| `X9-LOC-506` | an unsigned payload request is refused with 400 and serves nothing | `test_payload.py` |
| `X9-LOC-507` | a QR Code's id is 32 upper-case hex characters | `test_qr_code.py` |
| `X9-LOC-508` | the EMV string is well formed: format indicator 01, honest TLV lengths, a valid CRC | `test_qr_code.py` |
| `X9-LOC-509` | the org.x9 template carries the loc URL naming this QR Code, within 99 characters | `test_qr_code.py` |
| `X9-LOC-510` | qrCodeB64 decodes to the EMV string, byte for byte | `test_qr_code.py` |
| `X9-LOC-511` | the advertised location endpoint is https and ends with the location id | `test_qr_code.py` |
| `X9-LOC-512` | a new QR Code reads back ACTIVE with its Solana recipient | `test_qr_code.py` |
| `X9-LOC-513` | sentAt is the instant the payload was composed, on the service's clock: within the request's window, never before revisedAt and never after validUntil | `test_payload.py` |
| `X9-LOC-514` | Go only: the payload's read cancelled by the database (SQLSTATE 57014, a statement timeout's code) is 503 `internal-error`, not the generic 400 (ADR-0028 difference 10) | `payload_test.go` |

### What the payer is told when a decode is refused

The decoder calls `/pub/api/v1/loc/{id}` on the payer's behalf. Every refusal used to arrive as one
string, with an internal address attached, so a settled bill, a withdrawn one, a lapsed one, a
tampered one and an outage were indistinguishable — and four of the five invite a retry.

| Key | What it proves | Where |
|---|---|---|
| `X9-DEC-006` | a decode of an expired code says it expired | `test_payment_notification.py` |
| `X9-DEC-007` | the most ordinary refusal reaches the payer intact | `test_payment_notification.py` |
| `X9-DEC-016` | the decoder refuses the Go build's 19 malformed cases with a 400, before any fetch (black box) | `test_qr_code.py` |
| `X9-DEC-501` | another bank's payload, served over https and signed by its own key, with unknown rails, an unknown currency and unknown fields decodes (black box) | `test_tolerant_reading.py` |
| `X9-DEC-502` | an unknown currency and an unknown rail reach the payer as the other bank wrote them | `test_tolerant_reading.py` |
| `X9-DEC-503` | an unknown field inside a known rail of another bank's payload is ignored | `test_tolerant_reading.py` |
| `X9-DEC-504` | an unknown field on a method, on the creditor or at the top of another bank's payload is ignored | `test_tolerant_reading.py` |
| `X9-DEC-505` | another bank's payload signed by an untrusted CA, or altered after signing, is refused and nothing of it presented | `test_tolerant_reading.py` |
| `X9-DEC-506` | another bank's payload under another correlationId than the request's is refused, `QR Code is invalid (bad correlationId)`, with or without a caller's id (black box) | `test_tolerant_reading.py` |
| `X9-DEC-507` | another bank's payload with no correlationId in its header is refused, `QR Code is invalid (bad correlationId)`, with or without a caller's id (black box) | `test_tolerant_reading.py` |
| `X9-DEC-508` | another bank's payload altered after signing, under another correlationId, is refused as a bad signature, not as a bad correlationId (black box) | `test_tolerant_reading.py` |
| `X9-DEC-509` | Go only: a payload URL with a non-numeric port is 400 `validation-error`, `Invalid URI syntax: …`, and nothing is fetched (ADR-0028 difference 21, black box) | `test_tolerant_reading.py` |
| `X9-DEC-510` | Go only: a 302 or 307 from the payload host is not followed; its body is refused as an invalid signature (ADR-0028 difference 22, black box) | `test_tolerant_reading.py` |
| `X9-DEC-511` | Go only: the outbound client, the decoder's fetch and the notification's delivery, reads no proxy from the environment (ADR-0028 difference 17) | `client_test.go` |
| `X9-DEC-512` | Go only: a payee that does not answer within the decoder's bounds is a 502 `payload-unreachable` that carries no address (TOLERATED-DIFFERENCES.md, entry 17) | `decoder_test.go` |
| `X9-DEC-513` | Go only: a verified payload that is not a JSON object is a 400 business-rule, not a 500 (TOLERATED-DIFFERENCES.md, entry 18) | `decoder_test.go` |
| `X9-DEC-514` | Go only: a payload member of the wrong JSON type is dropped and an unreadable date-time presented as written (TOLERATED-DIFFERENCES.md, entry 19) | `foreign_test.go` |
| `X9-DEC-515` | Go only: the nil UUID as `correlationID` is a 400, and nothing is fetched (TOLERATED-DIFFERENCES.md, entry 20) | `decoder_test.go` |
| `X9-DEC-516` | Go: another bank's bill without paymentTiming is presented as `immediate` only without an `invoice.dueDate`; with one it stays absent; a value sent is kept | `timing_test.go` |
| `X9-DEC-517` | a bill another bank sent without paymentTiming decodes as `immediate` without a dueDate, without paymentTiming beside one, and as sent when sent (both builds) | `test_tolerant_reading.py` |

## Editing a payment request

| Key | What it proves | Where |
|---|---|---|
| `X9-PATCH-440` | `lockVersion` is in no answer: create, GET, PATCH, or the signed payload | `test_conditional_requests.py` |
| `X9-PATCH-501` | a GET serves an ETag | `test_conditional_requests.py` |
| `X9-PATCH-502` | a stale If-Match on PATCH is 412, and neither the amount nor the ETag moves | `test_conditional_requests.py` |
| `X9-PATCH-503` | a stale If-Match on status-update is 412, and the QR Code stays ACTIVE | `test_conditional_requests.py` |
| `X9-PATCH-504` | a repeated additionalInformation label is not collapsed | `test_conditional_requests.py` |
| `X9-PATCH-505` | a status change through status-update is not a new revision | `test_conditional_requests.py` |
| `X9-PATCH-506` | a PATCH naming every currency changes every currency | `test_patch_currencies.py` |
| `X9-PATCH-507` | a PATCH omitting a currency is refused naming it, and nothing moves | `test_patch_currencies.py` |
| `X9-PATCH-508` | a PATCH adding a currency the QR Code does not offer is refused naming it, and nothing moves | `test_patch_currencies.py` |
| `X9-PATCH-509` | a PATCH naming only an unknown currency is refused, not a silent 200 | `test_patch_currencies.py` |
| `X9-PATCH-510` | a PATCH whose body is the whole GET answer, with one patchable change, is applied; the read-only members are accepted | `test_closed_requests.py` |
| `X9-PATCH-511` | the whole GET answer with a read-only member changed (status, currency, creditor) is refused naming it; nothing moves | `test_closed_requests.py` |
| `X9-PATCH-512` | a PATCH whose merged tip is allowed with neither range nor presets is refused, nothing moves; stored presets are kept and count | `test_patch_merged_rules.py` |
| `X9-PATCH-513` | a due date or a discount edge the PATCH sets in the past is refused in the create's words, nothing moves | `test_patch_merged_rules.py` |
| `X9-PATCH-514` | a `validUntil` set in the past is refused; an expired QR Code is extended, its untouched past dates not judged again | `test_patch_merged_rules.py` |
| `X9-PATCH-515` | an earlier `validUntil` cuts a running reservation back: ACTIVE past it, not for the rest of the window | `test_patch_merged_rules.py` |
| `X9-PATCH-516` | an order, an invoice with its invoicee and adjustments sent to a bill that had none are applied | `test_patch_merged_rules.py` |
| `X9-PATCH-517` | a currency named twice is refused naming it, nothing moves | `test_patch_merged_rules.py` |
| `X9-PATCH-518` | `bill: null` and `editable: {}` are refused by name; `editable: null` removes `editable` | `test_patch_merged_rules.py` |
| `X9-PATCH-519` | a PATCH whose tip is not allowed and carries a range or presets is refused in the create's words, nothing moves | `test_patch_merged_rules.py` |
| `X9-PATCH-520` | `allowed: false` alone, or with `presets: null`, turns a stored tip offer off, its range and presets with it | `test_patch_merged_rules.py` |
| `X9-PATCH-521` | Go: the create answers 201 with Java's four members and `ETag: "0-ACTIVE"` (TOLERATED-DIFFERENCES.md, entry 9) | `paymentrequest_test.go` |
| `X9-PATCH-522` | Go: an accepted PATCH answers 200 with the new `ETag` and writes its audit row (TOLERATED-DIFFERENCES.md, entry 9) | `patch_test.go` |

## Payment requests pinned: create, PATCH, ETag, rebind (Story 4.2)

Black-box pins of the behaviour of plan §4.1 groups A, B, B2 and B3 (first pinned on the Java build), all in
`test_payment_requests_pinned.py`. Field-by-field refusals are `contract.py`'s; patching in each
status is `X9-MAP-002`.

| Key | What it proves | Where |
|---|---|---|
| `X9-RAIL-420` | an unsupported network is refused at create naming it: alone, beside a supported one, and a known rail in another case (`FedNow`) | `test_payment_requests_pinned.py` |
| `X9-CUR-420` | an unsupported currency (`EUR`) and a wrong-case one (`usd`, `Usdc`) are refused by name | `test_payment_requests_pinned.py` |
| `X9-CUR-421` | a payment method without a currency does not inherit the bill's: no default | `test_payment_requests_pinned.py` |
| `X9-CUR-422` | currencies from two peg groups (USD + BRL) are refused, the allow-list naming BRL first | `test_payment_requests_pinned.py` |
| `X9-CUR-423` | a duplicate currency is refused by name | `test_payment_requests_pinned.py` |
| `X9-LIFE-420` | a `validUntil` in the past or exactly now is refused | `test_payment_requests_pinned.py` |
| `X9-LIFE-421` | an invoice `dueDate` after `validUntil` is refused | `test_payment_requests_pinned.py` |
| `X9-LIFE-422` | a payment method's `validUntil` after the QR Code's is refused | `test_payment_requests_pinned.py` |
| `X9-TIP-420` | a tip offer that contradicts itself is refused: not allowed with a range or presets, allowed with neither | `test_payment_requests_pinned.py` |
| `X9-TIP-421` | a tip allowed with presets and no range is accepted (pinned) | `test_payment_requests_pinned.py` |
| `X9-AMT-420` | a negative amount is refused, in the bill or in the method alone | `test_payment_requests_pinned.py` |
| `X9-AMT-421` | 18 nines is accepted and read back to the unit; 10^18 (19 digits) is refused with 400 naming the field, nothing created | `test_payment_requests_pinned.py` |
| `X9-AMT-423` | a late fee that could pass 18 digits before validUntil is refused at create (bill, fee, method) and on PATCH, naming the field | `test_payment_requests_pinned.py` |
| `X9-AMT-422` | an amount past 2^63−1 is an unreadable body: 400 `malformed-json`, nothing created (pinned) | `test_payment_requests_pinned.py` |
| `X9-AMT-424` | an amount of `6.5`, `6.0` or `6e2`, in the bill or the payment method, is refused at create with 400 naming the field, nothing created | `test_payment_requests_pinned.py` |
| `X9-AMT-425` | a PATCH with a payment-method amount of `6.5` is refused with 400 naming the field; revision and ETag do not move | `test_payment_requests_pinned.py` |
| `X9-AMT-426` | a notified amount or tip amount of `6.5` is a signed 400 naming the field; the QR Code stays `ACTIVE` | `test_payment_notification.py` |
| `X9-LOC-425` | tag 26 fits in 99 characters, which limits the configured host to 37 | `test_payment_requests_pinned.py` |
| `X9-PATCH-420` | fields that are not patchable (creditor, status, id, revision, …), sent with values other than the stored ones, are refused by name and nothing applies | `test_payment_requests_pinned.py` |
| `X9-PATCH-421` | a patch carrying a creditor other than the stored one is refused naming it, and nothing moves | `test_payment_requests_pinned.py` |
| `X9-PATCH-422` | a no-op patch is refused and nothing moves | `test_payment_requests_pinned.py` |
| `X9-PATCH-423` | restating the bill unchanged counts as a change: 200, revision moves (pinned, not endorsed) | `test_payment_requests_pinned.py` |
| `X9-PATCH-424` | a patch may not add a currency to a single-currency QR Code | `test_payment_requests_pinned.py` |
| `X9-PATCH-425` | no status move (pre-commit, status-update, release, PAID, CANCELLED) moves the revision | `test_payment_requests_pinned.py` |
| `X9-PATCH-426` | every PATCH is held to the cross-field date rules on its merged state (`validUntil` before `dueDate`, before a method's, a method's after `validUntil`): 400 naming the field, nothing moves | `test_payment_requests_pinned.py` |
| `X9-PATCH-430` | the ETag is `"<revision>-<STATUS>"` | `test_payment_requests_pinned.py` |
| `X9-PATCH-431` | `If-Match` is accepted quoted, bare, `W/"…"` and `W/…`, on PATCH and status-update | `test_payment_requests_pinned.py` |
| `X9-PATCH-432` | a stale revision is 412 and nothing moves | `test_payment_requests_pinned.py` |
| `X9-PATCH-433` | a stale status is 412 and nothing moves | `test_payment_requests_pinned.py` |
| `X9-PATCH-434` | a malformed `If-Match` is 412; `*` is unconditional (pinned) | `test_payment_requests_pinned.py` |
| `X9-PATCH-435` | after a lapsed reservation the ETag reads `ACTIVE` and is accepted | `test_payment_requests_pinned.py` |
| `X9-LOC-420` | a `PAID` or `CANCELLED` holder of the same creditor gives up its location: fresh location, no event, the old one resolves the new code | `test_payment_requests_pinned.py` |
| `X9-LOC-421` | an expired `ACTIVE` holder gives up its location (ADR-0024; BUG-004 in the retired Java build) | `test_payment_requests_pinned.py` |
| `X9-LOC-422` | a live holder (`ACTIVE` in time, `PAYMENT_INITIATED`) keeps its location, on create and PATCH | `test_payment_requests_pinned.py` |
| `X9-LOC-423` | a different creditor never takes the location, on create and PATCH | `test_payment_requests_pinned.py` |
| `X9-LOC-424` | a refused create does not release the holder | `test_payment_requests_pinned.py` |

## Networks and rails

| Key | What it proves | Where |
|---|---|---|
| `X9-RAIL-001` | a destination address the QR Code never published is refused | `test_payment_notification.py` |
| `X9-RAIL-002` | blockchain data is refused on a bank rail | `test_payment_notification.py` |
| `X9-RAIL-120` | `paymentMethods` sent as a bare object is refused with 400 and no id | `test_create_refusals.py` |
| `X9-RAIL-501` | a network this deployment does not interpret is refused at create, naming it, with no id | `test_create_refusals.py` |
| `X9-RAIL-502` | an unknown field inside `solana` is refused at create, naming its path, with no id | `test_create_refusals.py` |
| `X9-RAIL-503` | an unknown field inside `solana` is refused on PATCH, naming its path, and the ETag does not move | `test_create_refusals.py` |
| `X9-RAIL-504` | an unknown field inside `fednow`, `rtp` or `ach` is refused at create, naming its path, with no id | `test_create_refusals.py` |
| `X9-RAIL-505` | an unknown field inside a bank rail is refused on PATCH, naming its path, and the ETag does not move | `test_create_refusals.py` |
| `X9-RAIL-506` | `protectionType` is read back as sent, on each bank rail, from GET and from the signed payload, for each allowed value | `test_payload.py` |
| `X9-RAIL-507` | an unknown field at any level of a create is refused in the same words on both builds, with no id | `test_closed_requests.py` |
| `X9-RAIL-508` | an unknown field at any level of a PATCH is refused in the same words on both builds; the ETag and revision do not move | `test_closed_requests.py` |
| `X9-RAIL-509` | an unknown field at any level of an outbound payment notification is refused in the same words on both builds | `test_closed_requests.py` |
| `X9-RAIL-510` | `paymentTiming` in another case (`Immediate`) is refused with 400 `malformed-json`, on create (nothing created) and on PATCH (nothing moves) | `test_payment_requests_pinned.py` |
| `X9-RAIL-511` | `protectionType` in another case (`Plaintext`) is refused at create with 400 `malformed-json`; `plaintext` is accepted | `test_payment_requests_pinned.py` |
| `X9-RAIL-520` | a pre-commit on a rail the QR Code does not offer is a signed 400 naming it, each rail on its own; nothing recorded | `test_payers.py` |
| `X9-RAIL-521` | a post-commit on an unoffered rail (RTP on FedNow-only) is a signed 400; one `payment.sent` records it flagged `NOT_OFFERED_RAIL`, the QR Code unchanged | `test_payers.py` |
| `X9-RAIL-522` | a post-commit offering neither rail nor currency is a signed 400 naming both; its `payment.sent` carries both codes, a `PAID` QR Code stays `PAID` | `test_payers.py` |

## Signatures, keys and problem details

| Key | What it proves | Where |
|---|---|---|
| `X9-SIG-001` | an unsigned body is refused | `test_payment_notification.py` |
| `X9-SIG-002` | an empty body is refused | `test_payment_notification.py` |
| `X9-SIG-003` | a payload edited after signing is refused | `test_payment_notification.py` |
| `X9-SIG-110` | a payer | `test_payment_notification.py` |
| `X9-SIG-111` | a payer | `test_payment_notification.py` |
| `X9-SIG-150` | a payee answering 302 or 307 is not followed: one delivery, the redirect reported as the answer | `test_payee_answers.py` |
| `X9-SIG-301` | the payee's refusal is reported as 200 with accepted false, changing nothing | `test_payers.py` |
| `X9-SIG-302` | an unreachable payee is 502, with no verdict | `test_payers.py` |
| `X9-SIG-303` | a pre-payment carrying a transactionId is refused before sending | `test_payers.py` |
| `X9-SIG-304` | a post-payment SENT needs a transactionId; NOT_SENT without one is delivered | `test_payers.py` |
| `X9-SIG-501` | the test payee holds the service's signed outbound question byte for byte, under the caller's correlationId | `test_payee_answers.py` |
| `X9-SIG-502` | each of the payee's answers (cases 1, 5-8, 11-16) is reported for what it is: signature, signatureProblem, statusCode, accepted | `test_payee_answers.py` |
| `X9-SIG-503` | an answer signed by an untrusted or a revoked certificate is INVALID, UNTRUSTED_CHAIN | `test_payee_answers.py` |
| `X9-SIG-504` | a payee that hangs up without answering is 502, whose detail says the payee may have received it, and nothing changes here | `test_payee_answers.py` |
| `X9-SIG-505` | a payee answering after the 15 s timeout is 502 | `test_payee_answers.py` |
| `X9-SIG-506` | each tampered payee answer is reported INVALID with the service's fixed code; a non-canonical correlationId, not the one sent, is refused 502 before the money moves | `test_payee_answers.py` |
| `X9-SIG-507` | the JWK set is served, each key with its x5c chain | `test_public_metadata.py` |
| `X9-SIG-508` | a test payer's token carries the X9.150 profile: ms iat/ttl, correlationId, crit, x5c, x5t#S256 | `test_signatures.py` |
| `X9-SIG-509` | both test-CA payers, RSA and EC, are accepted on their own keys | `test_signatures.py` |
| `X9-SIG-510` | each tampered payer notification is refused with its fixed code, and changes nothing | `test_signatures.py` |
| `X9-SIG-511` | the control of X9-SIG-510: the same message, signed honestly, is accepted | `test_signatures.py` |
| `X9-SIG-512` | a payer certificate revoked on the test CA's CRL is refused | `test_signatures.py` |
| `X9-SIG-513` | another payer's announcement, on its own key and with the same payer.info, is a 409 | `test_signatures.py` |
| `X9-SIG-514` | another payer's NOT_SENT is a 409, and the holder keeps the reservation | `test_signatures.py` |
| `X9-SIG-515` | another payer's post-commit is registered and changes nothing else | `test_signatures.py` |
| `X9-SIG-516` | a yes is a JWS signed by the payee that answers this question's correlationId | `test_signatures.py` |
| `X9-SIG-517` | a signed no for a wrong amount: 400, the question echoed, the field named | `test_signatures.py` |
| `X9-SIG-518` | a signed no for an unknown QR Code: 404 | `test_signatures.py` |
| `X9-SIG-519` | a signed no when another payer holds the reservation: 409 | `test_signatures.py` |
| `X9-SIG-520` | a signed no for a pre-commit on a CANCELLED QR Code: 400 | `test_signatures.py` |
| `X9-SIG-521` | a signed no for a pre-commit after validUntil by the payee's clock: 400 | `test_signatures.py` |
| `X9-SIG-522` | an unverifiable question gets a signed 401 under a fresh correlationId | `test_signatures.py` |
| `X9-SIG-523` | an unreadable question (not a JWS, or empty) is still answered signed, under a fresh correlationId | `test_signatures.py` |
| `X9-SIG-524` | the same signed question sent twice is answered twice (today's behaviour, pinned) | `test_signatures.py` |
| `X9-SIG-526` | a canonical `correlationId` written in capitals is accepted | `test_signatures.py` |
| `X9-SIG-527` | a payee answer whose body `statusCode` is not an integer, or exceeds 32 bits, is INVALID, `STATUS_MISSING` | `test_payee_answers.py` |
| `X9-SIG-528` | a typed query parameter that does not parse is 400 with `violations` an array naming it | `test_create_refusals.py` |
| `X9-SIG-529` | a pre-commit answer under another correlationId, or none, or replayed (Story 3.5 cases 2-4) is refused: 502, `failed to pay - payee is not responding (bad correlationId)`, one delivery (black box) | `test_payee_answers.py` |
| `X9-SIG-530` | a post-commit answer under another correlationId, or none, is followed by one more notification under a new correlationId, and its good answer is reported VERIFIED (black box) | `test_payee_answers.py` |
| `X9-SIG-531` | a post-commit answer under the wrong correlationId twice: exactly two notifications, the second answer reported INVALID, nothing else changed (black box) | `test_payee_answers.py` |
| `X9-SIG-532` | Go only: a create body with a lone surrogate escape (`"\ud800"`) or a date written as an array (`[2030,1,10]`) is 400 `malformed-json`; the well-formed control (`"A"`, `"2030-01-10"`) is created (ADR-0028 difference 7, black box) | `test_create_refusals.py` |
| `X9-SIG-533` | Go only: a payee answer over 1 MiB is 502, as if it had not answered, and nothing changes (ADR-0028 difference 15, black box) | `test_payee_answers.py` |
| `X9-SIG-534` | Go only: `signature/generate` signs a number as it was sent (`1e2`, `-2.50E+3`), and the token verifies (ADR-0028 difference 13) | `signature_test.go` |
| `X9-SIG-535` | Go only: every 500, a panic included, is the `internal-error` problem with no detail of its cause (TOLERATED-DIFFERENCES.md, entry 5) | `httpapi_test.go` |
| `X9-SIG-536` | Go only: a JSON body over 1 MiB is a 400 `malformed-json` (TOLERATED-DIFFERENCES.md, entry 6) | `httpapi_test.go` |
| `X9-SIG-537` | Go only: a certificate name other than `x9-key.pem` is a 404 (TOLERATED-DIFFERENCES.md, entry 11) | `signature_test.go` |
| `X9-SIG-538` | Go only: `signature/generate` refuses the nil UUID as a `Correlation-Id` (TOLERATED-DIFFERENCES.md, entry 12) | `signature_test.go` |

## Time edges (the test clock)

Plan §4.2 T1–T12, each at the instant it falls (Story 4.5, ADR-0033). "Just before" an edge means
judged within the 2 s before it; "1 ms after" is exact.

| Key | What it proves | Where |
|---|---|---|
| `X9-TIME-001` | T1: the discount is served, advertised until E and accepted up to E (exactly E via `dateForPayment`); 1 ms after, the face amount is served, the discount refused, the face amount accepted | `test_time_edges.py` |
| `X9-TIME-002` | T2: the holder's post-commit after E is recorded with a signed yes, not re-checked, and its event carries the amount reported | `test_time_edges.py` |
| `X9-TIME-003` | T3: no second edge at 23:59:59 UTC of E's day; the discount is neither offered nor accepted after E | `test_time_edges.py` |
| `X9-TIME-004` | T4: at exactly `dueDate` the full amount and no fee; 1 ms later the first day's fee | `test_time_edges.py` |
| `X9-TIME-005` | T5: the late fee rises every 24 h from `dueDate`; its advertised `validUntil` is the next boundary, capped by the QR Code's | `test_time_edges.py` |
| `X9-TIME-006` | T6: a pre-commit 1 ms after `validUntil` is refused, one just before the settlement margin (`validUntil` − 1 s) is accepted; the payee may still mark it PAID | `test_time_edges.py` |
| `X9-TIME-007` | T7: a payment method past its own `validUntil` is absent from the payload and refused; the other is served and accepted | `test_time_edges.py` |
| `X9-TIME-008` | T8: after a lapse the ETag reads ACTIVE and If-Match with it succeeds; another payer takes over with `payment.initiated`; the first payer's late SENT is registered, flagged | `test_time_edges.py` |
| `X9-TIME-010` | T9: a reservation taken 30 s before `validUntil` ends at `validUntil` | `test_time_edges.py` |
| `X9-TIME-011` | T10: a payer `iat` 10 min in the service's past with a 5-min `ttl` is 401 EXPIRED before any lookup | `test_time_edges.py` |
| `X9-TIME-012` | T11, both builds: an hour past `validUntil` on the test clock, the payload is refused as expired and GET still works | `test_time_edges.py` |
| `X9-TIME-014` | T11, Go only: an expired QR Code is kept and its location is releasable | `test_time_edges.py` |
| `X9-TIME-015` | T12, Go only: retention purges events, then QR Codes, by the test clock, and nothing younger | `test_time_edges.py` |
| `X9-TIME-016` | S4.5-AC2: with the payee's clock ahead of the payer's, the payee's clock decides `validUntil` | `test_time_edges.py` |
| `X9-TIME-017` | one instant: 1 ms past a method's `validUntil`, with `dateForPayment` that day, the expired method is absent and the other is priced one day late | `test_time_edges.py` |
| `X9-TIME-024` | the GET answer's `sentAt` is never before `revisedAt` (black-box, both builds) | `test_patch_merged_rules.py` |
| `X9-TIME-018` | a post-commit 1 ms after `validUntil` is accepted, signed, and registered with its hash | `test_time_edges.py` |
| `X9-TIME-019` | a pre-commit inside the settlement margin (after `validUntil` − 1 s, before `validUntil`) is refused on `validUntil` as too little time to settle, and reserves nothing (ADR-0041) | `test_time_edges.py` |
| `X9-TIME-501` | the test clock moves, reads back and resets, and every answer carries `X-Test-Clock-Offset` while it is moved | `test_clock.py` |
| `X9-TIME-502` | on the test clock, a payload stops being served once the clock passes `validUntil` (X9-LIFE-126 without sleeping) | `test_clock.py` |

## Golden sets (Story 4.7)

The JWS and EMV fixtures exported from Java for the Go build
([`others/blackbox/golden`](../others/blackbox/golden/README.md), ADR-0027), proved true offline
and, until 3.0.0, against the running Java build; since then against the Go build.

| Key | What it proves | Where |
|---|---|---|
| `X9-SIG-470` | every golden file is there with the manifest's SHA-256, and nothing else is | `test_golden.py` |
| `X9-SIG-471` | the golden JWS set covers what Story 4.7 names: valid, tampered, expired, `crit`, `typ`, revoked, untrusted, EC, Java's signed yes and refusals | `test_golden.py` |
| `X9-SIG-472` | the golden set carries bundled test certificates only, and no private key | `test_golden.py` |
| `X9-SIG-473` | the shipped trust anchor, chains and CRLs are valid at every case's `verifyAt`; the set is not due for regeneration | `test_golden.py` |
| `X9-SIG-474` | a second implementation, offline, accepts every case Java accepts and refuses every bad signature | `test_golden.py` |
| `X9-SIG-475` | with the clock at each case's `verifyAt`, Java gives every golden JWS the manifest's verdict and `signatureProblem` | `test_golden.py` |
| `X9-LOC-470` | each golden EMV string decodes to the manifest's fields, CRC and tag 26 | `test_golden.py` |
| `X9-LOC-471` | the golden EMV set has several hosts and locations, and a 37-character host whose tag 26 is exactly 99 | `test_golden.py` |
| `X9-LOC-472` | Java reads the location out of every golden EMV string | `test_golden.py` |
| `X9-LOC-473` | Java encodes each golden request of its own host again to the same characters | `test_golden.py` |

## Tips

| Key | What it proves | Where |
|---|---|---|
| `X9-TIP-101` | the bill plus a tip inside the range is accepted | `test_payment_notification.py` |
| `X9-TIP-102` | a tip taken out of the merchant's share is refused | `test_payment_notification.py` |
| `X9-TIP-103` | a tip on a bill that refuses tips is refused | `test_payment_notification.py` |
| `X9-TIP-104` | a tip outside the published range is refused | `test_payment_notification.py` |
| `X9-TIP-105` | a tip of zero is refused rather than ignored | `test_payment_notification.py` |
| `X9-TIP-106` | a negative tip is refused | `test_payment_notification.py` |
| `X9-TIP-107` | a transfer that is entirely tip is refused | `test_payment_notification.py` |
| `X9-TIP-108` | a tip larger than the transfer carrying it is refused | `test_payment_notification.py` |
| `X9-TIP-109` | presets do not bind when no range is published | `test_payment_notification.py` |
| `X9-TIP-110` | the tip percentage is taken against the merchant's share | `test_payment_notification.py` |
| `X9-TIP-111` | on an editable amount the percentage is of what is being paid | `test_payment_notification.py` |
| `X9-TIP-112` | a tip one minor unit outside its bound is tolerated | `test_payment_notification.py` |
| `X9-TIP-113` | a tip two minor units outside its bound is refused | `test_payment_notification.py` |
| `X9-TIP-501` | each tip form (none, refused, a range only) can be created | `test_tip_offers.py` |
| `X9-TIP-502` | a tip offer with neither a range nor presets is refused at create | `test_tip_offers.py` |
| `X9-TIP-503` | omitting the tip block is stored as tips refused | `test_tip_offers.py` |
| `X9-TIP-504` | the bill plus a tip is accepted | `test_tip_offers.py` |
| `X9-TIP-505` | a wrong tip is refused naming the tip, and reserves nothing | `test_tip_offers.py` |
| `X9-TIP-506` | a tip matching no preset is accepted when no range is published | `test_tip_offers.py` |

## Operations: metrics and the actuator (Story 14.1)

Go tests of what an operator's monitoring reads ([METRICS.md](METRICS.md), ADR-0031). The black-box
suite does not scrape `/actuator/prometheus` or `/actuator/info`: their values change on every run,
and the retired Java build's info answer was `{}`.

| Key | What it proves | Where |
|---|---|---|
| `X9-OPS-001` | Go: the running service serves every family METRICS.md marks kept, with its type and labels, its request timer by route template, the pool and runtime families, and `/actuator/info` | `actuator_test.go` |
| `X9-OPS-002` | Go: a request is timed under the Java build's family and labels, `uri` the route template (`/**` for an unrouted 404, `UNKNOWN` for a 405), never a raw id | `requests_test.go` |
| `X9-OPS-003` | Go: `http_server_requests_seconds_max` is Micrometer's two-minute time window | `requests_test.go` |
| `X9-OPS-004` | Go: the process and Go runtime families are served, each with its type | `requests_test.go` |
| `X9-OPS-005` | Go: every log line the level lets through is counted in `logback_events_total{level}`, the five levels present from the start | `requests_test.go` |
| `X9-OPS-006` | Go: `/actuator/info` answers name, version and commit, in Spring's build and git shape, with the actuator's media type | `info_test.go` |
| `X9-OPS-007` | Go: ADR-0031 and CONFIGURATION-FROM-JAVA.md agree on the actuator endpoints served, `/actuator/info` included | `config_test.go` |

## The black-box suite itself

The suite's own tooling: its client, its generators and the checks that keep it honest. These
prove nothing about the service; they prove the suite can be trusted to.

| Key | What it proves | Where |
|---|---|---|
| `X9-SUITE-510` | a constraint added to the contract gets its generated case; every kind is generated; no known gap is stale | `test_contract.py` |
| `X9-SUITE-501` | no black-box test signs as a payer through the service under test | `test_signatures.py` |
| `X9-SUITE-502` | every X9-MAP key in ADR-0035's map has a test in test_transition_map.py | `test_transition_map.py` |
| `X9-SUITE-503` | the suite's CRC-16/CCITT-FALSE gives the published check value | `test_x9client.py` |
| `X9-SUITE-504` | the suite's EMV decoder reads TLVs one template down and checks the CRC | `test_x9client.py` |
| `X9-SUITE-505` | the suite's EMV decoder refuses a length running past the end | `test_x9client.py` |
| `X9-SUITE-506` | the suite's HTTP client reads the status, the body, the ETag and problem+json | `test_x9client.py` |
| `X9-SUITE-507` | the suite's HTTP client reports nothing listening as status 0, not an exception | `test_x9client.py` |
| `X9-SUITE-508` | without X9_DATABASE_URL the suite has no inspector and states why | `test_x9client.py` |
| `X9-SUITE-509` | a database URL of an unknown scheme is a stated reason, not a crash | `test_x9client.py` |
| `X9-SUITE-511` | a clock nobody serves is reported unavailable, with the message the fixture skips on | `test_x9client.py` |
| `X9-SUITE-513` | the required-keys list reads test keys, node-id prefixes, `!` exclusions and comments, and refuses any other line by its number | `test_required_keys.py` |
| `X9-SUITE-514` | a listed test that fails, errors, is skipped, is an expected failure or never ran fails the run; a build-marker skip does not; an unlisted test never does | `test_required_keys.py` |
| `X9-SUITE-515` | a whole pytest run under `X9_REQUIRED_KEYS` exits on the listed tests alone, prints the counts of each outcome, and refuses an entry that matches no test | `test_required_keys.py` |
| `X9-SUITE-516` | with X9_RECORD_DIR set the suite's HTTP client writes each exchange of a test in order, with the headers that matter; unset, it writes nothing | `test_differential.py` |
| `X9-SUITE-517` | a recorded path keeps its shape with every server-minted id masked | `test_differential.py` |
| `X9-SUITE-518` | the differential run compares JSON as values: whitespace, key order, minted ids and the order of violations never count | `test_differential.py` |
| `X9-SUITE-519` | the differential run masks times and dates but keeps whether an offset is Z or numeric | `test_differential.py` |
| `X9-SUITE-520` | the differential run compares a JWS on its decoded header, minus per-run members and with `crit` as a set, and its decoded payload | `test_differential.py` |
| `X9-SUITE-521` | the differential run masks an EMV string's location id and CRC, also inside qrCodeB64 and quoted in a text, and catches any other change | `test_differential.py` |
| `X9-SUITE-522` | the differential run catches a changed status, violation text, ETag, Content-Type or Location, and a member added, dropped or retyped | `test_differential.py` |
| `X9-SUITE-523` | the differential run names a test or an exchange only one build has, keeps the rest aligned, counts a run of polls once and reads only the test's own events, in order per QR Code | `test_differential.py` |
| `X9-SUITE-524` | the differential run's command line prints the report, writes the JSON one, and exits 1 on any difference | `test_differential.py` |
| `X9-SUITE-525` | the tolerated list is read entry by entry, and one that cannot be applied as written is refused naming the entry | `test_differential.py` |
| `X9-SUITE-526` | a difference a tolerated entry matches passes, and any other still fails the run, as does an unnamed one-build test | `test_differential.py` |
| `X9-SUITE-527` | a tolerated selector that matched nothing is stale and fails the run, unless it may not occur or the entry is proved by a Go test | `test_differential.py` |
| `X9-SUITE-528` | a pending entry is tolerated and reported loudly; the command line exits 0, 1 or 2 | `test_differential.py` |
| `X9-SUITE-529` | the repository's TOLERATED-DIFFERENCES.md takes over every row of ADR-0028, each naming a test key | `test_differential.py` |
| `X9-SUITE-530` | a run of event polls counts as one exchange holding every event it read, each once, so which poll an event landed in never counts | `test_differential.py` |
| `X9-SUITE-531` | the tests marked `runs_last` (X9-TIME-015's retention purge) run after every other test of the session | `test_x9client.py` |
