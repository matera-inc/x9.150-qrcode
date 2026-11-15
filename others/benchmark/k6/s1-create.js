// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
//
// S1 — create only (S5.1-AC1). Each virtual user creates QR Codes, with the `readme` profile:
// ramp to 500 VUs, sustain 3 minutes, think time 1–3 s. The body is the one the retired README load
// script (formerly others/scripts/k6/) sent, USD on FedNow, so S1 continues it, with the two fields
// today's API refuses in it corrected (lib/x9.js says which).
//
//   k6 run -e BASE_URL=http://x9-qrcode:8080 others/benchmark/k6/s1-create.js
//   k6 run -e PROFILE=smoke ...                 # a harness check, not a measurement

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import { BASE_URL, SYSTEM_TAGS, fedNowPaymentRequest, stages, thinkTime, writeSummary } from "./lib/x9.js";

const created = new Counter("s1_created");
const errors = new Counter("s1_errors");
const success = new Rate("s1_success_rate");
const createDuration = new Trend("s1_create", true);

export const options = {
  stages: stages(),
  systemTags: SYSTEM_TAGS,
  // The README's thresholds. A crossed threshold is recorded (k6 exits 99); it does not stop a run.
  thresholds: {
    http_req_duration: ["p(95)<2000"],
    s1_success_rate: ["rate>0.95"],
    http_req_failed: ["rate<0.05"],
  },
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)", "count"],
};

export default function () {
  const response = http.post(`${BASE_URL}/api/v1/payment-request`,
    JSON.stringify(fedNowPaymentRequest(__VU, __ITER)),
    { headers: { "Content-Type": "application/json" }, tags: { name: "POST /api/v1/payment-request" } });
  createDuration.add(response.timings.duration);

  const ok = check(response, {
    "create: 201": (r) => r.status === 201,
    "create: id and qrCode": (r) => {
      try {
        const body = r.json();
        return body.id !== undefined && body.qrCode !== undefined;
      } catch (e) {
        return false;
      }
    },
  });
  success.add(ok);
  if (ok) {
    created.add(1);
  } else {
    errors.add(1);
    console.warn(`VU ${__VU} iter ${__ITER}: create ${response.status} ${String(response.body).slice(0, 300)}`);
  }
  sleep(thinkTime());
}

export function handleSummary(data) {
  return writeSummary(data, "S1 create", ["s1_create", "http_req_duration"], ["s1_created", "s1_errors"]);
}
