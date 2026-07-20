import http from "k6/http";
import { check, sleep } from "k6";
import { randomIntBetween } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { Counter, Rate, Trend } from "k6/metrics";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.1.0/index.js";
import { htmlReport } from "https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js";

// ─── Custom Metrics ────────────────────────────────────────────────
const qrcodesCreated = new Counter("qrcodes_created");
const qrcodeErrors = new Counter("qrcode_errors");
const qrcodeRate = new Rate("qrcode_success_rate");
const qrcodeDuration = new Trend("qrcode_creation_duration", true);

// ─── Configuration ─────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

// ─── Stages ────────────────────────────────────────────────────────
export const options = {
  stages: [
    { duration: "30s", target: 50 },   // ramp-up
    { duration: "30s", target: 200 },  // ramp to 200 VUs
    { duration: "30s", target: 1000 },  // ramp to 500 VUs
    { duration: "3m", target: 1000 },   // sustain 500 VUs
    { duration: "30s", target: 0 },    // ramp-down
  ],
  thresholds: {
    http_req_duration: ["p(95)<2000"],    // 95% of requests under 2s
    qrcode_success_rate: ["rate>0.95"],   // 95%+ success rate
    http_req_failed: ["rate<0.05"],       // less than 5% HTTP errors
  },
};

// ─── Helpers ───────────────────────────────────────────────────────
function futureDate(daysFromNow) {
  const d = new Date();
  d.setDate(d.getDate() + daysFromNow);
  return d.toISOString();
}

function futureLocalDate(daysFromNow) {
  const d = new Date();
  d.setDate(d.getDate() + daysFromNow);
  return d.toISOString().split("T")[0];
}

function randomAmount() {
  return randomIntBetween(1000, 99999);
}

function randomRoutingNumber() {
  return String(randomIntBetween(100000000, 999999999));
}

function randomAccountNumber() {
  return String(randomIntBetween(1000000000, 9999999999));
}

function randomMCC() {
  const codes = ["4900", "5411", "5812", "7011", "5541", "5912"];
  return codes[randomIntBetween(0, codes.length - 1)];
}

// ─��─ Build Request Payload ─────────────────────────────────────────
function buildPayload(vuId, iteration) {
  const amount = randomAmount();
  const dueDate = futureDate(randomIntBetween(5, 60));
  const orderDate = futureLocalDate(0);
  const validUntil = futureDate(randomIntBetween(30, 90));

  return JSON.stringify({
    validUntil: validUntil,
    creditor: {
      name: `K6 Load Test Creditor ${vuId}`,
      phone: `+1555${String(randomIntBetween(1000000, 9999999))}`,
      email: `creditor-${vuId}-${iteration}@loadtest.x9.dev`,
      address: {
        line1: `${randomIntBetween(100, 9999)} Performance Ave`,
        city: "Los Angeles",
        state: "CA",
        postalCode: "90012",
        country: "US",
      },
      MCC: randomMCC(),
    },
    bill: {
      description: `K6 load test bill VU-${vuId} iter-${iteration}`,
      paymentTiming: "immediate",
      order: {
        number: `K6-${vuId}-${iteration}`.substring(0, 20),
        date: orderDate,
      },
      amountDue: {
        amount: amount,
        currency: "USD",
      },
    },
    paymentMethods: [
      {
        currency: "USD",
        validUntil: validUntil,
        amount: amount,
        editable: {
          payerCanEdit: false,
        },
        networks: {
          FedNow: {
            routingNumber: randomRoutingNumber(),
            accountNumber: randomAccountNumber(),
          },
        },
      },
    ],
  });
}

// ─── Main Test ─────────────────────────────────────────────────────
export default function () {
  const payload = buildPayload(__VU, __ITER);

  const headers = {
    "Content-Type": "application/json",
  };

  const res = http.post(`${BASE_URL}/api/v1/payment-request`, payload, {
    headers: headers,
    tags: { name: "CreateQRCode" },
  });

  qrcodeDuration.add(res.timings.duration);

  const success = check(res, {
    "status is 201": (r) => r.status === 201,
    "response has id": (r) => {
      try {
        return JSON.parse(r.body).id !== undefined;
      } catch {
        return false;
      }
    },
    "response has qrCode": (r) => {
      try {
        return JSON.parse(r.body).qrCode !== undefined;
      } catch {
        return false;
      }
    },
  });

  if (success) {
    qrcodesCreated.add(1);
    qrcodeRate.add(1);
  } else {
    qrcodeErrors.add(1);
    qrcodeRate.add(0);
    if (res.status !== 201) {
      console.warn(
        `VU ${__VU} iter ${__ITER}: status=${res.status} body=${res.body}`
      );
    }
  }

  sleep(randomIntBetween(1, 3));
}

// ─── Summary ───────────────────────────────────────────────────────
export function handleSummary(data) {
  const duration = data.metrics.qrcode_creation_duration;
  const summary = {
    total_requests: data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
    qrcodes_created: data.metrics.qrcodes_created ? data.metrics.qrcodes_created.values.count : 0,
    errors: data.metrics.qrcode_errors ? data.metrics.qrcode_errors.values.count : 0,
    avg_duration_ms: duration && duration.values.avg != null
      ? duration.values.avg.toFixed(2)
      : "N/A",
    p95_duration_ms: duration && duration.values["p(95)"] != null
      ? duration.values["p(95)"].toFixed(2)
      : "N/A",
    p99_duration_ms: duration && duration.values["p(99)"] != null
      ? duration.values["p(99)"].toFixed(2)
      : "N/A",
  };

  console.log("\n═══ X9 QRCode Load Test Summary ═══");
  console.log(`  Total Requests:   ${summary.total_requests}`);
  console.log(`  QRCodes Created:  ${summary.qrcodes_created}`);
  console.log(`  Errors:           ${summary.errors}`);
  console.log(`  Avg Duration:     ${summary.avg_duration_ms} ms`);
  console.log(`  P95 Duration:     ${summary.p95_duration_ms} ms`);
  console.log(`  P99 Duration:     ${summary.p99_duration_ms} ms`);
  console.log("═══════════════════════════════════\n");

  // Generate timestamp filename: YYYY-MM-DDTHH:mm:ss (colons replaced with hyphens for file safety)
  const now = new Date();
  const timestamp = now.toISOString().slice(0, 19).replace(/:/g, "-"); // 2026-03-20T11-54-04
  const resultsFolder = "others/scripts/k6/results";
  const prefix = __ENV.REPORT_PREFIX || "summary";

  return {
    stdout: textSummary(data, { indent: "  ", enableColors: true }),
    [`${resultsFolder}/${prefix}-${timestamp}.json`]: JSON.stringify(data, null, 2),
    [`${resultsFolder}/${prefix}-${timestamp}.html`]: htmlReport(data),
  };
}
