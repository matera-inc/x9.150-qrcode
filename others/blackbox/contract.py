#!/usr/bin/env python3
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The contract writes the field-refusal tests (Story 3.3, ADR-0034).

Reads ``openapi.yaml`` and generates ONE refusal case for every ``maxLength``, ``minLength``,
``pattern`` and ``required`` in the JSON request schemas of the operations below. A field added to
the contract gets its refusal tests without anybody typing them.

A case is a sample body that the service accepts (proved by a control case per operation) with
exactly one field broken: one character too long, one too short, a value its pattern refuses, or
the field removed. ``tests/test_contract.py`` sends each one and asserts 400, the field's path in
the violations, and that nothing changed.

A constraint the service does not enforce today is listed, with the reason, in
``contract-known-gaps.yaml``: it still runs, as an expected failure that turns red the day the
service starts enforcing it. A constraint the generator cannot build a body for is reported as
skipped, with the reason. Nothing is dropped silently.

    python3 others/blackbox/contract.py          # list the cases and the counts, send nothing
"""
import copy
import os
import re
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
OPENAPI = os.environ.get("X9_OPENAPI") or os.path.normpath(os.path.join(
    HERE, "..", "..", "x9-qrcode-infrastructure", "src", "main", "resources", "apis", "openapi.yaml"))
KNOWN_GAPS = os.path.join(HERE, "contract-known-gaps.yaml")

KINDS = ("maxLength", "minLength", "pattern", "required")


class Operation:
    """A request schema, and how a case against it is sent and checked (see test_contract)."""

    def __init__(self, name, method, path, schema):
        self.name, self.method, self.path, self.schema = name, method, path, schema

    def __repr__(self):
        return f"{self.method} {self.path}"


OPERATIONS = [
    Operation("create", "POST", "/api/v1/payment-request", "PaymentRequestInput"),
    Operation("patch", "PATCH", "/api/v1/payment-request/{id}", "PatchPaymentRequestReplacement"),
    Operation("status-update", "PUT", "/api/v1/payment-request/{id}/status-update", "StatusUpdate"),
    Operation("pre-payment", "POST", "/api/v1/payment-notification/pre-payment",
              "OutboundPaymentNotification"),
    Operation("post-payment", "POST", "/api/v1/payment-notification/post-payment",
              "OutboundPaymentNotification"),
    Operation("signature-validate", "POST", "/api/v1/signature/validate",
              "SignatureValidationRequest"),
    Operation("emv-decoder", "POST", "/api/v1/qrcode-emv-decoder", "QRCodeEmvDecoder"),
]


class Unbuildable(Exception):
    """The sample body has nowhere to put this case. Reported as skipped, with this reason."""


class Case:
    def __init__(self, operation, path, kind, limit):
        self.operation = operation
        self.path = path                       # ("creditor", "address", "city"), "[]" for items
        self.kind = kind
        self.limit = limit                     # n for lengths, the regex for pattern, None
        self.pattern = None                    # set for length cases on a patterned field
        self.min_length = None
        self.max_length = None

    @property
    def field(self):
        return ".".join(self.path).replace(".[]", "[]")

    @property
    def id(self):
        return f"{self.operation.name}:{self.field}:{self.kind}"

    def __repr__(self):
        return self.id

    # -------------------------------------------------------------- building the broken body

    def build(self, base):
        """``(body, field_path)``: ``base`` with this one field broken, and the path the
        service should name (array indices filled in). Raises :class:`Unbuildable`."""
        body = copy.deepcopy(base)
        parent, key, named = _locate(body, self.path)

        if self.kind == "required":
            if key not in parent:
                raise Unbuildable(f"{self.field} is absent from the sample, nothing to remove")
            del parent[key]
            return body, named

        current = parent.get(key)
        if current is not None and not isinstance(current, str):
            raise Unbuildable(f"{self.field} is not a string in the sample ({type(current).__name__})")
        parent[key] = self._value(current)
        return body, named

    def _value(self, current):
        seed = (current or "") if isinstance(current, str) else ""
        filler = seed[-1:] if seed else "A"
        if self.kind == "maxLength":
            return (seed + filler * (self.limit + 1))[: self.limit + 1]
        if self.kind == "minLength":
            if self.limit <= 0:
                raise Unbuildable("minLength 0 cannot be violated")
            return (seed or filler * self.limit)[: self.limit - 1]
        if self.kind == "pattern":
            return _violating(self.limit, self.min_length, self.max_length, seed)
        raise ValueError(self.kind)


def _locate(body, path):
    """Walk ``path`` in ``body``; returns (parent, last key, dotted path with real indices).

    For ``[]`` it picks the first element whose next key exists, so ``paymentMethods[].networks
    .solana`` lands on the method that carries Solana, wherever it sits in the sample.
    """
    return _locate_from(body, list(path), [])


def _locate_from(node, path, named):
    if len(path) == 1:
        if not isinstance(node, dict):
            raise Unbuildable(f"{'.'.join(named)} is not an object in the sample")
        return node, path[0], ".".join(named + [path[0]])

    step, rest = path[0], path[1:]
    if step == "[]":
        if not isinstance(node, list) or not node:
            raise Unbuildable(f"no element at {'.'.join(named)}")
        reasons = []
        for index, element in enumerate(node):
            here = named[:-1] + [f"{named[-1]}[{index}]"]
            try:
                return _locate_from(element, rest, here)
            except Unbuildable as e:
                reasons.append(str(e))
        raise Unbuildable(reasons[0])

    if not isinstance(node, dict) or node.get(step) is None:
        raise Unbuildable(f"the sample has no {'.'.join(named + [step])}")
    return _locate_from(node[step], rest, named + [step])


def _violating(pattern, min_length, max_length, seed):
    """A value of an allowed length that ``pattern`` refuses, so the pattern is what fires."""
    length = len(seed) or 1
    length = max(length, min_length or 1)
    if max_length is not None:
        length = min(length, max_length)
    for char in ("é", "!", "-", " ", "a", "0", "Z", "+"):
        candidate = char * length
        if not re.fullmatch(pattern, candidate):
            return candidate
    raise Unbuildable(f"no candidate violates {pattern}")


# ------------------------------------------------------------------ reading the contract

def load(path=OPENAPI):
    with open(path) as f:
        return yaml.safe_load(f)


def _resolve(spec, schema):
    while isinstance(schema, dict) and "$ref" in schema:
        schema = spec["components"]["schemas"][schema["$ref"].split("/")[-1]]
    return schema or {}


def _merged(spec, schema):
    """The schema with ``allOf`` parts folded in (properties, required, string constraints)."""
    schema = _resolve(spec, schema)
    if "allOf" not in schema:
        return schema
    merged = {k: v for k, v in schema.items() if k != "allOf"}
    merged["properties"] = dict(schema.get("properties") or {})
    merged["required"] = list(schema.get("required") or [])
    for part in schema["allOf"]:
        part = _merged(spec, part)
        for k, v in part.items():
            if k == "properties":
                merged["properties"].update(v)
            elif k == "required":
                merged["required"] += [r for r in v if r not in merged["required"]]
            else:
                merged.setdefault(k, v)
    return merged


def _walk(spec, schema, path, operation, out, depth=0):
    if depth > 15:
        return
    schema = _merged(spec, schema)

    strings = {k: schema[k] for k in ("maxLength", "minLength", "pattern") if k in schema}
    for kind, limit in strings.items():
        case = Case(operation, tuple(path), kind, limit)
        case.pattern = strings.get("pattern")
        case.min_length = strings.get("minLength")
        case.max_length = strings.get("maxLength")
        out.append(case)

    for name in schema.get("required") or []:
        out.append(Case(operation, tuple(path) + (name,), "required", None))
    for name, child in (schema.get("properties") or {}).items():
        _walk(spec, child, path + [name], operation, out, depth + 1)
    if schema.get("type") == "array" and "items" in schema:
        _walk(spec, schema["items"], path + ["[]"], operation, out, depth + 1)


def cases(spec=None):
    """Every generated case, in contract order."""
    spec = spec or load()
    out = []
    for operation in OPERATIONS:
        _walk(spec, spec["components"]["schemas"][operation.schema], [], operation, out)
    seen, unique = set(), []
    for case in out:
        if case.id not in seen and case.path:
            seen.add(case.id)
            unique.append(case)
    return unique


def known_gaps(path=KNOWN_GAPS):
    """``{case id: reason}`` from the known-gaps file."""
    if not os.path.exists(path):
        return {}
    with open(path) as f:
        data = yaml.safe_load(f) or {}
    return {entry["case"]: entry["reason"] for entry in data.get("gaps") or []}


if __name__ == "__main__":
    generated = cases()
    gaps = known_gaps()
    for case in generated:
        print(f"{case.id}{'   [known gap]' if case.id in gaps else ''}")
    by_kind = {k: sum(1 for c in generated if c.kind == k) for k in KINDS}
    print(f"\n{len(generated)} cases generated: " + ", ".join(f"{v} {k}" for k, v in by_kind.items())
          + f"; {len(gaps)} known gaps", file=sys.stderr)
