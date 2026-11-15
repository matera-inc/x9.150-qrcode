# Test index

Every test, by key. The keys are stable: a bug report, an ADR or a message to an adopter can point
at one and still mean the same thing after the method has been renamed.

Each test's javadoc or docstring carries **Source** — whether the rule is the standard's, **ours**,
or a mechanism being pinned — and **Why** it matters. See [TEST-CONVENTIONS.md](TEST-CONVENTIONS.md).

**775 tests documented** — every JUnit test and every black-box case.

**From a rule to its tests:** [RULE-COVERAGE.md](RULE-COVERAGE.md) lists every documented rule
(STATE-MACHINE, ENDPOINTS, INTERPRETATION, ADRs 0004 to 0021, ADR-0035's map, ADR-0036 and ADR-0027's EMV rules), the
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
| `X9-AMT-110` | the largest 18-digit amount is accepted and kept to the unit | `AmountVOTest.java` |
| `X9-AMT-111` | an amount of 19 digits is refused, though a 64-bit integer holds it | `AmountVOTest.java` |
| `X9-AMT-112` | a late fee or discount of 19 digits is refused, as every amount is | `AmountVOTest.java` |
| `X9-AMT-113` | the largest late fee is fixed + perDay × days late at validUntil, counted as the formula counts | `QRCodeEntityLateFeeBoundTest.java` |
| `X9-AMT-114` | the bill plus its largest late fee may reach 18 nines; one more is refused naming `bill.amountDue.amount` | `QRCodeEntityLateFeeBoundTest.java` |
| `X9-AMT-115` | every payment method is bounded as the payload would show it with the largest fee, naming `paymentMethods[i].amount` | `QRCodeEntityLateFeeBoundTest.java` |
| `X9-AMT-116` | a late fee that would overflow a 64-bit integer is refused, never wrapped | `QRCodeEntityLateFeeBoundTest.java` |
| `X9-AMT-117` | the late-fee bound is checked against the merged state on PATCH | `QRCodeEntityLateFeeBoundTest.java` |
| `X9-AMT-118` | the late fee stops growing at the QR Code's validUntil | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-119` | the formula refuses an amount past 18 digits and never wraps | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-120` | 18 nines is accepted at creation and read back to the unit | `AmountEighteenDigitsApiTest.java` |
| `X9-AMT-121` | a 19-digit amount is refused at creation, naming the field | `AmountEighteenDigitsApiTest.java` |
| `X9-AMT-122` | a 19-digit amount is refused by PATCH, naming the field, and nothing moves | `AmountEighteenDigitsApiTest.java` |
| `X9-AMT-123` | a payer cannot send a 19-digit amount or tip amount to a payee | `AmountEighteenDigitsApiTest.java` |
| `X9-AMT-124` | at creation, the bill plus its largest late fee is bounded at 18 nines, refused naming the field | `LateFeeEighteenDigitsApiTest.java` |
| `X9-AMT-125` | at creation, each payment method is bounded with the largest late fee, refused naming the field | `LateFeeEighteenDigitsApiTest.java` |
| `X9-AMT-126` | a PATCH with a later validUntil, a larger fee or a larger amount past the bound is refused, revision unmoved | `LateFeeEighteenDigitsApiTest.java` |
| `X9-AMT-520` | a bill with no late-fee adjustment is not bounded by the 18-digit late-fee rule, at any amount | `QRCodeEntityLateFeeBoundTest.java` |
| `X9-AMT-501` | a payment at exactly the due date is on time: the full amount, no late fee | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-502` | the due date is on time with no discount configured too | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-503` | 1 ms before the due date is the full amount when no discount covers it | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-504` | 1 ms after the due date the first day's late fee is owed | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-505` | a discount is advertised until exactly the instant it stops applying | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-506` | a discount applies at its edge and not 1 ms after | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-507` | of several discounts the largest eligible is served, until its own edge | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-508` | the largest eligible discount wins even when a smaller one would last longer | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-509` | a late fee is advertised until the next 24-hour boundary from the due date | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-510` | a late fee applies at its boundary and rises 1 ms after | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-511` | the advertised validUntil is capped by whatever ends sooner | `FixedDiscountLateFeeLinearInterestFormulaServiceTest.java` |
| `X9-AMT-512` | a discount fetched before its window closed is refused when the request arrives after it | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-513` | a request arriving after validUntil on the payee's clock is refused | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-514` | a request arriving at exactly validUntil is accepted | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-515` | a request arriving at exactly the due date owes the full amount | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-516` | the discounted amount is accepted at its advertised edge and refused 1 ms after | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-517` | the late-fee amount is accepted at its advertised boundary and refused 1 ms after | `PaymentNotificationAcceptancePolicyTest.java` |
| `X9-AMT-518` | over HTTP, the discount is advertised until exactly the instant it stops applying | `PaymentNotificationValidityWindowApiTest.java` |
| `X9-AMT-519` | over HTTP, the advertised discount edge is capped by the payment method's validUntil | `PaymentNotificationValidityWindowApiTest.java` |

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
| `X9-CUR-501` | a currency outside supported-currencies.json is refused at create, naming it, with no id | `test_create_refusals.py` |

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
| `X9-EVT-501` | payment.initiated then payment.sent are published in order; a refusal publishes nothing | `test_two_phase.py` |
| `X9-EVT-502` | every event has its own eventId | `test_two_phase.py` |
| `X9-EVT-503` | payment.sent carries the transaction hash the payer reported | `test_two_phase.py` |
| `X9-EVT-504` | reading on from the cursor returns none of the events already read | `test_two_phase.py` |

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
| `X9-LIFE-047` | after a reservation lapses, a PATCH carrying the ETag GET gave succeeds | `ETagAfterLapsedReservationApiTest.java` |
| `X9-LIFE-048` | after a lapse, a stale tag is still refused with 412 | `ETagAfterLapsedReservationApiTest.java` |
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
| `X9-LIFE-076` | a payee whose headers arrive after the 15 s timeout is a gateway failure | `OutboundPaymentNotificationApiTest.java` |
| `X9-LIFE-077` | a payee whose body trickles past the 15 s timeout is a gateway failure | `OutboundPaymentNotificationApiTest.java` |
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
| `X9-LIFE-128` | the entity tag of a lapsed reservation carries the effective status | `QRCodeEntityEntityTagTest.java` |
| `X9-LIFE-129` | If-Match accepts the tag GET gave for a lapsed reservation | `QRCodeEntityEntityTagTest.java` |
| `X9-LIFE-130` | a tag naming another revision or the stored status is still refused | `QRCodeEntityEntityTagTest.java` |
| `X9-LIFE-140` | an absent bill.paymentTiming on create defaults to immediate | `DefaultedBillFieldsApiTest.java` |
| `X9-LIFE-141` | an explicit null bill.paymentTiming on create is refused | `DefaultedBillFieldsApiTest.java` |
| `X9-LIFE-142` | a status-update PAYMENT_INITIATED after the reservation lapsed reserves the QR Code again, with payment.initiated | `StatusUpdateAfterLapsedReservationApiTest.java` |
| `X9-LIFE-143` | a repeated status-update PAYMENT_INITIATED while the reservation is live is still a no-op | `StatusUpdateAfterLapsedReservationApiTest.java` |
| `X9-LIFE-504` | the control of the contract cases: each operation's unbroken sample is accepted | `test_contract.py` |
| `X9-LIFE-505` | every length, pattern, required and numeric bound in openapi.yaml is refused: 400, the field's path, nothing changed | `test_contract.py` |
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
| `X9-HOLD-001` | the same payer at the same institution is the same party | `ReservationHolderVOTest.java` |
| `X9-HOLD-002` | a different payer at the same institution is a different party | `ReservationHolderVOTest.java` |
| `X9-HOLD-003` | the same payer value from a different institution is a different party | `ReservationHolderVOTest.java` |
| `X9-HOLD-004` | without payer info there is no telling two payers apart, so they are different | `ReservationHolderVOTest.java` |
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
| `X9-HOLD-101` | a transaction hash makes a post-commit on every rail, Solana included, whatever the action says | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-102` | no transaction id makes a pre-commit on every rail, FedNow and RTP included | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-103` | NOT_SENT is the one phase the action decides on its own | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-104` | a pre-commit on an ACTIVE QR Code reserves it for the sender | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-105` | the holder's pre-commit is a retry: the window moves and nothing is emitted | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-106` | another payer's pre-commit on a held QR Code is a 409 | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-107` | a pre-commit on a PAID or CANCELLED QR Code is a 400 | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-108` | a takeover of a lapsed reservation emits payment.initiated | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-109` | a post-commit on an ACTIVE QR Code on a chain reserves it and registers the hash, flagged | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-110` | a post-commit on an ACTIVE QR Code on a bank rail reserves it with payment.initiated only | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-111` | after a post-commit reserved it, another payer's pre-commit is a 409 | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-112` | the holder's bank-rail post-commit is recorded with no event | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-113` | the holder's post-commit on a chain emits payment.sent with no deviations | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-114` | another payer's post-commit on a held QR Code is registered, flagged, and the holder keeps it | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-115` | a post-commit on a PAID or CANCELLED QR Code is registered, flagged, and nothing reopens | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-116` | a bank-rail post-commit on a closed QR Code publishes nothing | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-117` | every hash is registered as its own payment.sent, none overwritten | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-118` | a PAYMENT_INITIATED carrying a hash on an ACTIVE QR Code carries both deviation codes | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-119` | a PAYMENT_INITIATED carrying a hash from the holder carries ACTION_CONTRADICTS_HASH only | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-120` | a PAYMENT_INITIATED carrying a hash on a held or closed QR Code carries both codes | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-121` | SENT without a hash is a 400 in every status | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-122` | NOT_SENT from the holder releases the QR Code | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-123` | NOT_SENT on an ACTIVE QR Code is a 400, unless from the holder of a lapsed reservation | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-124` | NOT_SENT from anybody but the holder is a 409 | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-125` | NOT_SENT on a PAID or CANCELLED QR Code is a 400 | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-126` | a hash from the holder stops the window | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-127` | a hash from anybody stops the window, and the holder does not change | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-128` | after a hash, even the holder's retried pre-commit is a 409 and changes nothing | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-129` | only the status-update API ends a reservation whose window a hash stopped | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-138` | after a hash, the holder's NOT_SENT is accepted, flagged ACTION_CONTRADICTS_HASH, and changes nothing | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-139` | a NOT_SENT carrying a transaction id is a post-commit, flagged ACTION_CONTRADICTS_HASH | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-140` | the status-update API's revert clears the registered hash with the reservation | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-130` | a refused retry from the holder releases the reservation, with payment.failed on a chain | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-131` | a refused retry on a bank rail releases the reservation without an event | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-132` | a holder without payer.info cannot retry: 409 | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-133` | a holder without payer.info cannot release with NOT_SENT: 409 | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-134` | a holder without payer.info reports as somebody else: flagged, and nothing changes | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-135` | with ttl-seconds 0 a pre-commit's reservation never lapses | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-136` | with ttl-seconds 0 the status-update API's reservation never lapses either | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-137` | with ttl-seconds 0 a reservation still ends by NOT_SENT or a refused retry | `QRCodeEntityNotificationMatrixTest.java` |
| `X9-HOLD-201` | a FedNow or RTP pre-commit is gated at the payee's clock and reserves the QR Code (S2.4-AC1) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-202` | a second payer's pre-commit is a 409 on every rail (S2.4-AC2) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-203` | two payers racing for one QR Code end with exactly one winner and one 409 (S2.4-AC2) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-204` | another payer's Solana post-commit is registered, flagged, and the holder keeps the QR Code (S2.4-AC2) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-205` | another payer's bank-rail post-commit changes nothing and publishes nothing (S2.4-AC2) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-206` | the holder's bank-rail post-commit is recorded, never judged, with no payment.sent (S2.4-AC3) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-207` | a Solana post-commit on an ACTIVE QR Code reserves it, registers the hash, flags both events (S2.4-AC4) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-208` | a bank-rail post-commit on an ACTIVE QR Code reserves it with payment.initiated only (S2.4-AC4) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-209` | a Solana PAYMENT_INITIATED carrying a hash is a post-commit, recorded as received and flagged (S2.4-AC5) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-210` | the holder's PAYMENT_INITIATED carrying a hash is flagged ACTION_CONTRADICTS_HASH only (S2.4-AC5) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-211` | a PAYMENT_INITIATED carrying a hash on a held or PAID QR Code carries both codes (S2.4-AC5) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-212` | SENT without a hash is a 400 in every status (S2.4-AC5) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-213` | NOT_SENT still releases the reservation (S2.4-AC5) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-214` | every hash is registered as its own payment.sent, and X9.150 never marks PAID (S2.4-AC6) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-215` | a post-commit on a PAID or CANCELLED QR Code is registered, flagged, and nothing reopens (S2.4-AC7) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-216` | a bank-rail post-commit on a closed QR Code is accepted and publishes nothing (S2.4-AC7) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-217` | the payee can list, per payer, the post-commits flagged RECEIVED_IN_WRONG_STATUS (S2.4-AC8) | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-218` | after a hash, the holder's NOT_SENT is accepted and changes nothing | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-219` | after a hash, the holder's retried pre-commit is a 409 and does not release | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-220` | a NOT_SENT carrying a transaction id is a post-commit: registered, flagged ACTION_CONTRADICTS_HASH | `PreCommitOnEveryRailApiTest.java` |
| `X9-HOLD-221` | losing the save race after every re-run answers 409 busy with the current status, never 500 | `PaymentNotificationRaceExhaustedTest.java` |
| `X9-HOLD-301` | the holder's retry is judged again at the payee's clock, and its refusal frees the QR Code | `HolderRetryAndIdentityApiTest.java` |
| `X9-HOLD-302` | after a refused retry on a bank rail, the same payer may pre-commit again with the right amount | `HolderRetryAndIdentityApiTest.java` |
| `X9-HOLD-303` | a refused pre-commit from a non-holder, or on an ACTIVE QR Code, changes nothing | `HolderRetryAndIdentityApiTest.java` |
| `X9-HOLD-304` | an accepted retry answers 200 and emits nothing | `HolderRetryAndIdentityApiTest.java` |
| `X9-HOLD-305` | the same certificate with a different payer.info is a different payer | `HolderRetryAndIdentityApiTest.java` |
| `X9-HOLD-306` | a holder without payer.info cannot retry, cannot release, and its post-commit changes nothing | `HolderRetryAndIdentityApiTest.java` |
| `X9-HOLD-307` | once a hash is registered, only the status-update API ends the reservation | `HolderRetryAndIdentityApiTest.java` |
| `X9-HOLD-308` | approved at 23:59:50 with the discount, retried at 00:00:02: refused, and the QR Code is freed | `HolderRetryAndIdentityApiTest.java` |
| `X9-HOLD-311` | winning the pre-commit on a lapsed reservation emits payment.initiated for the new holder | `ReservationWindowApiTest.java` |
| `X9-HOLD-312` | the holder's hash stops the window | `ReservationWindowApiTest.java` |
| `X9-HOLD-313` | another payer's hash stops the window too, and the holder does not change | `ReservationWindowApiTest.java` |
| `X9-HOLD-314` | a bank-rail hash stops the window as well | `ReservationWindowApiTest.java` |
| `X9-HOLD-315` | without a hash, the same wait releases the reservation | `ReservationWindowApiTest.java` |
| `X9-HOLD-316` | the holder of a lapsed reservation may still give it back with NOT_SENT | `ReservationWindowApiTest.java` |
| `X9-HOLD-321` | with ttl-seconds 0 a reservation never lapses, and still ends by NOT_SENT or the status-update API | `NoReservationWindowApiTest.java` |
| `X9-HOLD-322` | with ttl-seconds 0 a refused retry still frees the QR Code | `NoReservationWindowApiTest.java` |
| `X9-HOLD-323` | a negative x9.reservation.ttl-seconds refuses to start | `ReservationTtlConfigurationTest.java` |
| `X9-HOLD-324` | 0 and positive values of x9.reservation.ttl-seconds are accepted as they are | `ReservationTtlConfigurationTest.java` |
| `X9-HOLD-401` | a rail and a currency in another case are accepted and echoed as sent, stored and in the event | `test_payers.py` |
| `X9-HOLD-402` | no post-commit marks a QR Code PAID, on any rail, the holder's or a second hash or one on ACTIVE | `test_payers.py` |
| `X9-HOLD-403` | a QR Code created without paymentNotification refuses notifications, signed, and stays ACTIVE | `test_payers.py` |
| `X9-HOLD-404` | paymentNotification EXTERNAL: the payload names the external endpoint; a notification here is refused | `test_payers.py` |
| `X9-HOLD-405` | every answer to a payer is signed, refusals included, statusCode equal to the HTTP status | `test_payers.py` |
| `X9-HOLD-406` | 32 concurrent pre-commits: exactly one 200, 31 signed 409s, one payment.initiated | `test_payers.py` |
| `X9-HOLD-407` | an ACH notification without `payer.info` (no payer, or no info) or without `expectedDate` is refused, signed, and reserves nothing | `test_payers.py` |
| `X9-HOLD-501` | a pre-commit is accepted and the QR Code is PAYMENT_INITIATED | `test_two_phase.py` |
| `X9-HOLD-502` | a second payer, on its own key, is refused with 409 while the QR Code is reserved | `test_two_phase.py` |
| `X9-HOLD-503` | a post-commit is accepted and does not mark the QR Code PAID | `test_two_phase.py` |

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
| `X9-LOC-021` | a payload request whose signed `qrCodeContent` is malformed EMV is refused, saying so | `JwsQRCodeSignatureServiceTest.java` |
| `X9-LOC-030` | the payload URL is extracted from EMV tag 26 | `MateraAdoptQRCodeEMVServiceTest.java` |
| `X9-LOC-040` | a location held by a live QR Code cannot be claimed at creation | `QRCodesApisFlowTest.java` |
| `X9-LOC-041` | the payload is retrieved by location, signed | `QRCodesApisFlowTest.java` |
| `X9-LOC-042` | a location is reused once its holder is no longer live | `QRCodesApisFlowTest.java` |
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
| `X9-LOC-480` | a configured host of 38 characters stops the service at startup, naming the 37 limit; 37 starts | `X9PropertiesHostLengthTest.java` |
| `X9-LOC-481` | a payload or JWK set on another host is fetched over `https`, whatever scheme was written | `DefaultQRCodeLocationServiceTest.java` |
| `X9-LOC-482` | this deployment's own host is read internally, on its local port (the control for X9-LOC-481) | `DefaultQRCodeLocationServiceTest.java` |
| `X9-LOC-483` | a URL that only mentions the own host (query, path, user information, subdomain, suffix, other port) is another host's, fetched over `https` without user information (BUG-005) | `DefaultQRCodeLocationServiceTest.java` |
| `X9-LOC-484` | the own host is recognised whatever its case, and with user information dropped (the control for X9-LOC-483) | `DefaultQRCodeLocationServiceTest.java` |
| `X9-LOC-485` | a configured host with a character outside printable ASCII, or a space, stops the service at startup | `X9PropertiesHostLengthTest.java` |
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
| `X9-DEC-013` | a malformed EMV string is refused, never tolerated: the Go build's 19 malformed cases by name, plus three only a Java string can carry | `EmvReaderTest.java` |
| `X9-DEC-014` | a well-formed EMV string is read: the golden string, a lower-case CRC, org.x9 among other templates, UTF-16 lengths (the control for X9-DEC-013) | `EmvReaderTest.java` |
| `X9-DEC-015` | the decoder refuses each malformed case with a 400 naming `qrCode`, before any fetch | `DecoderRelaysTheRefusalApiTest.java` |
| `X9-DEC-016` | the decoder refuses the Go build's 19 malformed cases with a 400, before any fetch (black box) | `test_qr_code.py` |

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
| `X9-PATCH-040` | a PATCH of `validUntil` alone, to before the bill's `dueDate`, is refused naming `bill.invoice.dueDate`; nothing changes; exactly `dueDate` is accepted | `PatchCrossFieldDatesApiTest.java` |
| `X9-PATCH-041` | a PATCH moving `validUntil` before a method's, or a method's after the QR Code's, is refused naming `paymentMethods[0].validUntil`; nothing changes | `PatchCrossFieldDatesApiTest.java` |
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

## Payment requests pinned: create, PATCH, ETag, rebind (Story 4.2)

Black-box pins of today's Java behaviour for plan §4.1 groups A, B, B2 and B3, all in
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
| `X9-LOC-425` | tag 26 fits in 99 characters, which limits the configured host to 37 | `test_payment_requests_pinned.py` |
| `X9-PATCH-420` | fields that are not patchable (creditor, status, id, revision, …) are never applied | `test_payment_requests_pinned.py` |
| `X9-PATCH-421` | a patch changing only fields that are not patchable is a refused no-op | `test_payment_requests_pinned.py` |
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
| `X9-LOC-421` | an expired `ACTIVE` holder gives up its location (Java contradicts ADR-0024, strict xfail, BUG-004) | `test_payment_requests_pinned.py` |
| `X9-LOC-422` | a live holder (`ACTIVE` in time, `PAYMENT_INITIATED`) keeps its location, on create and PATCH | `test_payment_requests_pinned.py` |
| `X9-LOC-423` | a different creditor never takes the location, on create and PATCH | `test_payment_requests_pinned.py` |
| `X9-LOC-424` | a refused create does not release the holder | `test_payment_requests_pinned.py` |

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
| `X9-RAIL-101` | a FedNow post-commit on an ACTIVE QR Code reserves it, and never settles it | `PaymentNotificationDispatchApiTest.java` |
| `X9-RAIL-102` | a rail the QR Code does not offer is refused | `PaymentNotificationDispatchApiTest.java` |
| `X9-RAIL-103` | an uninterpreted network is rejected, not ignored | `PaymentNotificationDispatchApiTest.java` |
| `X9-RAIL-110` | the full create-and-settle flow works on every interpreted rail | `QRCodesApisFlowTest.java` |
| `X9-RAIL-111` | a payment notification is accepted on every interpreted rail | `QRCodesApisFlowTest.java` |
| `X9-RAIL-120` | `paymentMethods` sent as a bare object is refused with 400 and no id | `test_create_refusals.py` |
| `X9-RAIL-501` | a network this deployment does not interpret is refused at create, naming it, with no id | `test_create_refusals.py` |

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
| `X9-SIG-055` | a payer whose only revocation source is an unreachable CRL is accepted (soft-fail; BUG-003) | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-056` | the same payer can fetch the payload | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-057` | a payer whose only revocation source is an unreachable OCSP responder is accepted (soft-fail) | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-058` | a payer whose CRL and OCSP responder are both unreachable is accepted (soft-fail) | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-059` | a CA-issued payer naming no revocation source is refused | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-060` | a refusal is logged as a refusal | `RequestLoggerFilterStatusTest.java` |
| `X9-SIG-061` | a failure is logged as a failure | `RequestLoggerFilterStatusTest.java` |
| `X9-SIG-062` | a success is still logged as a success | `RequestLoggerFilterStatusTest.java` |
| `X9-SIG-063` | an exception escaping the chain is logged as a 500 | `RequestLoggerFilterStatusTest.java` |
| `X9-SIG-064` | a payer whose only revocation source is a CRL answering with something that is not a CRL is accepted (soft-fail) | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-065` | the same case can fetch the payload | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-066` | a payer whose CRL is not a CRL and whose OCSP responder is unreachable is accepted (soft-fail) | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-067` | the same case can fetch the payload | `CaIssuedSignatureApiTest.java` |
| `X9-SIG-068` | an `x5u` on another host that only mentions the own host is not fetched over plain http: `CERTIFICATE_UNAVAILABLE` (BUG-005) | `CaIssuedSignatureApiTest.java` |
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
| `X9-SIG-120` | an accepted pre-commit is answered with a signed `{"statusCode": 200}` | `SignedNotificationAnswerApiTest.java` |
| `X9-SIG-121` | an accepted post-commit is answered with a signed yes too | `SignedNotificationAnswerApiTest.java` |
| `X9-SIG-122` | a refusal keeps its status and error fields, signed, correlationId echoed | `SignedNotificationAnswerApiTest.java` |
| `X9-SIG-123` | another holder's 409 is signed as well | `SignedNotificationAnswerApiTest.java` |
| `X9-SIG-124` | a question that cannot be read is refused, signed, under a fresh correlationId, naming the failed check by its `signatureProblem` code | `SignedNotificationAnswerApiTest.java` |
| `X9-SIG-125` | a SENT report after the discount edge is recorded and answered with a signed yes | `SignedNotificationAnswerApiTest.java` |
| `X9-SIG-126` | with public keys only, a verifier links question and answer | `SignedNotificationAnswerApiTest.java` |
| `X9-SIG-151` | the signed answer, a yes and a no alike, carries `typ: JOSE` | `SignedNotificationAnswerApiTest.java` |
| `X9-SIG-130` | a signed yes with the correlationId sent is VERIFIED | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-131` | a signed no is VERIFIED and not accepted | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-132` | a signed yes under another correlationId is INVALID, and still an unsigned yes | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-133` | a signed answer whose payload was altered is INVALID | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-134` | an HTTP status that differs from the signed one is INVALID | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-135` | an unsecured JOSE object (`alg: none`) is INVALID | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-136` | an empty 200 is ABSENT and reported as an unsigned yes | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-137` | unsigned JSON and plain text are ABSENT | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-138` | a plain JSON refusal is ABSENT, not accepted, body passed through | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-140` | an answer whose iat is in the future beyond the skew is INVALID, `FUTURE_IAT`; within it, VERIFIED | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-141` | an answer whose `iat + ttl` has passed is INVALID, `EXPIRED` | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-142` | a signed body without an integer `statusCode` is INVALID, `STATUS_MISSING` | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-143` | a signed answer with an unknown `crit` or no `correlationId` header is INVALID, `BAD_HEADER` | `OutboundAnswerVerificationApiTest.java` |
| `X9-SIG-144` | the shared verifier refuses an iat ahead of its clock beyond the skew, and accepts one within it | `JwsQRCodeSignatureServiceTest.java` |
| `X9-SIG-145` | a question whose iat is in the future beyond the skew is refused, signed; within it, accepted | `SignedNotificationAnswerApiTest.java` |
| `X9-SIG-146` | an empty body, refused before the controller, is answered with a signed 400 | `NotificationAnswerSignedEverywhereApiTest.java` |
| `X9-SIG-147` | a wrong `Content-Type` is answered with a signed 415 | `NotificationAnswerSignedEverywhereApiTest.java` |
| `X9-SIG-148` | a body Spring cannot read is answered with a signed 400 | `NotificationAnswerSignedEverywhereApiTest.java` |
| `X9-SIG-149` | an exception nothing maps is answered with a signed 500 that says nothing about it | `NotificationAnswerSignedEverywhereApiTest.java` |
| `X9-SIG-150` | a payee answering 302 or 307 is not followed: one delivery, the redirect reported as the answer | `test_payee_answers.py` |
| `X9-SIG-301` | the payee's refusal is reported as 200 with accepted false, changing nothing | `test_payers.py` |
| `X9-SIG-302` | an unreachable payee is 502, with no verdict | `test_payers.py` |
| `X9-SIG-303` | a pre-payment carrying a transactionId is refused before sending | `test_payers.py` |
| `X9-SIG-304` | a post-payment SENT needs a transactionId; NOT_SENT without one is delivered | `test_payers.py` |
| `X9-SIG-501` | the test payee holds the service's signed outbound question byte for byte, under the caller's correlationId | `test_payee_answers.py` |
| `X9-SIG-502` | each of the payee's answers (cases 1-8, 11-16) is reported for what it is: signature, signatureProblem, statusCode, accepted | `test_payee_answers.py` |
| `X9-SIG-503` | an answer signed by an untrusted or a revoked certificate is INVALID, UNTRUSTED_CHAIN | `test_payee_answers.py` |
| `X9-SIG-504` | a payee that hangs up without answering is 502, and nothing changes here | `test_payee_answers.py` |
| `X9-SIG-505` | a payee answering after the 15 s timeout is 502 | `test_payee_answers.py` |
| `X9-SIG-506` | each tampered payee answer is reported INVALID with the service's fixed code | `test_payee_answers.py` |
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
| `X9-SIG-525` | an EC payer issued by a trusted CA, carrying x5c, is accepted | `CaIssuedSignatureApiTest.java` |

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
| `X9-TIME-006` | T6: a pre-commit 1 ms after `validUntil` is refused, one just before is accepted; the payee may still mark it PAID | `test_time_edges.py` |
| `X9-TIME-007` | T7: a payment method past its own `validUntil` is absent from the payload and refused; the other is served and accepted | `test_time_edges.py` |
| `X9-TIME-008` | T8: after a lapse the ETag reads ACTIVE and If-Match with it succeeds; another payer takes over with `payment.initiated`; the first payer's late SENT is registered, flagged | `test_time_edges.py` |
| `X9-TIME-010` | T9: a reservation taken 30 s before `validUntil` ends at `validUntil` | `test_time_edges.py` |
| `X9-TIME-011` | T10: a payer `iat` 10 min in the service's past with a 5-min `ttl` is 401 EXPIRED before any lookup | `test_time_edges.py` |
| `X9-TIME-012` | T11, both builds: an hour past `validUntil` on the test clock, the payload is refused as expired and GET still works | `test_time_edges.py` |
| `X9-TIME-013` | T11, Java only: MongoDB's TTL monitor deletes an expired QR Code on the real clock (waits real time) | `test_time_edges.py` |
| `X9-TIME-014` | T11, Go only: an expired QR Code is kept and its location is releasable | `test_time_edges.py` |
| `X9-TIME-015` | T12, Go only: retention purges events, then QR Codes, by the test clock, and nothing younger | `test_time_edges.py` |
| `X9-TIME-016` | S4.5-AC2: with the payee's clock ahead of the payer's, the payee's clock decides `validUntil` | `test_time_edges.py` |
| `X9-TIME-017` | one instant: 1 ms past a method's `validUntil`, with `dateForPayment` that day, the expired method is absent and the other is priced one day late | `test_time_edges.py` |
| `X9-TIME-020` | a past `dateForPayment` does not bring back an expired payment method; the methods shown are chosen at the instant the fee is computed | `PayloadPricingInstantApiTest.java` |
| `X9-TIME-021` | a future `dateForPayment` still chooses the payment methods valid on that day | `PayloadPricingInstantApiTest.java` |
| `X9-TIME-018` | a post-commit 1 ms after `validUntil` is accepted, signed, and registered with its hash | `test_time_edges.py` |
| `X9-TIME-501` | the test clock moves, reads back and resets, and every answer carries `X-Test-Clock-Offset` while it is moved | `test_clock.py` |
| `X9-TIME-502` | on the test clock, a payload stops being served once the clock passes `validUntil` (X9-LIFE-126 without sleeping) | `test_clock.py` |
| `X9-TIME-511` | with no offset, business time is real time | `BusinessClockTest.java` |
| `X9-TIME-512` | DateTimeUtils.nowUTC() includes the offset | `BusinessClockTest.java` |
| `X9-TIME-513` | a shifted clock keeps ticking: it is never frozen | `BusinessClockTest.java` |
| `X9-TIME-514` | reset restores real time | `BusinessClockTest.java` |
| `X9-TIME-515` | setting `at` shifts the clock to that instant | `BusinessClockTest.java` |
| `X9-TIME-516` | `at` keeps its milliseconds, and the clock never reads earlier than it | `BusinessClockTest.java` |
| `X9-TIME-517` | setting E + 1 ms makes every later read after E | `BusinessClockTest.java` |
| `X9-TIME-518` | an instant finer than a millisecond is refused | `BusinessClockTest.java` |
| `X9-TIME-519` | an instant too far away is refused | `BusinessClockTest.java` |
| `X9-TIME-520` | an absurd offset is refused | `BusinessClockTest.java` |
| `X9-TIME-521` | the default dateForPayment of a payload read is business time | `BusinessClockReadsTest.java` |
| `X9-TIME-522` | an explicit dateForPayment is not touched by the offset | `BusinessClockReadsTest.java` |
| `X9-TIME-523` | every certificate bundled with this repository satisfies the test-clock guard | `TestClockCertificateGuardTest.java` |
| `X9-TIME-524` | the compiled-in list is exactly the bundled certificates | `TestClockCertificateGuardTest.java` |
| `X9-TIME-525` | a certificate that is not bundled is refused by the guard | `TestClockCertificateGuardTest.java` |
| `X9-TIME-526` | no certificate at all is refused by the guard | `TestClockCertificateGuardTest.java` |
| `X9-TIME-527` | with the clock on and a certificate that is not bundled, the application refuses to start | `TestClockCertificateGuardTest.java` |
| `X9-TIME-528` | with the clock off, no clock exists, whatever the certificate | `TestClockCertificateGuardTest.java` |
| `X9-TIME-529` | with the clock on and the demo certificate, the clock listener starts | `TestClockCertificateGuardTest.java` |
| `X9-TIME-530` | the clock listener may not be the API port | `TestClockCertificateGuardTest.java` |
| `X9-TIME-531` | GET, PUT and DELETE /test/clock are served on the clock listener | `TestClockControlApiTest.java` |
| `X9-TIME-532` | the clock is never on the API port, even with the flag on | `TestClockControlApiTest.java` |
| `X9-TIME-533` | a JWS signed under the offset carries the shifted iat | `TestClockControlApiTest.java` |
| `X9-TIME-534` | verification reads the shifted clock: a JWS expired in business time is refused | `TestClockControlApiTest.java` |
| `X9-TIME-535` | every API answer carries X-Test-Clock-Offset until the offset is deleted | `TestClockControlApiTest.java` |
| `X9-TIME-536` | on the clock, a pre-commit is accepted before an edge and refused 1 ms after it | `TestClockControlApiTest.java` |
| `X9-TIME-537` | with the clock off, nothing listens on the clock address | `TestClockDisabledApiTest.java` |
| `X9-TIME-538` | with the clock off, /test/clock on the API port is 404 for every method, and the clock does not move | `TestClockDisabledApiTest.java` |
| `X9-TIME-539` | starting the clock listener logs a WARN | `TestClockServerTest.java` |
| `X9-TIME-540` | the clock server refuses to start with a certificate that is not bundled | `TestClockServerTest.java` |
| `X9-TIME-541` | PUT an offset in seconds, GET it back, DELETE it | `TestClockServerTest.java` |
| `X9-TIME-542` | PUT `at` moves the clock to that instant | `TestClockServerTest.java` |
| `X9-TIME-543` | the offset can be set in milliseconds | `TestClockServerTest.java` |
| `X9-TIME-544` | PUT `at` keeps its milliseconds, and the clock never reads earlier | `TestClockServerTest.java` |
| `X9-TIME-545` | a bad PUT is refused and leaves the clock where it was | `TestClockServerTest.java` |
| `X9-TIME-546` | other methods and paths are refused | `TestClockServerTest.java` |
| `X9-TIME-547` | a real certificate swapped in at runtime stops the clock moving | `TestClockServerTest.java` |
| `X9-TIME-548` | stopping the clock server resets the clock | `TestClockServerTest.java` |
| `X9-TIME-549` | clock listen addresses are parsed | `TestClockServerTest.java` |

## Golden sets (Story 4.7)

The JWS and EMV fixtures exported from Java for the Go build
([`others/blackbox/golden`](../others/blackbox/golden/README.md), ADR-0027), proved true offline
and against the running Java build.

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
| `X9-TIP-130` | a tip without allowed on create allows tips | `DefaultedBillFieldsApiTest.java` |
| `X9-TIP-131` | an explicit null tip.allowed on create is refused | `DefaultedBillFieldsApiTest.java` |
| `X9-TIP-132` | a PATCH tip without allowed allows tips | `DefaultedBillFieldsApiTest.java` |
| `X9-TIP-133` | an explicit null tip.allowed on PATCH is refused | `DefaultedBillFieldsApiTest.java` |
| `X9-TIP-140` | tip percentages are taken in the notified currency (a USD bill paid through a USDC method) | `PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-501` | each tip form (none, refused, a range only) can be created | `test_tip_offers.py` |
| `X9-TIP-502` | a tip offer with neither a range nor presets is refused at create | `test_tip_offers.py` |
| `X9-TIP-503` | omitting the tip block is stored as tips refused | `test_tip_offers.py` |
| `X9-TIP-504` | the bill plus a tip is accepted | `test_tip_offers.py` |
| `X9-TIP-505` | a wrong tip is refused naming the tip, and reserves nothing | `test_tip_offers.py` |
| `X9-TIP-506` | a tip matching no preset is accepted when no range is published | `test_tip_offers.py` |

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
