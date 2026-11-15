// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
//
// The harness's own check of lib/jws.js, with no service: a token it signs verifies, and every
// way an answer can be wrong is refused with its code (S5.1-AC2: a failed check is an error).
// Each payer plays the payee here, its own certificate pinned. Exits non-zero on any failure.
//
//   k6 run others/benchmark/k6/selftest.js           # after prepare.sh

import exec from "k6/execution";
import { b64url, sign, verifyAnswer } from "./lib/jws.js";

const material = JSON.parse(open(__ENV.PAYERS_PATH || "../build/payers.json"));

export const options = { iterations: 1, vus: 1 };

function swapSegment(token, index, value) {
  const parts = token.split(".");
  parts[index] = value;
  return parts.join(".");
}

export default async function () {
  let failures = 0;
  const expect = (name, verdict, problem) => {
    const got = verdict.ok ? "VERIFIED" : verdict.problem;
    if (got !== problem) failures++;
    console.log(`${got === problem ? "ok  " : "FAIL"} ${name}: ${got}${got === problem ? "" : ` (expected ${problem})`}`);
  };

  for (const payer of material.payers) {
    const pinned = { payeeCertificates: [payer.x5c[0]], statusCode: 200, bodyStatusCode: true };
    const answer = await sign(payer, { statusCode: 200 }, { statusCode: 200, crit: ["correlationId", "iat", "ttl", "statusCode"] });
    const ok = Object.assign({ correlationId: answer.correlationId }, pinned);

    expect(`${payer.alg} a signed answer`, await verifyAnswer(answer.token, ok), "VERIFIED");
    expect(`${payer.alg} another correlationId`,
      await verifyAnswer(answer.token, Object.assign({}, ok, { correlationId: crypto.randomUUID() })),
      "CORRELATION_ID_MISMATCH");
    expect(`${payer.alg} an altered body`,
      await verifyAnswer(swapSegment(answer.token, 1, b64url(JSON.stringify({ statusCode: 201 }))), ok),
      "BAD_SIGNATURE");
    const signature = answer.token.split(".")[2];
    expect(`${payer.alg} an altered signature`,
      await verifyAnswer(swapSegment(answer.token, 2, (signature[0] === "A" ? "B" : "A") + signature.slice(1)), ok),
      "BAD_SIGNATURE");
    expect(`${payer.alg} another payee`,
      await verifyAnswer(answer.token, Object.assign({}, ok, { payeeCertificates: [payer.x5c[1]] })),
      "UNEXPECTED_PAYEE");
    const expired = await sign(payer, { statusCode: 200 },
      { statusCode: 200, crit: ["correlationId", "iat", "ttl", "statusCode"], iat: Date.now() - 600000, ttl: 60000 });
    expect(`${payer.alg} expired`,
      await verifyAnswer(expired.token, Object.assign({}, ok, { correlationId: expired.correlationId })), "EXPIRED");
    const noStatus = await sign(payer, { statusCode: 200 });
    expect(`${payer.alg} statusCode not in crit`,
      await verifyAnswer(noStatus.token, Object.assign({}, ok, { correlationId: noStatus.correlationId })), "BAD_HEADER");
    const wrongStatus = await sign(payer, { statusCode: 409 }, { statusCode: 409, crit: ["correlationId", "iat", "ttl", "statusCode"] });
    expect(`${payer.alg} another statusCode`,
      await verifyAnswer(wrongStatus.token, Object.assign({}, ok, { correlationId: wrongStatus.correlationId })), "STATUS_MISMATCH");
    // As the service sends it by default (endpoint-type JWK_SET): jku, no x5c.
    const byReference = await sign(payer, { statusCode: 200 },
      { statusCode: 200, crit: ["correlationId", "iat", "ttl", "statusCode"], x5c: undefined, jku: "https://payee.example.com/pub/.well-known/jwks" });
    expect(`${payer.alg} key by jku`,
      await verifyAnswer(byReference.token, Object.assign({}, ok, { correlationId: byReference.correlationId })), "VERIFIED");
    expect(`${payer.alg} not a JWS`, await verifyAnswer("not-a-jws", ok), "NOT_JWS");
  }
  if (failures > 0) exec.test.abort(`${failures} self-test case(s) failed`);
}
