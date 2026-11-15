// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
//
// What the scenarios share: the target, the load profile, request bodies and the summary file.
// The target is a parameter (BASE_URL), never a build: the same script drives the Java and the Go
// build, and nothing here knows which one answers.
//
// Environment:
//   BASE_URL      the service, default http://localhost:8080 (in-cluster: http://x9-qrcode:8080)
//   PROFILE       readme (default) | warmup | smoke — see PROFILES
//   STAGES        overrides PROFILE: "30s:50,30s:200,30s:500,3m:500,30s:0"
//   SLEEP_MIN_S / SLEEP_MAX_S   think time between iterations, default 1..3 s (the README script's)
//   SUMMARY_PATH  where handleSummary writes the full k6 summary JSON, default summary.json

import encoding from "k6/encoding";

export const BASE_URL = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/+$/, "");

// The README's profile (S5.1-AC1): ramp to 500 VUs, sustain 3 minutes.
export const PROFILES = {
  readme: [
    { duration: "30s", target: 50 },
    { duration: "30s", target: 200 },
    { duration: "30s", target: 500 },
    { duration: "3m", target: 500 },
    { duration: "30s", target: 0 },
  ],
  // The Java build's JIT warm-up, excluded from measurement (plan §9.2): 2 minutes.
  warmup: [
    { duration: "30s", target: 100 },
    { duration: "90s", target: 100 },
  ],
  // A harness check, not a measurement: a handful of users for under a minute.
  smoke: [
    { duration: "10s", target: 5 },
    { duration: "30s", target: 5 },
    { duration: "5s", target: 0 },
  ],
};

// k6's default system tags without `error`, whose transport messages can carry an address
// (S5.1-AC6: no IP in anything committed). `error_code` keeps the reason.
export const SYSTEM_TAGS = ["proto", "subproto", "status", "method", "url", "name", "group", "check",
  "error_code", "tls_version", "scenario", "service", "expected_response"];

export function stages() {
  if (__ENV.STAGES) {
    return __ENV.STAGES.split(",").map((stage) => {
      const [duration, target] = stage.trim().split(":");
      return { duration: duration, target: parseInt(target, 10) };
    });
  }
  const profile = __ENV.PROFILE || "readme";
  if (!PROFILES[profile]) throw new Error(`unknown PROFILE ${profile}`);
  return PROFILES[profile];
}

const SLEEP_MIN = parseFloat(__ENV.SLEEP_MIN_S || "1");
const SLEEP_MAX = parseFloat(__ENV.SLEEP_MAX_S || "3");

export function thinkTime() {
  return SLEEP_MIN + Math.random() * (SLEEP_MAX - SLEEP_MIN);
}

export function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

export function futureIso(seconds) {
  return new Date(Date.now() + seconds * 1000).toISOString();
}

// ─── S1: the README script's body (USD on FedNow), brought up to today's API ──────────────────
// The README script sends `editable: {payerCanEdit: false}` and no FedNow `protectionType`; the
// service now refuses both (400), so here `editable` is absent (the payer cannot edit) and
// `protectionType` is "plaintext". Everything else is the README script's.

const MCCS = ["4900", "5411", "5812", "7011", "5541", "5912"];

export function fedNowPaymentRequest(vu, iteration) {
  const amount = randomInt(1000, 99999);
  const validUntil = futureIso(randomInt(30, 90) * 86400);
  return {
    validUntil: validUntil,
    creditor: {
      name: `K6 Load Test Creditor ${vu}`,
      phone: `+1555${randomInt(1000000, 9999999)}`,
      email: `creditor-${vu}-${iteration}@example.com`,
      address: {
        line1: `${randomInt(100, 9999)} Performance Ave`,
        city: "Los Angeles",
        state: "CA",
        postalCode: "90012",
        country: "US",
      },
      MCC: MCCS[randomInt(0, MCCS.length - 1)],
    },
    bill: {
      description: `K6 load test bill VU-${vu} iter-${iteration}`,
      paymentTiming: "immediate",
      order: { number: `K6-${vu}-${iteration}`.substring(0, 20), date: new Date().toISOString().split("T")[0] },
      amountDue: { amount: amount, currency: "USD" },
    },
    paymentMethods: [{
      currency: "USD",
      validUntil: validUntil,
      amount: amount,
      networks: {
        fednow: {
          routingNumber: String(randomInt(100000000, 999999999)),
          accountNumber: String(randomInt(1000000000, 9999999999)),
          protectionType: "plaintext",
        },
      },
    }],
  };
}

// ─── S2: USDC on Solana, so a post-commit can carry a transactionId ───────────────────────────

// Well-formed base58 addresses that belong to nobody (the black-box suite's samples).
export const RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
export const PAYER_WALLET = "5xoBq7f7CDgZwqHrDBdRWM84ExRetg4gZq93dyJtoSwp";

export function solanaPaymentRequest(vu, iteration, amount) {
  const validUntil = futureIso(86400);
  return {
    validUntil: validUntil,
    creditor: {
      name: `K6 Cycle Creditor ${vu}`,
      address: { line1: "1 Benchmark St", city: "Los Angeles", country: "US" },
      MCC: "5411",
    },
    bill: {
      description: `K6 cycle VU-${vu} iter-${iteration}`,
      amountDue: { amount: amount, currency: "USDC" },
    },
    paymentNotification: { kind: "DEFAULT" },
    paymentMethods: [{
      currency: "USDC",
      validUntil: validUntil,
      amount: amount,
      networks: { solana: { recipient: RECIPIENT } },
    }],
  };
}

export function notification(qrId, amount, payerInfo, transactionId) {
  const payment = { qrcodeId: qrId, amount: amount, currency: "USDC", network: "solana" };
  if (transactionId) payment.transactionId = transactionId;
  return {
    payment: payment,
    payer: { info: payerInfo },
    blockchain: {
      action: transactionId ? "SENT" : "PAYMENT_INITIATED",
      from: PAYER_WALLET,
      to: RECIPIENT,
    },
  };
}

const BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

// A unique, well-formed Solana-style transaction signature (base58, 88 characters).
export function transactionId() {
  const bytes = crypto.getRandomValues(new Uint8Array(88));
  return Array.from(bytes, (b) => BASE58[b % 58]).join("");
}

// The EMV content is ASCII by construction.
export function base64(text) {
  return encoding.b64encode(text, "std");
}

// ─── the summary ─────────────────────────────────────────────────────────────────────────────

function value(data, metric, stat) {
  const m = data.metrics[metric];
  return m && m.values && m.values[stat] !== undefined ? m.values[stat] : null;
}

// The full k6 summary (every metric, p50/p95/p99) to SUMMARY_PATH, and a few lines to stdout.
export function writeSummary(data, scenario, trends, counters) {
  const lines = [`═══ ${scenario} against ${BASE_URL} ═══`];
  for (const trend of trends) {
    const fmt = (s) => (value(data, trend, s) === null ? "-" : value(data, trend, s).toFixed(1));
    lines.push(`  ${trend.padEnd(26)} p50=${fmt("med")} p95=${fmt("p(95)")} p99=${fmt("p(99)")} ms  n=${value(data, trend, "count") || 0}`);
  }
  for (const counter of counters) {
    lines.push(`  ${counter.padEnd(26)} ${value(data, counter, "count") || 0}`);
  }
  lines.push(`  http_reqs                  ${value(data, "http_reqs", "count") || 0} (${(value(data, "http_reqs", "rate") || 0).toFixed(1)}/s)`);
  lines.push(`  http_req_failed            ${((value(data, "http_req_failed", "rate") || 0) * 100).toFixed(2)}%`);
  return {
    stdout: lines.join("\n") + "\n",
    [__ENV.SUMMARY_PATH || "summary.json"]: JSON.stringify(data, null, 1),
  };
}
