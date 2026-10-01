# Test index

Every documented test, by key. The keys are stable: a bug report, an ADR or a message to an
adopter can point at one and still mean the same thing after the method has been renamed.

Each test's own javadoc or docstring carries **Source** — whether the rule is the standard's,
ours, or a mechanism being pinned — and **Why** it matters. See
[TEST-CONVENTIONS.md](TEST-CONVENTIONS.md).

**50 documented** of roughly 338 cases in the repository. The rest are being converted
area by area; an undocumented test is not a less valid test, only a less useful one to learn from.

## Amounts

| Key | What it proves | Where |
|---|---|---|
| `X9-AMT-001` | an amount that is not the bill is refused | `others/blackbox/test_payment_notification.py` |
| `X9-AMT-002` | a negative amount is refused | `others/blackbox/test_payment_notification.py` |
| `X9-AMT-003` | the exact bill is accepted | `others/blackbox/test_payment_notification.py` |
| `X9-AMT-004` | an amount outside the published editable range is refused | `others/blackbox/test_payment_notification.py` |
| `X9-AMT-005` | any amount inside the published range is accepted | `others/blackbox/test_payment_notification.py` |

## Currencies

| Key | What it proves | Where |
|---|---|---|
| `X9-CUR-001` | a currency the QR Code does not offer is refused | `others/blackbox/test_payment_notification.py` |

## Lifecycle and status

| Key | What it proves | Where |
|---|---|---|
| `X9-LIFE-001` | a notification for an unknown QR Code is 404 | `others/blackbox/test_payment_notification.py` |
| `X9-LIFE-002` | a second pre-payment is refused | `others/blackbox/test_payment_notification.py` |
| `X9-LIFE-003` | a payment on a cancelled QR Code is refused | `others/blackbox/test_payment_notification.py` |
| `X9-LIFE-004` | a post-payment with no pre-payment is refused | `others/blackbox/test_payment_notification.py` |
| `X9-LIFE-005` | a refused notification leaves the QR Code ACTIVE | `others/blackbox/test_payment_notification.py` |

## Locations

| Key | What it proves | Where |
|---|---|---|
| `X9-LOC-001` | the content this QR Code was issued with is accepted | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityIssuedContentTest.java` |
| `X9-LOC-002` | content that is not the issued one is refused | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityIssuedContentTest.java` |
| `X9-LOC-003` | content differing by a single character is refused | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityIssuedContentTest.java` |
| `X9-LOC-004` | an absent submitted content is not this rule's business | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityIssuedContentTest.java` |
| `X9-LOC-005` | a QR Code holding no content yet accepts nothing | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityIssuedContentTest.java` |

## Editing a payment request

| Key | What it proves | Where |
|---|---|---|
| `X9-PATCH-001` | naming every currency the QR Code offers is accepted | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-002` | the order currencies are named in does not matter | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-003` | a currency the QR Code does not offer is refused, by name | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-004` | a currency left out of the patch is refused, by name | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-005` | a patch naming only an unknown currency is refused | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityPatchCurrencyTest.java` |
| `X9-PATCH-006` | naming no currency at all is not this rule's business | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/entity/QRCodeEntityPatchCurrencyTest.java` |

## Networks and rails

| Key | What it proves | Where |
|---|---|---|
| `X9-RAIL-001` | a destination address the QR Code never published is refused | `others/blackbox/test_payment_notification.py` |
| `X9-RAIL-002` | blockchain data is refused on a bank rail | `others/blackbox/test_payment_notification.py` |

## Signatures and keys

| Key | What it proves | Where |
|---|---|---|
| `X9-SIG-001` | an unsigned body is refused | `others/blackbox/test_payment_notification.py` |
| `X9-SIG-002` | an empty body is refused | `others/blackbox/test_payment_notification.py` |
| `X9-SIG-003` | a payload edited after signing is refused | `others/blackbox/test_payment_notification.py` |

## Tips

| Key | What it proves | Where |
|---|---|---|
| `X9-TIP-001` | the bill plus a tip is accepted | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-002` | a tip paid out of the merchant's share is refused | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-003` | a bill with no tip is unaffected | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-004` | a tip larger than the transfer carrying it is refused | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-005` | a tip on a bill that refuses tips is refused | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-006` | a bill cannot exist without tip information | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-007` | a tip inside the published range is accepted | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-008` | a tip above the published maximum is refused | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-009` | a tip below the published minimum is refused | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-010` | an absurd tip is refused rather than accepted | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-011` | presets do not bind when no range is published | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-012` | a tip matching no preset is still bound by the range | `x9-qrcode-domain/src/test/java/com/matera/x9qrcode/domain/service/PaymentNotificationTipPolicyTest.java` |
| `X9-TIP-101` | the bill plus a tip inside the range is accepted | `others/blackbox/test_payment_notification.py` |
| `X9-TIP-102` | a tip taken out of the merchant's share is refused | `others/blackbox/test_payment_notification.py` |
| `X9-TIP-103` | a tip on a bill that refuses tips is refused | `others/blackbox/test_payment_notification.py` |
| `X9-TIP-104` | a tip outside the published range is refused | `others/blackbox/test_payment_notification.py` |
| `X9-TIP-105` | a tip of zero is refused rather than ignored | `others/blackbox/test_payment_notification.py` |
| `X9-TIP-106` | a negative tip is refused | `others/blackbox/test_payment_notification.py` |
| `X9-TIP-107` | a transfer that is entirely tip is refused | `others/blackbox/test_payment_notification.py` |
| `X9-TIP-108` | a tip larger than the transfer carrying it is refused | `others/blackbox/test_payment_notification.py` |
| `X9-TIP-109` | presets do not bind when no range is published | `others/blackbox/test_payment_notification.py` |
| `X9-TIP-110` | the tip percentage is taken against the merchant's share | `others/blackbox/test_payment_notification.py` |
| `X9-TIP-111` | on an editable amount the percentage is against the face amount | `others/blackbox/test_payment_notification.py` |
