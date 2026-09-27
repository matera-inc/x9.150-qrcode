# ANSI X9.150 Specification

Reference to the **ANSI X9.150-2026 Payment QR Code Standard** that this project implements.

> ⚠️ **The ANSI standard is NOT distributed with this repository.**
> ANSI X9.150-2026 is copyrighted by the Accredited Standards Committee X9, Inc. (ASC X9) / ANSI
> and may not be redistributed. To obtain it, purchase the official document from the ANSI Web
> Store: https://webstore.ansi.org/standards/ascx9/ansix91502026

The official ASC X9 document for the Merchant-Presented Payment QR Code Standard is sold by ANSI as
a PDF and is not part of this repository — purchase it from the link above.

## Our reading of it

The standard leaves a handful of things ambiguous, and in a couple of places contradicts itself.
[**INTERPRETATION.md**](INTERPRETATION.md) records every such call this implementation makes — what
the text says, which way we went, and why — so that anyone holding both the standard and this repo
can see our decisions as decisions rather than mistake them for the standard's.

[**ACCOUNT-NUMBERS-US.md**](ACCOUNT-NUMBERS-US.md) goes deeper on one field that reliably surprises
people: why `accountNumber` accepts letters as well as digits, what ANSI X9.150 and the Nacha ACH
file format each require, and what to do about upper- versus lower-case.

Decisions that are ours alone, rather than readings of the standard, are in
[`docs/adr/`](../docs/adr/README.md).
