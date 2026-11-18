// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
//
// S2 — the full payment cycle (S5.1-AC2). Each iteration of each virtual user:
//
//   1. create          POST /api/v1/payment-request                 USDC on Solana       → 201
//   2. payload         POST /pub/api/v1/loc/{id}       signed by the payer (payreq)     → 200, signed
//   3. pre-commit      POST /pub/api/v1/payment-notification   PAYMENT_INITIATED        → 200, signed
//   4. post-commit     POST /pub/api/v1/payment-notification   SENT + transactionId     → 200, signed
//   5. status-update   PUT  /api/v1/payment-request/{id}/status-update   PAID           → 200
//
// The payer's messages are signed here, in k6, with WebCrypto, as the repository's TEST-CA payers
// (prepare.sh converts them; ES256 and PS512 alternate by VU). The certificate travels inline in
// x5c, so the service never fetches a payer certificate; it fetches only the test CA's CRL, from
// the distribution point the certificates name, which the deployment serves beside the service.
//
// Every signed answer is checked as a payer must check it (lib/jws.js verifyAnswer): the payee's
// certificate and the algorithm the campaign signs with (prepare.sh, SIGNING), x5t#S256, the signature, iat/ttl, the request's correlationId, the statusCode. A
// failed check is an error, counted in s2_errors{step,reason} and s2_signature_failures, and the
// cycle stops there. Same profile as S1 unless PROFILE/STAGES say otherwise.
//
//   PAYERS_PATH   payers.json from prepare.sh, default ../build/payers.json (next to this file)
//   PAYERS        which payers to use, default "ec,rsa"

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import { sign, verifyAnswer } from "./lib/jws.js";
import {
  BASE_URL, SYSTEM_TAGS, base64, notification, randomInt, solanaPaymentRequest, stages, thinkTime,
  transactionId, writeSummary,
} from "./lib/x9.js";

const material = JSON.parse(open(__ENV.PAYERS_PATH || "../build/payers.json"));
const wanted = (__ENV.PAYERS || "ec,rsa").split(",").map((name) => name.trim());
const PAYERS = material.payers.filter((payer) => wanted.includes(payer.name));
const PAYEE_CERTIFICATES = material.payeeCertificates;
// The algorithm every answer must be signed with (SIGNING: ES256 by default, PS512 with ps512).
const PAYEE_ALG = material.payeeAlg;
if (PAYERS.length === 0) throw new Error(`no payer among ${wanted} in payers.json`);

const STEPS = ["create", "payload", "pre_commit", "post_commit", "status_update"];
const durations = {};
for (const step of STEPS) durations[step] = new Trend(`s2_${step}`, true);
const cycleDuration = new Trend("s2_cycle", true);
const cycles = new Counter("s2_cycles_completed");
const cycleSuccess = new Rate("s2_cycle_success_rate");
const errors = new Counter("s2_errors");
const signatureFailures = new Counter("s2_signature_failures");

export const options = {
  stages: stages(),
  systemTags: SYSTEM_TAGS,
  thresholds: {
    http_req_duration: ["p(95)<2000"],
    s2_cycle_success_rate: ["rate>0.95"],
    http_req_failed: ["rate<0.05"],
    // A signed answer that does not check out is never tolerated.
    s2_signature_failures: ["count<1"],
  },
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)", "count"],
};

const JSON_HEADERS = { "Content-Type": "application/json" };
const JOSE_HEADERS = { "Content-Type": "application/jose" };

function fail(step, reason, response, detail) {
  errors.add(1, { step: step, reason: reason });
  if (reason !== "status" || !response || response.status !== 0) {
    console.warn(`VU ${__VU} iter ${__ITER} ${step}: ${reason} ${response ? response.status : ""} ${detail || ""}`
      .slice(0, 400));
  }
  return false;
}

function timed(step, response) {
  durations[step].add(response.timings.duration);
  return response;
}

// A signed answer: HTTP status, then the payer's checks. Returns the verified payload, or null.
async function signedAnswer(step, response, correlationId, bodyStatusCode) {
  if (!check(response, { [`${step}: 200`]: (r) => r.status === 200 })) {
    fail(step, "status", response, String(response.body).slice(0, 300));
    return null;
  }
  const verdict = await verifyAnswer(response.body, {
    payeeCertificates: PAYEE_CERTIFICATES,
    alg: PAYEE_ALG,
    correlationId: correlationId,
    statusCode: 200,
    bodyStatusCode: bodyStatusCode,
  });
  if (!check(verdict, { [`${step}: signed answer verifies`]: (v) => v.ok })) {
    signatureFailures.add(1, { step: step, reason: verdict.problem });
    fail(step, verdict.problem, response, verdict.detail);
    return null;
  }
  return verdict.payload;
}

async function cycle(payer) {
  const amount = randomInt(100, 99999);

  // 1. create
  const created = timed("create", http.post(`${BASE_URL}/api/v1/payment-request`,
    JSON.stringify(solanaPaymentRequest(__VU, __ITER, amount)),
    { headers: JSON_HEADERS, tags: { name: "POST /api/v1/payment-request" } }));
  let qr;
  try {
    qr = created.json();
  } catch (e) {
    qr = {};
  }
  if (!check(created, { "create: 201 with id, location and qrCode": (r) => r.status === 201 && qr.id && qr.location && qr.qrCode })) {
    return fail("create", "status", created, String(created.body).slice(0, 300));
  }

  // 2. the signed payload, fetched with a signed request
  const request = await sign(payer, { qrCodeContent: base64(qr.qrCode) });
  const payloadResponse = timed("payload", http.post(`${BASE_URL}/pub/api/v1/loc/${qr.location.id}`,
    request.token, { headers: JOSE_HEADERS, tags: { name: "POST /pub/api/v1/loc/{id}" } }));
  const payload = await signedAnswer("payload", payloadResponse, request.correlationId, false);
  if (payload === null) return false;
  if (!check(payload, { "payload: names the QR Code": (p) => p.id === qr.id })) {
    return fail("payload", "WRONG_PAYLOAD", payloadResponse, `id ${payload.id}`);
  }

  // 3. pre-commit, 4. post-commit
  const payerInfo = `k6-vu${__VU}@example.com`;
  for (const [step, txId] of [["pre_commit", null], ["post_commit", transactionId()]]) {
    const message = await sign(payer, notification(qr.id, amount, payerInfo, txId));
    const answer = timed(step, http.post(`${BASE_URL}/pub/api/v1/payment-notification`, message.token,
      { headers: JOSE_HEADERS, tags: { name: `POST /pub/api/v1/payment-notification (${step})` } }));
    if ((await signedAnswer(step, answer, message.correlationId, true)) === null) return false;
  }

  // 5. the payment system settles it
  const settled = timed("status_update", http.put(`${BASE_URL}/api/v1/payment-request/${qr.id}/status-update`,
    JSON.stringify({ status: "PAID", endToEndId: `E2E-${qr.id}`, network: "solana" }),
    { headers: JSON_HEADERS, tags: { name: "PUT /api/v1/payment-request/{id}/status-update" } }));
  if (!check(settled, { "status-update: 200": (r) => r.status === 200 })) {
    return fail("status_update", "status", settled, String(settled.body).slice(0, 300));
  }
  return true;
}

// Once, before the load: the payee's JWK set (where its answers' jku points) must hold the pinned
// certificate, so pinning it is the same as resolving the jku, minus one fetch per answer.
export function setup() {
  const response = http.get(`${BASE_URL}/pub/.well-known/jwks`, { tags: { name: "setup: jwks" } });
  let keys = [];
  try {
    keys = response.json().keys || [];
  } catch (e) {
    keys = [];
  }
  const published = keys.map((key) => (key.x5c || [])[0]).filter((c) => c);
  if (!published.some((c) => PAYEE_CERTIFICATES.includes(c))) {
    throw new Error(`the payee's JWK set (${response.status}) does not hold the pinned certificate`);
  }
}

export default async function () {
  const payer = PAYERS[__VU % PAYERS.length];
  const started = Date.now();
  const ok = await cycle(payer);
  cycleSuccess.add(ok);
  if (ok) {
    cycles.add(1);
    cycleDuration.add(Date.now() - started);
  }
  sleep(thinkTime());
}

export function handleSummary(data) {
  return writeSummary(data, "S2 payment cycle",
    ["s2_cycle"].concat(STEPS.map((step) => `s2_${step}`)),
    ["s2_cycles_completed", "s2_errors", "s2_signature_failures"]);
}
