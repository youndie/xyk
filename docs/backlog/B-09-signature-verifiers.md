---
id: B-09
title: "Five schemes, recorded vendor vectors, and one constant-time compare"
status: done
priority: P0
size: M
stage: stage-1-product
epic: feature-signature-verification
blocked_by: [B-06]
---

# B-09 — Five schemes, recorded vendor vectors, and one constant-time compare

`github`, `stripe`, `telegram`, a generic `hmac-sha256`, and `none`. The details are not
interchangeable and are not written from memory: the headers, prefixes and concatenations were read
from each vendor's documentation on 2026-09-15 and recorded with their addresses in
[research §1.4](../research/research-architecture.md).

- **Decision: the verifier takes `(headers, raw bytes, now)`.** Anything narrower cannot express
  Stripe, which signs `"<t>.<body>"`, carries several candidate signatures during a rotation, and
  rejects a timestamp outside a tolerance.
- **Decision: every scheme that is not `v1` is ignored on the Stripe path.** It is the vendor's own
  downgrade defence, and the `v0` value Stripe sends for test events would otherwise be a way in.
- **Decision: the vectors are recorded requests, captured once and checked in.** A scheme verified
  only against signatures this codebase produced proves that the code agrees with itself.
- **Decision: HMAC comes from `org.kotlincrypto.macs:hmac-sha2`** — already used on `linuxX64` by two
  other repositories here, so no OpenSSL cinterop and nothing that `dlopen`s on the ingest path.
- The constant-time compare is hand-written; the risk is not that it is wrong but that a path stops
  calling it, which no timing test would notice. A review item, named here so it is not forgotten.
- Not covered: IP allowlists, asymmetric schemes, mTLS.

- AC: every scenario of
  [feature-signature-verification](../features/feature-signature-verification.md) passes. **Done**,
  as tests on both targets and again through HTTP against the running binary.
- AC: a mutation of one byte anywhere in a recorded vector — body, timestamp, digest — is refused.
  **Done** — four mutations of the GitHub vector (a trailing space on the body, the last hex digit,
  the algorithm prefix, no prefix at all), the Stripe body-only digest, a `base64` endpoint offered
  the `hex` spelling, and the tolerance in both directions.

## Closed 2026-09-15

Five schemes: `github`, `stripe`, `telegram`, `hmac-sha256`, `none`.

**The vectors come from `openssl`, and the command that produced each one is written beside it in
`VectorsTest`.** That is the whole value of them: a scheme checked only against digests this codebase
computed proves that the code agrees with itself — which it would do just as convincingly with
Stripe's concatenation the wrong way round. The test for that mistake is explicit: the digest over
the body *alone* is recorded, and must be refused.

Through HTTP, against the binary:

| | |
|---|---|
| Stripe, fresh `t`/`v1` | `200` |
| Stripe, `t` 1000 s old, tolerance 300 | `401 {"error":"signature stale"}` |
| Stripe, the passing digest offered as `v0` | `401 {"error":"signature invalid"}` |
| Telegram, right and wrong token | `200`, `401` |
| generic endpoint created with no `schemeConfig.header` | `400 {"error":"scheme hmac-sha256 needs schemeConfig.header"}` |
| generic, base64 digest in its configured header | `200`; one byte appended gives `401` |

**Three decisions worth not re-deriving.**

1. **The tolerance is checked only after the signature matched.** Answering "stale" for a payload
   whose signature is wrong would tell an attacker which half they got right.
2. **`schemeConfig` is one bag with defaults, stored as JSON on the endpoint** (migration v2), not
   one interface per scheme: three of the five configure nothing, and four shapes would put a cast
   at every call site.
3. **An unconfigured generic endpoint is refused at creation**, because one that reached the ingest
   path would refuse every request it ever received — indistinguishable, from outside, from a wrong
   secret.

**Narrower than the documents in one place:** the Stripe tolerance is per-install
(`XYK_STRIPE_TOLERANCE_SECONDS`) and per-endpoint through `schemeConfig.toleranceSeconds`, but
`PATCH /api/endpoints/{id}` cannot change it yet — that route takes `enabled`, `secret` and
`description` only. Recorded in [endpoint-admin](../api/endpoint-admin.md).
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/verify/`,
  `server/src/commonTest/kotlin/io/github/youndie/xyk/verify/VectorsTest.kt`
