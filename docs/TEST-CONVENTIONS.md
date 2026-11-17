# Documenting a test so somebody can learn from it

This is a reference implementation. People read these tests to find out what the standard
*requires* — and, just as often, to find out where we had to decide something the standard does not
settle. A test that only proves an `if` works tells them neither.

So every test carries three things.

## The shape

```python
def test_a_tip_taken_out_of_the_merchants_share_is_refused(self, api):
    """X9-TIP-102 — a tip paid out of the merchant's share is refused.

    Source: ANSI X9.150-2026 §2.1 — "Total amount sent for payment" — with §13.6.2 defining that
      total as amount + tip. Conformance, not interpretation.
    Why: 1000 arrives carrying a 200 tip, so the merchant receives 800 on a 1000 bill. Before this
      rule the QR Code was marked paid in full and nothing recorded that the merchant was short.
    """
```

**Key** — `X9-<AREA>-<NNN>`, stable for the life of the test. It is what a bug report, an ADR or a
message to an adopter can point at. Renaming a test method must not invalidate the reference, which
is the whole reason the key is not just the method name.

Areas: `AMT` amounts · `TIP` tips · `CUR` currencies · `RAIL` networks and rails · `LIFE` lifecycle
and status · `HOLD` who holds a reservation · `MAP` a row of ADR-0035's transition map · `TIME` time
edges and the test clock · `SIG` signatures and keys · `LOC` locations and hand-over · `DEC` the
decoder · `PATCH` editing a request · `EVT` the event stream · `OPS` metrics and the actuator ·
`SUITE` the black-box suite's own tooling.

**Every black-box case has a key.** `docs/check/check_rule_coverage.py` fails on a black-box case
whose docstring does not start with one. (The Java build's JUnit tests carried theirs in their
javadoc; they were retired with it at 3.0.0.)

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

## In Go

A Go test is keyed when it must be pointed at from outside its package (a known difference between
the builds, a fix the differential run found, what an operator's monitoring reads): the key starts
its doc comment, then the same Source and Why.

```go
// X9-OPS-002 (S14.1-AC2): a request is timed under its route template, never a raw id.
//
// Source: Mechanism. ADR-0031 keeps the metric families and their labels.
// Why: a raw id in a label is one time series per QR Code, and the scrape grows without bound.
func TestRequestsAreTimedByRoute(t *testing.T) {
```

Any other Go test is named by `name_test.go::TestName` where a rule needs it.

## A rule and its test

When a test proves a rule written in STATE-MACHINE.md, ENDPOINTS.md, INTERPRETATION.md or an ADR,
name the test in that rule's row of [RULE-COVERAGE.md](RULE-COVERAGE.md). When it proves an
acceptance criterion, cite the criterion's ID (`S4.2-AC1`) in the docstring or doc comment. A check in
CI (`docs/check/check_rule_coverage.py`) fails on a criterion ID that does not exist, on a rule
with no test, and on a row naming a test that does not exist.

## What not to do

**Do not restate the assertion.** `"checks that the status is 400"` is visible one line below and
teaches nothing.

**Do not cite a clause you have not read.** A wrong citation in a reference implementation is worse
than none: it is confidently wrong, and the reader has no reason to doubt it. If the rule is ours,
the honest sentence is *"the standard does not settle this"*.

**Do not give two tests the same key**, and do not reuse a retired one. A key that once meant
something else is a trap for anyone reading an old report.
