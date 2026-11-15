// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
//
// X9.150 JWS for k6, on WebCrypto (the global crypto.subtle of k6 v2.4.0). Nothing else: no
// extension, no remote module, no sidecar.
//
//   sign(payer, payload)          a payer's compact JWS: typ JOSE, the chain in x5c, x5t#S256,
//                                 and correlationId, iat, ttl (milliseconds) in crit
//   verifyAnswer(token, expected) checks a payee's signed answer the way a payer must; returns
//                                 { ok, problem, detail, header, payload }
//
// Payers are the repository's TEST-CA payers, converted from PKCS#12 by prepare.sh (NON-PRODUCTION:
// their keys are public). Two things k6 v2.4.0 does and does not do, found while writing this:
//
//   - RSA-PSS (PS512) and ECDSA P-256 (ES256) sign and verify in crypto.subtle;
//   - an RSA public key imported as a JWK cannot verify ("given CryptoKey is not a public RSA
//     key"); one imported as SPKI can. So the payee's key is read out of its certificate's DER
//     (subjectPublicKeyInfo) and imported as "spki", for both algorithms.

import encoding from "k6/encoding";

export const CRIT = ["correlationId", "iat", "ttl"];
export const TTL_MS = 300000;
export const CLOCK_SKEW_MS = 60000; // the service's default x9.public-endpoints.jws-clock-skew-seconds

const ALGS = {
  PS512: {
    importAlg: { name: "RSA-PSS", hash: "SHA-512" },
    signAlg: { name: "RSA-PSS", saltLength: 64 },
    keyOid: "2a864886f70d010101", // rsaEncryption
  },
  ES256: {
    importAlg: { name: "ECDSA", namedCurve: "P-256" },
    signAlg: { name: "ECDSA", hash: "SHA-256" },
    keyOid: "2a8648ce3d0201", // id-ecPublicKey
  },
};

// ─── encoding ────────────────────────────────────────────────────────────────────────────────

export function b64url(data) {
  return encoding.b64encode(data, "rawurl");
}

function b64urlToString(text) {
  return encoding.b64decode(text, "rawurl", "s");
}

function b64urlToBytes(text) {
  return encoding.b64decode(text, "rawurl");
}

function stdToBytes(text) {
  return encoding.b64decode(text, "std");
}

function hex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

// ─── DER: the subjectPublicKeyInfo of an X.509 certificate ───────────────────────────────────

function tlv(bytes, offset) {
  const tag = bytes[offset];
  let length = bytes[offset + 1];
  let header = 2;
  if (length & 0x80) {
    const n = length & 0x7f;
    length = 0;
    for (let i = 0; i < n; i++) length = length * 256 + bytes[offset + 2 + i];
    header += n;
  }
  return { tag, start: offset, body: offset + header, end: offset + header + length };
}

function children(bytes, node) {
  const out = [];
  for (let at = node.body; at < node.end; ) {
    const child = tlv(bytes, at);
    out.push(child);
    at = child.end;
  }
  return out;
}

// Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signature }
// tbsCertificate ::= SEQUENCE { [0] version OPTIONAL, serialNumber, signature, issuer, validity,
//                               subject, subjectPublicKeyInfo, ... }
export function subjectPublicKeyInfo(certificateDer) {
  const bytes = new Uint8Array(certificateDer);
  const certificate = tlv(bytes, 0);
  const tbs = tlv(bytes, certificate.body);
  const fields = children(bytes, tbs);
  const spki = fields[fields[0].tag === 0xa0 ? 6 : 5];
  const algorithm = children(bytes, children(bytes, spki)[0]);
  const oid = algorithm[0];
  return {
    der: bytes.slice(spki.start, spki.end).buffer,
    keyOid: hex(bytes.slice(oid.body, oid.end)),
  };
}

// ─── payers ──────────────────────────────────────────────────────────────────────────────────

const privateKeys = {};

async function privateKey(payer) {
  if (!privateKeys[payer.name]) {
    privateKeys[payer.name] = await crypto.subtle.importKey(
      "pkcs8", stdToBytes(payer.pkcs8), ALGS[payer.alg].importAlg, false, ["sign"]);
  }
  return privateKeys[payer.name];
}

// A payer's compact JWS of `payload`. Returns { token, correlationId }.
export async function sign(payer, payload, extraHeader) {
  const correlationId = crypto.randomUUID();
  const header = Object.assign({
    alg: payer.alg,
    typ: "JOSE",
    x5c: payer.x5c,
    "x5t#S256": payer.x5tS256,
    crit: CRIT,
    correlationId: correlationId,
    iat: Date.now(),
    ttl: TTL_MS,
  }, extraHeader || {});
  const input = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`;
  const signature = await crypto.subtle.sign(
    ALGS[payer.alg].signAlg, await privateKey(payer), new TextEncoder().encode(input));
  return { token: `${input}.${b64url(signature)}`, correlationId: correlationId };
}

// ─── the payee's answers ─────────────────────────────────────────────────────────────────────

const payeeKeys = {}; // `${alg}:${x5c[0]}` -> CryptoKey

async function payeeKey(certificate, alg) {
  const cacheKey = `${alg}:${certificate}`;
  if (!payeeKeys[cacheKey]) {
    const spki = subjectPublicKeyInfo(stdToBytes(certificate));
    if (spki.keyOid !== ALGS[alg].keyOid) return null;
    payeeKeys[cacheKey] = await crypto.subtle.importKey(
      "spki", spki.der, ALGS[alg].importAlg, false, ["verify"]);
  }
  return payeeKeys[cacheKey];
}

const thumbprints = {};

async function thumbprint(certificate) {
  if (!thumbprints[certificate]) {
    thumbprints[certificate] = b64url(await crypto.subtle.digest("SHA-256", stdToBytes(certificate)));
  }
  return thumbprints[certificate];
}

function refused(problem, detail, header, payload) {
  return { ok: false, problem: problem, detail: detail, header: header, payload: payload };
}

// Verifies a payee's signed answer as a payer must, in the X9.150 profile:
//   a compact JWS; alg PS512 or ES256; crit names correlationId, iat, ttl (and statusCode when
//   `expected.statusCode` is given), each present; the signing certificate (x5c[0], or the one
//   x5t#S256 names when the key is given by jku/x5u) is one of `expected.payeeCertificates`;
//   x5t#S256 names it; the signature verifies with its key; iat <= now + skew and now < iat + ttl;
//   correlationId is the request's; statusCode in the header and in the body is the expected one.
// `problem` uses the service's own SignatureProblem codes where one fits, plus
// CORRELATION_ID_MISMATCH, STATUS_MISMATCH and UNEXPECTED_PAYEE.
export async function verifyAnswer(token, expected) {
  if (typeof token !== "string" || token.split(".").length !== 3) {
    return refused("NOT_JWS", `not a compact JWS: ${String(token).slice(0, 80)}`);
  }
  const [h, p, s] = token.split(".");
  let header;
  let payload;
  try {
    header = JSON.parse(b64urlToString(h));
    payload = JSON.parse(b64urlToString(p));
  } catch (e) {
    return refused("NOT_JWS", `undecodable: ${e}`);
  }

  if (!header.alg || header.alg === "none") return refused("ALG_NONE", "unsecured", header, payload);
  if (!ALGS[header.alg]) return refused("BAD_HEADER", `alg ${header.alg}`, header, payload);
  const crit = Array.isArray(header.crit) ? header.crit : [];
  const required = expected.statusCode === undefined ? CRIT : CRIT.concat(["statusCode"]);
  for (const name of required) {
    if (!crit.includes(name)) return refused("BAD_HEADER", `crit lacks ${name}`, header, payload);
  }
  for (const name of crit) {
    if (!(name in header)) return refused("BAD_HEADER", `crit names ${name}, absent`, header, payload);
  }

  // The signing certificate. The service as shipped (endpoint-type JWK_SET) names its key by
  // reference, `jku` plus x5t#S256, rather than inline in x5c. A payer resolves the jku once and
  // keeps the certificate; k6 has it already (pinned by prepare.sh, and checked against the
  // service's own JWK set in s2-cycle.js's setup), so no answer costs a fetch. Either way,
  // x5t#S256 must name a pinned payee certificate, and that certificate's key must verify.
  const x5c = Array.isArray(header.x5c) ? header.x5c : [];
  let certificate = null;
  if (x5c.length > 0) {
    if (!expected.payeeCertificates.includes(x5c[0])) {
      return refused("UNEXPECTED_PAYEE", "x5c[0] is not the payee's certificate", header, payload);
    }
    certificate = x5c[0];
  } else if (typeof header.jku === "string" || typeof header.x5u === "string") {
    for (const candidate of expected.payeeCertificates) {
      if (header["x5t#S256"] === (await thumbprint(candidate))) certificate = candidate;
    }
    if (certificate === null) {
      return refused("UNEXPECTED_PAYEE", "x5t#S256 names no payee certificate", header, payload);
    }
  } else {
    return refused("BAD_HEADER", "no x5c, jku or x5u", header, payload);
  }
  if (header["x5t#S256"] !== (await thumbprint(certificate))) {
    return refused("UNTRUSTED_CHAIN", "x5t#S256 does not name the signing certificate", header, payload);
  }

  const key = await payeeKey(certificate, header.alg);
  if (key === null) return refused("BAD_SIGNATURE", `${header.alg} on another key type`, header, payload);
  const verified = await crypto.subtle.verify(
    ALGS[header.alg].signAlg, key, b64urlToBytes(s), new TextEncoder().encode(`${h}.${p}`));
  if (!verified) return refused("BAD_SIGNATURE", "the signature does not verify", header, payload);

  const now = Date.now();
  if (header.iat > now + CLOCK_SKEW_MS) return refused("FUTURE_IAT", `iat ${header.iat}`, header, payload);
  if (now >= header.iat + header.ttl) return refused("EXPIRED", `iat ${header.iat} ttl ${header.ttl}`, header, payload);

  if (header.correlationId !== expected.correlationId) {
    return refused("CORRELATION_ID_MISMATCH",
      `sent ${expected.correlationId}, answered ${header.correlationId}`, header, payload);
  }
  if (expected.statusCode !== undefined) {
    if (header.statusCode !== expected.statusCode) {
      return refused("STATUS_MISMATCH", `header statusCode ${header.statusCode}`, header, payload);
    }
    if (expected.bodyStatusCode && payload.statusCode !== expected.statusCode) {
      return refused("STATUS_MISMATCH", `body statusCode ${payload.statusCode}`, header, payload);
    }
  }
  return { ok: true, header: header, payload: payload };
}
