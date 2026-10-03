# Test index

Every test, by key. The keys are stable: a bug report, an ADR or a message to an adopter can point
at one and still mean the same thing after the method has been renamed.

Each test's javadoc or docstring carries **Source** — whether the rule is the standard's, **ours**,
or a mechanism being pinned — and **Why** it matters. See [TEST-CONVENTIONS.md](TEST-CONVENTIONS.md).

**344 tests documented** — every JUnit test in the repository and every case in the
black-box suite.

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
| `X9-AMT-020` | two amounts with different values are not equal | `ValueObjectEqualityTest.java` |
| `X9-AMT-021` | two amounts with the same value are equal | `ValueObjectEqualityTest.java` |
| `X9-AMT-022` | different values give different hash codes | `ValueObjectEqualityTest.java` |
| `X9-AMT-023` | the same breakage applied to every text value object | `ValueObjectEqualityTest.java` |
| `X9-AMT-024` | different value object types are never equal, even wrapping the same value | `ValueObjectEqualityTest.java` |
| `X9-AMT-025` | a value object is not equal to null | `ValueObjectEqualityTest.java` |
| `X9-AMT-030` | a bill is created from its required values | `BillVOTest.java` |
| `X9-AMT-031` | a bill accepts its optional blocks | `BillVOTest.java` |
| `X9-AMT-032` | a bill cannot exist without a description | `BillVOTest.java` |
| `X9-AMT-033` | a bill cannot exist without an amount due | `BillVOTest.java` |
| `X9-AMT-034` | a bill cannot exist without a payment timing | `BillVOTest.java` |
| `X9-AMT-035` | a deferred bill must carry an invoice | `BillVOTest.java` |
| `X9-AMT-036` | an adjusted bill must carry an invoice | `BillVOTest.java` |
| `X9-AMT-037` | a discount cannot equal or exceed the amount due | `BillVOTest.java` |
| `X9-AMT-040` | the discounted amount is accepted while the window is open | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-041` | the face amount is accepted while a discount is available, because it overpays | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-042` | an amount calculated with an expired discount is refused | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-043` | the face amount is accepted once the discount window has closed | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-044` | the face amount is refused once a late fee has accrued | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-045` | the amount including the late fee is accepted | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-046` | a payment method whose own window closed is refused though the payload is still valid | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-047` | an expired payload is refused whatever the amount | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-050` | the same additionalInformation label may appear more than once | `AdditionalInformationApiTest.java` |
| `X9-AMT-051` | the order sent is the order kept | `AdditionalInformationApiTest.java` |
| `X9-AMT-052` | every line reaches the QR Code as stored | `AdditionalInformationApiTest.java` |
| `X9-AMT-053` | a patch also keeps repeated labels | `AdditionalInformationApiTest.java` |
| `X9-AMT-060` | an amount that is not the bill is refused over HTTP | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-AMT-061` | an underpayment is refused | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-AMT-070` | leniency about spelling is not leniency about the amount | `PayerNotificationLeniencyApiTest.java` |
| `X9-AMT-080` | an order without a date is accepted rather than crashing | `BillCreationDatesApiTest.java` |
| `X9-AMT-081` | an order raised in the past is accepted | `BillCreationDatesApiTest.java` |
| `X9-AMT-082` | an invoice issued in the past is accepted | `BillCreationDatesApiTest.java` |
| `X9-AMT-083` | an invoice issued long ago is still accepted | `BillCreationDatesApiTest.java` |
| `X9-AMT-084` | a due date in the past is still refused | `BillCreationDatesApiTest.java` |
| `X9-AMT-090` | a discounted amount is accepted while the window is open | `PaymentNotificationValidityWindowApiTest.java` |
| `X9-AMT-091` | an amount the payer chose within the published range is accepted | `PaymentNotificationValidityWindowApiTest.java` |
| `X9-AMT-092` | an amount below the published range is refused | `PaymentNotificationValidityWindowApiTest.java` |
| `X9-AMT-093` | an amount above the published range is refused | `PaymentNotificationValidityWindowApiTest.java` |
| `X9-AMT-100` | a discount applies to every pegged payment method | `RetrieveQRCodePayloadPaymentMethodMapperTest.java` |
| `X9-AMT-101` | a late fee applies to every pegged payment method | `RetrieveQRCodePayloadPaymentMethodMapperTest.java` |
| `X9-AMT-102` | no adjustment leaves every amount untouched | `RetrieveQRCodePayloadPaymentMethodMapperTest.java` |

## Currencies

| Key | What it proves | Where |
|---|---|---|
| `X9-CUR-001` | a currency the QR Code does not offer is refused | `test_payment_notification.py` |
| `X9-CUR-010` | a currency on the deployment's list passes | `AllowListSupportedCurrencyPolicyTest.java` |
| `X9-CUR-011` | surrounding whitespace is forgiven | `AllowListSupportedCurrencyPolicyTest.java` |
| `X9-CUR-012` | the wrong case is refused, naming the spelling to use | `AllowListSupportedCurrencyPolicyTest.java` |
| `X9-CUR-013` | a currency outside the list is refused, by name | `AllowListSupportedCurrencyPolicyTest.java` |
| `X9-CUR-014` | every offending currency is named at once | `AllowListSupportedCurrencyPolicyTest.java` |
| `X9-CUR-015` | blanks and nulls are ignored rather than refused | `AllowListSupportedCurrencyPolicyTest.java` |
| `X9-CUR-016` | an empty allow-list accepts anything | `AllowListSupportedCurrencyPolicyTest.java` |
| `X9-CUR-017` | a null allow-list behaves like an empty one | `AllowListSupportedCurrencyPolicyTest.java` |
| `X9-CUR-020` | currencies within one peg group may share a QR Code | `PeggedCurrencyMixPolicyTest.java` |
| `X9-CUR-021` | a single non-pegged currency alone is allowed | `PeggedCurrencyMixPolicyTest.java` |
| `X9-CUR-022` | a non-pegged currency mixed with a pegged one is refused | `PeggedCurrencyMixPolicyTest.java` |
| `X9-CUR-023` | two different non-pegged currencies are refused | `PeggedCurrencyMixPolicyTest.java` |
| `X9-CUR-024` | pegged currencies from different groups are refused | `PeggedCurrencyMixPolicyTest.java` |
| `X9-CUR-025` | peg groups are compared case-insensitively | `PeggedCurrencyMixPolicyTest.java` |
| `X9-CUR-026` | null and blank currencies are ignored | `PeggedCurrencyMixPolicyTest.java` |
| `X9-CUR-027` | several dollar-pegged tokens may share a request | `PeggedCurrencyMixPolicyTest.java` |
| `X9-CUR-028` | a dollar-pegged token still cannot join another group | `PeggedCurrencyMixPolicyTest.java` |
| `X9-CUR-030` | a notified currency matches whatever its case | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-CUR-031` | a currency this QR Code does not offer is still refused | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-CUR-040` | a currency this QR Code does not offer is refused over HTTP | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-CUR-050` | a notified currency is matched whatever its case | `PayerNotificationLeniencyApiTest.java` |
| `X9-CUR-051` | leniency about case is not leniency about currency | `PayerNotificationLeniencyApiTest.java` |
| `X9-CUR-060` | a stablecoin the configured rail settles is accepted | `SupportedCurrencyApiTest.java` |
| `X9-CUR-061` | the bank rails' own currency is accepted | `SupportedCurrencyApiTest.java` |
| `X9-CUR-062` | a currency no supported rail settles is refused | `SupportedCurrencyApiTest.java` |
| `X9-CUR-063` | a supported currency in the wrong case is refused at creation | `SupportedCurrencyApiTest.java` |
| `X9-CUR-070` | peg groups load from strict JSON as upper-cased sets | `CurrencyConfigurationTest.java` |

## The event stream

| Key | What it proves | Where |
|---|---|---|
| `X9-EVT-001` | a consumer watches a payment through to settlement | `PaymentEventConsumerFlowTest.java` |
| `X9-EVT-002` | events carry a stable id and a monotonic cursor | `PaymentEventConsumerFlowTest.java` |
| `X9-EVT-003` | a drained event is not drained twice | `PaymentEventConsumerFlowTest.java` |
| `X9-EVT-010` | the first instance to ask gets the drain lease | `PaymentEventDrainLockTest.java` |
| `X9-EVT-011` | a second instance is refused while the lease is held | `PaymentEventDrainLockTest.java` |
| `X9-EVT-012` | the holder may reacquire its own lease | `PaymentEventDrainLockTest.java` |
| `X9-EVT-013` | releasing hands the lease over | `PaymentEventDrainLockTest.java` |
| `X9-EVT-014` | an expired lease is taken over without release | `PaymentEventDrainLockTest.java` |
| `X9-EVT-020` | the event log expires rather than growing forever | `PaymentEventLogContractTest.java` |
| `X9-EVT-021` | both the announced and the reported amount reach the consumer | `PaymentEventLogContractTest.java` |
| `X9-EVT-030` | the payment event carries the tip the payer reported | `test_payment_notification.py` |
| `X9-EVT-031` | an event for a payment with no tip reports no tip | `test_payment_notification.py` |

## Lifecycle, status and delivery

| Key | What it proves | Where |
|---|---|---|
| `X9-LIFE-001` | a notification for an unknown QR Code is 404 | `test_payment_notification.py` |
| `X9-LIFE-002` | a second pre-payment from a DIFFERENT payer is refused | `test_payment_notification.py` |
| `X9-LIFE-003` | a payment on a cancelled QR Code is refused | `test_payment_notification.py` |
| `X9-LIFE-004` | a post-payment with no pre-payment is refused | `test_payment_notification.py` |
| `X9-LIFE-005` | a refused notification leaves the QR Code ACTIVE | `test_payment_notification.py` |
| `X9-LIFE-010` | a QR Code is created from valid data | `QRCodeEntityTest.java` |
| `X9-LIFE-011` | a stored QR Code is restored without re-running creation rules | `QRCodeEntityTest.java` |
| `X9-LIFE-012` | creation without an id generator is a programming error, not a business one | `QRCodeEntityTest.java` |
| `X9-LIFE-013` | a QR Code cannot be created without a creditor | `QRCodeEntityTest.java` |
| `X9-LIFE-014` | a QR Code cannot be created without a bill | `QRCodeEntityTest.java` |
| `X9-LIFE-015` | a QR Code cannot be created with no payment method | `QRCodeEntityTest.java` |
| `X9-LIFE-020` | a payment can be initiated on an ACTIVE QR Code | `QRCodeEntityInitiatePaymentTest.java` |
| `X9-LIFE-021` | initiating a payment moves revisedAt but not the revision | `QRCodeEntityInitiatePaymentTest.java` |
| `X9-LIFE-022` | a second initiation on a reserved QR Code is a conflict | `QRCodeEntityInitiatePaymentTest.java` |
| `X9-LIFE-023` | a paid QR Code cannot be initiated again | `QRCodeEntityInitiatePaymentTest.java` |
| `X9-LIFE-024` | a cancelled QR Code cannot be initiated | `QRCodeEntityInitiatePaymentTest.java` |
| `X9-LIFE-025` | a pre-payment may not carry settlement details | `QRCodeEntityInitiatePaymentTest.java` |
| `X9-LIFE-030` | a transition is applied when the entity tag still matches | `ConditionalStatusUpdateApiTest.java` |
| `X9-LIFE-031` | an update without If-Match is still unconditional | `ConditionalStatusUpdateApiTest.java` |
| `X9-LIFE-032` | If-Match: * means no condition | `ConditionalStatusUpdateApiTest.java` |
| `X9-LIFE-033` | a transition is refused when the QR Code changed after it was read | `ConditionalStatusUpdateApiTest.java` |
| `X9-LIFE-034` | the refusal reports what was actually found | `ConditionalStatusUpdateApiTest.java` |
| `X9-LIFE-035` | an unrecognised If-Match is refused rather than ignored | `ConditionalStatusUpdateApiTest.java` |
| `X9-LIFE-036` | a quoted or weak entity tag is accepted | `ConditionalStatusUpdateApiTest.java` |
| `X9-LIFE-037` | an illegal transition is 409, not 412 | `ConditionalStatusUpdateApiTest.java` |
| `X9-LIFE-038` | the ETag changes on a status change even though the revision does not | `RevisionTracksDataApiTest.java` |
| `X9-LIFE-039` | the ETag also changes on a data change | `RevisionTracksDataApiTest.java` |
| `X9-LIFE-040` | a fully valid notification is accepted | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-LIFE-041` | a payload fetched while valid cannot be paid once it expires | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-LIFE-042` | a QR Code already being paid is refused to a different payer | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-LIFE-043` | a cancelled QR Code is refused | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-LIFE-044` | an unknown QR Code is refused | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-LIFE-045` | a QR Code created without a paymentNotification refuses notifications | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-LIFE-046` | a QR Code naming an external endpoint refuses notifications here | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-LIFE-050` | a payment can be initiated on an ACTIVE QR Code over HTTP | `StatusUpdatePaymentInitiatedApiTest.java` |
| `X9-LIFE-051` | the reservation is visible on subsequent reads | `StatusUpdatePaymentInitiatedApiTest.java` |
| `X9-LIFE-053` | an already-paid QR Code cannot be initiated | `StatusUpdatePaymentInitiatedApiTest.java` |
| `X9-LIFE-054` | a cancelled QR Code cannot be initiated | `StatusUpdatePaymentInitiatedApiTest.java` |
| `X9-LIFE-055` | a reserved QR Code can be reactivated | `StatusUpdatePaymentInitiatedApiTest.java` |
| `X9-LIFE-056` | a pre-payment may not carry settlement details | `StatusUpdatePaymentInitiatedApiTest.java` |
| `X9-LIFE-060` | a pre-commit notification takes the QR Code out of circulation | `SolanaTwoPhaseNotificationApiTest.java` |
| `X9-LIFE-061` | a notification without the optional expectedDate is accepted | `SolanaTwoPhaseNotificationApiTest.java` |
| `X9-LIFE-062` | a post-commit notification reports without settling | `SolanaTwoPhaseNotificationApiTest.java` |
| `X9-LIFE-063` | a failed payment is published as such | `SolanaTwoPhaseNotificationApiTest.java` |
| `X9-LIFE-064` | a SENT notification without a transaction is refused | `SolanaTwoPhaseNotificationApiTest.java` |
| `X9-LIFE-070` | an accepted pre-payment comes back accepted | `OutboundPaymentNotificationApiTest.java` |
| `X9-LIFE-071` | a refused pre-payment is an answer, not an error | `OutboundPaymentNotificationApiTest.java` |
| `X9-LIFE-072` | an unreachable payee is a gateway failure | `OutboundPaymentNotificationApiTest.java` |
| `X9-LIFE-073` | a pre-payment carrying a transaction id is refused before it is sent | `OutboundPaymentNotificationApiTest.java` |
| `X9-LIFE-074` | a post-payment without a transaction id is refused before it is sent | `OutboundPaymentNotificationApiTest.java` |
| `X9-LIFE-075` | a post-payment carrying its reference is delivered | `OutboundPaymentNotificationApiTest.java` |
| `X9-LIFE-080` | a transient write conflict is retried until it succeeds | `TransientTransactionRetryTest.java` |
| `X9-LIFE-081` | the transient label is found through the cause chain | `TransientTransactionRetryTest.java` |
| `X9-LIFE-082` | a duplicate key is not retried | `TransientTransactionRetryTest.java` |
| `X9-LIFE-083` | an ordinary business failure is not retried | `TransientTransactionRetryTest.java` |
| `X9-LIFE-084` | a duplicate key is not mistaken for transient | `TransientTransactionRetryTest.java` |
| `X9-LIFE-085` | attempts are bounded and the original failure survives | `TransientTransactionRetryTest.java` |
| `X9-LIFE-086` | the retry wraps the transaction rather than running inside it | `TransientTransactionRetryTest.java` |
| `X9-LIFE-087` | the retry advice wraps the transaction advice | `TransientTransactionRetryWiringTest.java` |
| `X9-LIFE-100` | a payment request is created | `QRCodesApisFlowTest.java` |
| `X9-LIFE-101` | paymentTiming is read in any case and emitted in the spec's spelling | `QRCodesApisFlowTest.java` |
| `X9-LIFE-102` | a status transition is applied | `QRCodesApisFlowTest.java` |
| `X9-LIFE-103` | the new status is visible on a subsequent read | `QRCodesApisFlowTest.java` |
| `X9-LIFE-104` | a payment notification completes the cycle | `QRCodesApisFlowTest.java` |
| `X9-LIFE-110` | a reservation inside its window still holds | `QRCodeEntityReservationExpiryTest.java` |
| `X9-LIFE-111` | a reservation past its window reads as ACTIVE | `QRCodeEntityReservationExpiryTest.java` |
| `X9-LIFE-112` | the stored status is left alone when a reservation lapses | `QRCodeEntityReservationExpiryTest.java` |
| `X9-LIFE-113` | a lapsed reservation lets the next payer in | `QRCodeEntityReservationExpiryTest.java` |
| `X9-LIFE-114` | a reservation inside its window keeps the next payer out | `QRCodeEntityReservationExpiryTest.java` |
| `X9-LIFE-115` | the window never outlives the QR Code | `QRCodeEntityReservationExpiryTest.java` |
| `X9-LIFE-116` | the stamp is cleared when the reservation ends | `QRCodeEntityReservationExpiryTest.java` |
| `X9-LIFE-117` | a terminal status is never reinterpreted | `QRCodeEntityReservationExpiryTest.java` |
| `X9-LIFE-118` | a reservation with no stamp is treated as holding | `QRCodeEntityReservationExpiryTest.java` |
| `X9-LIFE-120` | the payee may settle an ACTIVE bill | `test_payment_notification.py` |
| `X9-LIFE-121` | the payee may settle a bill a payer has reserved | `test_payment_notification.py` |
| `X9-LIFE-122` | the payee may settle a bill whose validUntil has passed | `test_payment_notification.py` |
| `X9-LIFE-123` | a cancelled bill cannot be marked paid | `test_payment_notification.py` |
| `X9-LIFE-124` | once PAID, payers are refused | `test_payment_notification.py` |
| `X9-LIFE-125` | asking for the status it already has is an idempotent no-op | `StatusUpdatePaymentInitiatedApiTest.java` |
| `X9-LIFE-126` | a payer cannot fetch the payload of an expired QR Code | `test_payment_notification.py` |
| `X9-LIFE-127` | X to X is accepted, on every status | `test_payment_notification.py` |

### Who holds a reservation

Only the party that announced a payment may re-announce it, report it, or give it back. Identity is
`payer.info` paired with the subject of the certificate that signed the JWS — see ADR-0021.

| Key | What it proves | Where |
|---|---|---|
| `X9-HOLD-001` | the same payer at the same institution is the same party | `ReservationHolderVOTest.java` |
| `X9-HOLD-002` | a different payer at the same institution is a different party | `ReservationHolderVOTest.java` |
| `X9-HOLD-003` | the same payer value from a different institution is a different party | `ReservationHolderVOTest.java` |
| `X9-HOLD-004` | an institution with no payer info matches itself | `ReservationHolderVOTest.java` |
| `X9-HOLD-005` | nothing matches a holder nobody can name | `ReservationHolderVOTest.java` |
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
| `X9-HOLD-030` | a repeated announcement moves the window | `ReservationRefreshApiTest.java` |
| `X9-HOLD-031` | without a repeat, the same wait releases it | `ReservationRefreshApiTest.java` |

## Locations and payload retrieval

| Key | What it proves | Where |
|---|---|---|
| `X9-LOC-001` | the content this QR Code was issued with is accepted | `QRCodeEntityIssuedContentTest.java` |
| `X9-LOC-002` | content that is not the issued one is refused | `QRCodeEntityIssuedContentTest.java` |
| `X9-LOC-003` | content differing by a single character is refused | `QRCodeEntityIssuedContentTest.java` |
| `X9-LOC-004` | an absent submitted content is not this rule's business | `QRCodeEntityIssuedContentTest.java` |
| `X9-LOC-005` | a QR Code holding no content yet accepts nothing | `QRCodeEntityIssuedContentTest.java` |
| `X9-LOC-010` | the new QR Code actually takes over the location | `LocationHandoverApiTest.java` |
| `X9-LOC-011` | the printed image survives and serves the new amount | `LocationHandoverApiTest.java` |
| `X9-LOC-012` | moving the location is not a no-op even when the amounts are unchanged | `LocationHandoverApiTest.java` |
| `X9-LOC-013` | a location held by a live QR Code is not handed over | `LocationHandoverApiTest.java` |
| `X9-LOC-020` | a payload request naming another location is refused | `JwsQRCodeSignatureServiceTest.java` |
| `X9-LOC-030` | the payload URL is extracted from EMV tag 26 | `MateraAdoptQRCodeEMVServiceTest.java` |
| `X9-LOC-040` | a location held by a live QR Code cannot be claimed at creation | `QRCodesApisFlowTest.java` |
| `X9-LOC-041` | the payload is retrieved by location, signed | `QRCodesApisFlowTest.java` |
| `X9-LOC-042` | a location is reused once its holder is no longer live | `QRCodesApisFlowTest.java` |

### What the payer is told when a decode is refused

The decoder calls `/pub/api/v1/loc/{id}` on the payer's behalf. Every refusal used to arrive as one
string, with an internal address attached, so a settled bill, a withdrawn one, a lapsed one, a
tampered one and an outage were indistinguishable — and four of the five invite a retry.

| Key | What it proves | Where |
|---|---|---|
| `X9-DEC-001` | a decode of a paid bill says it was paid | `DecoderRelaysTheRefusalApiTest.java` |
| `X9-DEC-002` | a decode of a cancelled bill says the biller withdrew it | `DecoderRelaysTheRefusalApiTest.java` |
| `X9-DEC-003` | a decode of a tampered code says the content does not match | `DecoderRelaysTheRefusalApiTest.java` |
| `X9-DEC-004` | no refusal carries this deployment's internal address | `DecoderRelaysTheRefusalApiTest.java` |
| `X9-DEC-005` | a payee that cannot be reached is a 502, not a refusal | `DecoderRelaysTheRefusalApiTest.java` |
| `X9-DEC-006` | a decode of an expired code says it expired | `test_payment_notification.py` |
| `X9-DEC-007` | the most ordinary refusal reaches the payer intact | `test_payment_notification.py` |
| `X9-DEC-008` | a relayed reason cannot carry a link | `RelayedRefusalIsSafeToRenderTest.java` |
| `X9-DEC-009` | a bare domain is a link too | `RelayedRefusalIsSafeToRenderTest.java` |
| `X9-DEC-010` | an ordinary reason passes through untouched | `RelayedRefusalIsSafeToRenderTest.java` |
| `X9-DEC-011` | control characters do not survive | `RelayedRefusalIsSafeToRenderTest.java` |
| `X9-DEC-012` | a reason reduced to nothing still says something | `RelayedRefusalIsSafeToRenderTest.java` |

## Editing a payment request

| Key | What it proves | Where |
|---|---|---|
| `X9-PATCH-001` | naming every currency the QR Code offers is accepted | `QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-002` | the order currencies are named in does not matter | `QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-003` | a currency the QR Code does not offer is refused, by name | `QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-004` | a currency left out of the patch is refused, by name | `QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-005` | a patch naming only an unknown currency is refused | `QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-006` | naming no currency at all is not this rule's business | `QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-010` | a status change does not create a new revision | `RevisionTracksDataApiTest.java` |
| `X9-PATCH-011` | several status changes still do not create a revision | `RevisionTracksDataApiTest.java` |
| `X9-PATCH-012` | a data change creates a new revision | `RevisionTracksDataApiTest.java` |
| `X9-PATCH-013` | each data change counts once | `RevisionTracksDataApiTest.java` |
| `X9-PATCH-014` | a patch is refused when the QR Code changed after it was read | `RevisionTracksDataApiTest.java` |
| `X9-PATCH-015` | a patch is applied when the tag still matches | `RevisionTracksDataApiTest.java` |
| `X9-PATCH-016` | a patch without If-Match is still unconditional | `RevisionTracksDataApiTest.java` |
| `X9-PATCH-020` | the amount alone can be reduced | `PatchAmountOnlyApiTest.java` |
| `X9-PATCH-021` | the memo and recipient survive an amount-only patch | `PatchAmountOnlyApiTest.java` |
| `X9-PATCH-022` | the amount can be reduced a second time | `PatchAmountOnlyApiTest.java` |
| `X9-PATCH-023` | a patch that changes nothing is still refused | `PatchAmountOnlyApiTest.java` |
| `X9-PATCH-030` | a payment request is edited | `QRCodesApisFlowTest.java` |
| `X9-PATCH-031` | the edit is visible on a subsequent read | `QRCodesApisFlowTest.java` |

## Networks and rails

| Key | What it proves | Where |
|---|---|---|
| `X9-RAIL-001` | a destination address the QR Code never published is refused | `test_payment_notification.py` |
| `X9-RAIL-002` | blockchain data is refused on a bank rail | `test_payment_notification.py` |
| `X9-RAIL-010` | only rails with a published shape are enumerated | `NetworksVORailLookupTest.java` |
| `X9-RAIL-011` | the standard's own rails are not blockchains | `NetworksVORailLookupTest.java` |
| `X9-RAIL-012` | Solana is classified as a blockchain | `NetworksVORailLookupTest.java` |
| `X9-RAIL-013` | a notified network resolves whatever its case | `NetworksVORailLookupTest.java` |
| `X9-RAIL-014` | every rail resolves from its own value | `NetworksVORailLookupTest.java` |
| `X9-RAIL-015` | rail support is checked case-insensitively | `NetworksVORailLookupTest.java` |
| `X9-RAIL-016` | an unlisted network does not resolve to an interpreted rail | `NetworksVORailLookupTest.java` |
| `X9-RAIL-017` | a network we do not interpret is still carried, by name | `NetworksVORailLookupTest.java` |
| `X9-RAIL-018` | offering one network does not thereby offer another | `NetworksVORailLookupTest.java` |
| `X9-RAIL-019` | bank rails carry no destination address | `NetworksVORailLookupTest.java` |
| `X9-RAIL-030` | a rail this QR Code does not offer is refused | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-RAIL-040` | a Solana method round-trips with both published fields | `SolanaPaymentMethodApiTest.java` |
| `X9-RAIL-041` | the Solana memo is optional | `SolanaPaymentMethodApiTest.java` |
| `X9-RAIL-042` | the memo is carried, never composed by us | `SolanaPaymentMethodApiTest.java` |
| `X9-RAIL-043` | a Solana method without a recipient is refused | `SolanaPaymentMethodApiTest.java` |
| `X9-RAIL-044` | the field name we once guessed is refused | `SolanaPaymentMethodApiTest.java` |
| `X9-RAIL-045` | a recipient that is not base58 of the right length is refused | `SolanaPaymentMethodApiTest.java` |
| `X9-RAIL-046` | a 43-character recipient is accepted | `SolanaPaymentMethodApiTest.java` |
| `X9-RAIL-047` | a memo beyond its published limit is refused | `SolanaPaymentMethodApiTest.java` |
| `X9-RAIL-050` | the configured spelling is accepted | `NetworkNamingApiTest.java` |
| `X9-RAIL-051` | a standard rail under any other spelling is refused at creation | `NetworkNamingApiTest.java` |
| `X9-RAIL-052` | an unsupported network is refused, by name | `NetworkNamingApiTest.java` |
| `X9-RAIL-053` | an unsupported network is not silently dropped | `NetworkNamingApiTest.java` |
| `X9-RAIL-054` | a patch may not introduce an unsupported network | `NetworkNamingApiTest.java` |
| `X9-RAIL-060` | a payload offering an uninterpreted rail still decodes | `OpaqueNetworkOnDecodeApiTest.java` |
| `X9-RAIL-061` | a rail we do interpret is unaffected by opacity | `OpaqueNetworkOnDecodeApiTest.java` |
| `X9-RAIL-062` | an unknown network arrives with every field intact | `OpaqueNetworkOnDecodeApiTest.java` |
| `X9-RAIL-063` | an unknown network of any shape survives | `OpaqueNetworkOnDecodeApiTest.java` |
| `X9-RAIL-064` | an unknown network keeps its own key spelling | `OpaqueNetworkOnDecodeApiTest.java` |
| `X9-RAIL-070` | a notified rail is matched whatever its case | `PayerNotificationLeniencyApiTest.java` |
| `X9-RAIL-071` | the notified value is echoed back verbatim | `PayerNotificationLeniencyApiTest.java` |
| `X9-RAIL-072` | leniency about case is not leniency about rail | `PayerNotificationLeniencyApiTest.java` |
| `X9-RAIL-073` | an uninterpreted network is still refused on a notification | `PayerNotificationLeniencyApiTest.java` |
| `X9-RAIL-080` | an alphanumeric bank account number is accepted | `BankAccountNumberApiTest.java` |
| `X9-RAIL-081` | anything outside digits and letters is refused | `BankAccountNumberApiTest.java` |
| `X9-RAIL-090` | a destination this QR Code never published is refused | `SolanaTwoPhaseNotificationApiTest.java` |
| `X9-RAIL-100` | an ACH notification takes the QR Code out of circulation | `PaymentNotificationDispatchApiTest.java` |
| `X9-RAIL-101` | a FedNow notification is recorded without settling | `PaymentNotificationDispatchApiTest.java` |
| `X9-RAIL-102` | a rail the QR Code does not offer is refused | `PaymentNotificationDispatchApiTest.java` |
| `X9-RAIL-103` | an uninterpreted network is rejected, not ignored | `PaymentNotificationDispatchApiTest.java` |
| `X9-RAIL-110` | the full create-and-settle flow works on every interpreted rail | `QRCodesApisFlowTest.java` |
| `X9-RAIL-111` | a payment notification is accepted on every interpreted rail | `QRCodesApisFlowTest.java` |

## Signatures, keys and problem details

| Key | What it proves | Where |
|---|---|---|
| `X9-SIG-001` | an unsigned body is refused | `test_payment_notification.py` |
| `X9-SIG-002` | an empty body is refused | `test_payment_notification.py` |
| `X9-SIG-003` | a payload edited after signing is refused | `test_payment_notification.py` |
| `X9-SIG-010` | an unsigned body is refused | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-SIG-011` | a tampered signature is refused | `PaymentNotificationAcceptanceApiTest.java` |
| `X9-SIG-020` | problem detail extension members sit at the top level | `ProblemDetailShapeApiTest.java` |
| `X9-SIG-021` | extension members are not nested under a properties object | `ProblemDetailShapeApiTest.java` |
| `X9-SIG-022` | the standard problem detail members are kept | `ProblemDetailShapeApiTest.java` |
| `X9-SIG-030` | what arrives at the payee is a signed JWS | `OutboundPaymentNotificationApiTest.java` |
| `X9-SIG-031` | the id travels inside the payment object, where the standard puts it | `OutboundPaymentNotificationApiTest.java` |
| `X9-SIG-032` | a payee can read what we sent using the contract type | `OutboundPaymentNotificationApiTest.java` |
| `X9-SIG-033` | a relative notification endpoint is refused | `OutboundPaymentNotificationApiTest.java` |
| `X9-SIG-040` | the service signs with its EC key | `EcSigningIdentityApiTest.java` |
| `X9-SIG-041` | that signature verifies against our published certificate | `EcSigningIdentityApiTest.java` |
| `X9-SIG-042` | the published JWK set describes an EC key | `EcSigningIdentityApiTest.java` |
| `X9-SIG-050` | an RSA payer issued by a trusted CA is accepted | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-052` | a payer from an untrusted CA is refused | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-053` | a revoked payer is refused | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-054` | an EC payer can fetch the payload | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-060` | a refusal is logged as a refusal | `RequestLoggerFilterStatusTest.java` |
| `X9-SIG-061` | a failure is logged as a failure | `RequestLoggerFilterStatusTest.java` |
| `X9-SIG-062` | a success is still logged as a success | `RequestLoggerFilterStatusTest.java` |
| `X9-SIG-063` | an exception escaping the chain is logged as a 500 | `RequestLoggerFilterStatusTest.java` |
| `X9-SIG-070` | signing emits every X9 header | `JwsQRCodeSignatureServiceTest.java` |
| `X9-SIG-071` | a signature we produced validates | `JwsQRCodeSignatureServiceTest.java` |
| `X9-SIG-072` | a signed response carries its status code | `JwsQRCodeSignatureServiceTest.java` |
| `X9-SIG-073` | signing without a correlation id is refused | `JwsQRCodeSignatureServiceTest.java` |
| `X9-SIG-074` | signing with an invalid TTL is refused | `JwsQRCodeSignatureServiceTest.java` |
| `X9-SIG-075` | the signing certificate is served | `JwsQRCodeSignatureServiceTest.java` |
| `X9-SIG-076` | the JWK set is served | `JwsQRCodeSignatureServiceTest.java` |
| `X9-SIG-080` | no GCM secret-key property is set | `JasyptStaysOffTheGcmPathTest.java` |
| `X9-SIG-081` | the configured algorithm is the PBE one | `JasyptStaysOffTheGcmPathTest.java` |
| `X9-SIG-090` | an EC identity produces an EC JWK and an ECDSA signer | `JwkSetFacadeBeanKeyTypeTest.java` |
| `X9-SIG-091` | an RSA identity still produces an RSA JWK and an RSASSA signer | `JwkSetFacadeBeanKeyTypeTest.java` |
| `X9-SIG-092` | an algorithm the key cannot produce fails immediately, and says why | `JwkSetFacadeBeanKeyTypeTest.java` |
| `X9-SIG-093` | the same guard catches an EC algorithm on an RSA key | `JwkSetFacadeBeanKeyTypeTest.java` |
| `X9-SIG-100` | an EMV payload decodes and verifies against the published certificate | `QRCodesApisFlowTest.java` |
| `X9-SIG-101` | signing and payload retrieval work on every interpreted rail | `QRCodesApisFlowTest.java` |
| `X9-SIG-110` | a payer | `test_payment_notification.py` |
| `X9-SIG-111` | a payer | `test_payment_notification.py` |

## Tips

| Key | What it proves | Where |
|---|---|---|
| `X9-TIP-001` | the bill plus a tip is accepted | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-002` | a tip paid out of the merchant's share is refused | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-003` | a bill with no tip is unaffected | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-004` | a tip larger than the transfer carrying it is refused | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-005` | a tip on a bill that refuses tips is refused | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-006` | a bill cannot exist without tip information | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-007` | a tip inside the published range is accepted | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-008` | a tip above the published maximum is refused | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-009` | a tip below the published minimum is refused | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-010` | an absurd tip is refused rather than accepted | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-011` | presets do not bind when no range is published | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-012` | a tip matching no preset is still bound by the range | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-013` | on an editable amount the percentage is of what is being paid | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-014` | a tip one minor unit outside its bound is tolerated | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-015` | a tip two minor units outside its bound is refused | `PaymentNotificationTipPolicyTest.java` |
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
| `X9-TIP-120` | a malformed tip is refused at creation | `QRCodesApisFlowTest.java` |
| `X9-TIP-121` | a bill can be created with no tip block | `QRCodesApisFlowTest.java` |
| `X9-TIP-122` | a bill created without a tip reports tips as not allowed | `QRCodesApisFlowTest.java` |
