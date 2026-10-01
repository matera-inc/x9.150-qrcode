# Documenting a test so somebody can learn from it

This is a reference implementation. People read these tests to find out what the standard
*requires* — and, just as often, to find out where we had to decide something the standard does not
settle. A test that only proves an `if` works tells them neither.

So every test carries three things.

## The shape

```java
/**
 * <b>X9-TIP-002</b> — a tip paid out of the merchant's share is refused.
 *
 * <p><b>Source:</b> ANSI X9.150-2026 §2.1 — <i>"Total amount sent for payment"</i> — with §13.6.2
 * defining that total as <i>amount + tip</i>. Conformance, not interpretation.
 *
 * <p><b>Why:</b> 1000 arrives carrying a 200 tip, so the merchant receives 800 on a 1000 bill.
 * Before this rule the QR Code was marked paid in full and nothing recorded that the merchant was
 * short. A consumer totalling payments across the QR Codes of one payment request counts that bill
 * as settled.
 */
```

**Key** — `X9-<AREA>-<NNN>`, stable for the life of the test. It is what a bug report, an ADR or a
message to an adopter can point at. Renaming a test method must not invalidate the reference, which
is the whole reason the key is not just the method name.

Areas: `AMT` amounts · `TIP` tips · `CUR` currencies · `RAIL` networks and rails · `LIFE` lifecycle
and status · `SIG` signatures and keys · `LOC` locations and hand-over · `PATCH` editing a request ·
`EVT` the event stream.

**Source** — where the rule comes from, and it must be honest about which of three kinds it is:

| | |
|---|---|
| **Conformance** | the standard requires it. Cite the clause and quote enough to recognise it. |
| **Ours** | the standard is silent or addresses someone else. Say so, and link the ADR or the `INTERPRETATION.md` entry. |
| **Mechanism** | neither — it pins how this implementation happens to work, so it cannot drift by accident. Say that plainly. |

The third kind matters most. A reader who cannot tell "the standard says so" from "Matera decided
this" will carry our decisions into their own implementation believing they are requirements.

**Why** — what goes wrong without it, in concrete terms. Not "validates the amount" but "the
merchant receives 800 on a 1000 bill and the QR Code is marked paid in full". Where a defect
prompted the test, say what the defect did; that is the part a reader actually learns from.

## In pytest

Same three parts, in the docstring, with the key first:

```python
def test_a_tip_taken_out_of_the_merchants_share_is_refused(self, api):
    """X9-TIP-002 — a tip paid out of the merchant's share is refused.

    Source: ANSI X9.150-2026 §2.1 with §13.6.2 — the notification carries the total, tip included.
      Conformance.
    Why: 1000 arrives carrying a 200 tip, so the merchant receives 800 on a 1000 bill ...
    """
```

## What not to do

**Do not restate the assertion.** `"checks that the status is 400"` is visible one line below and
teaches nothing.

**Do not cite a clause you have not read.** A wrong citation in a reference implementation is worse
than none: it is confidently wrong, and the reader has no reason to doubt it. If the rule is ours,
the honest sentence is *"the standard does not settle this"*.

**Do not give two tests the same key**, and do not reuse a retired one. A key that once meant
something else is a trap for anyone reading an old report.
