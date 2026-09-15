---
id: feature-signature-verification
title: Proving the webhook is genuine
type: feature
status: draft
owner: unassigned
involved_services:
  - xyk-server
client_entries: []
api:
  - endpoint-ingest
  - endpoint-admin
tags: [security, ingest]
---

# Proving the webhook is genuine

> **Built at B-09 (2026-09-15).** All five schemes exist, with their vectors recorded from
> `openssl`. The per-endpoint tolerance can be set at creation and not yet changed by `PATCH`.

## 1. Overview

A webhook URL is a public write endpoint. The only thing standing between it and anyone who guesses
the id is the proof that the sender knows a secret — and every vendor invents that proof
differently. This feature is the small set of schemes xyk understands, the rules each of them
imposes, and the one property they all share: **the subject of the proof is the raw bytes, exactly
as they arrived.**

The three schemes named in the brief were read from the vendors' own documentation on 2026-09-15 and
recorded in [research §1.4](../research/research-architecture.md), with the addresses. Nothing here
is written from memory, because this is precisely the kind of detail that memory gets subtly wrong —
a prefix, a separator, an order of concatenation — and the failure is a `401` on genuine traffic.

## 2. Business rules

* **`github`** — HMAC-SHA256 over the raw body, keyed with the endpoint secret; compared against the
  hex digest in `X-Hub-Signature-256` after its `sha256=` prefix.
* **`stripe`** — the signed payload is `"<t>" + "." + raw body`, where `t` comes from the
  `Stripe-Signature` header; HMAC-SHA256; **every scheme that is not `v1` is ignored**, which is a
  downgrade defence and not an optimisation; **several `v1` values may be present** while a secret is
  being rolled, and any one of them matching is a pass; the timestamp must be within the tolerance.
* **`telegram`** — `X-Telegram-Bot-Api-Secret-Token` is compared to the stored secret. It proves the
  sender knows a secret and says **nothing about the body**; the journal records which scheme passed
  so that this is visible later.
* **`hmac-sha256`** — the generic scheme for everyone else: a configured header, an optional prefix,
  a hex or base64 digest over the raw body.
* **`none`** — accepts anything. Creating such an endpoint requires `XYK_ALLOW_UNVERIFIED=true`.
* **Every comparison of secret material is constant-time**, including the negative paths.
* **A failure names its reason**: missing header, invalid signature, stale timestamp. Three distinct
  incidents, three distinct codes.
* **Verification runs before the body is stored.** A rejected request leaves nothing behind but a
  counter — otherwise the endpoint is a free write endpoint after all.

## 3. Flow

1. The endpoint row names a scheme and holds one or more active secrets.
2. The verifier is handed `(headers, raw bytes, now)` — never `(body, secret)`, because Stripe needs
   the clock and Telegram needs no body at all.
3. It returns a verdict: which scheme passed, which secret fingerprint matched, or why it failed.
4. The verdict is stored on the event, so the journal can say *how* an event was trusted.

## 4. Code anchors

| Service | Code |
|---|---|
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/verify/Verifier.kt` — the interface: request in, verdict out |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/verify/GithubVerifier.kt` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/verify/StripeVerifier.kt` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/verify/TelegramVerifier.kt` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/verify/ConstantTime.kt` |
| xyk-server | `server/src/commonTest/kotlin/io/github/youndie/xyk/verify/VectorsTest.kt` — recorded vendor payloads |
| xyk-twin-go | `twin-go/verify.go` — the same schemes, on `crypto/hmac`, for the comparison |

## 5. Scenarios (BDD / test cases)

**Every scenario below is a *target*.** The vectors they run against are recorded requests, captured
once and checked in — a scheme verified only against signatures this codebase produced itself proves
that it agrees with itself.

### Scenario: a GitHub signature is accepted

* **Given:** an endpoint with scheme `github` and secret `s`
* **When:** a body arrives with `X-Hub-Signature-256: sha256=<HMAC-SHA256(s, body) in hex>`
* **Then:** the request is accepted and the event records `scheme=github`
* **Automated:** `VectorsTest`

### Scenario: a body altered by one byte is refused

* **Given:** the same endpoint and a valid recorded request
* **When:** one byte of the body is changed, the signature left as it was
* **Then:** the response is `401` with `{"error":"signature invalid"}`
* **And:** no event row is written
* **Automated:** `VectorsTest` for the refusal, `AcceptEventTest` for nothing being stored

### Scenario: Stripe's signed payload includes the timestamp

* **Given:** an endpoint with scheme `stripe` and secret `whsec_...`
* **When:** `Stripe-Signature: t=<now>,v1=<HMAC over "<now>.<body>">` arrives
* **Then:** the request is accepted
* **And:** a signature computed over the body **alone** is refused — the concatenation is part of
  the contract, not a detail
* **Automated:** `VectorsTest`

### Scenario: a Stripe signature older than the tolerance is stale, not invalid

* **Given:** tolerance of 300 seconds
* **When:** a correctly signed request arrives with `t` 400 seconds in the past
* **Then:** the response is `401` with `{"error":"signature stale"}` — distinct from an invalid
  signature, because the incident is a clock and not a secret
* **Automated:** `VectorsTest`

### Scenario: a non-v1 scheme never verifies anything

* **Given:** a request whose header carries only `t=<now>,v0=<a digest that would match>`
* **When:** it arrives at a `stripe` endpoint
* **Then:** the response is `401` with `{"error":"signature invalid"}`
* **Automated:** `VectorsTest`

### Scenario: both secrets work during a rotation window

* **Given:** an endpoint whose secret was rotated, old and new both active
* **When:** two requests arrive, one signed with each
* **Then:** both are accepted, and the journal records different secret fingerprints
* **Automated:** `RegistryTest`

### Scenario: a Telegram secret token is compared, not verified

* **Given:** an endpoint with scheme `telegram`
* **When:** a request arrives with the right `X-Telegram-Bot-Api-Secret-Token` and an arbitrary body
* **Then:** it is accepted, and the event records `scheme=telegram`
* **And:** the journal shows the scheme next to the event rather than a bare "verified" badge
* **Automated:** `VectorsTest`

### Scenario: an endpoint with no verification cannot be created by accident

* **Given:** `XYK_ALLOW_UNVERIFIED` unset
* **When:** an endpoint is created with scheme `none`
* **Then:** the response is `400` with `{"error":"scheme none is disabled"}`

## 6. Out of scope

* **Verifying the sender's IP.** Stripe publishes a list and recommends both; an allowlist that has
  to be kept current is a way to reject genuine traffic on a Tuesday. If it is added, it is
  configuration on the endpoint, never a default.
* **mTLS, JWT-bearing webhooks, and vendor-specific asymmetric schemes.** The generic HMAC scheme
  covers the common case; a new scheme is a new class next to the three named here.
* **Encrypting stored payloads.** Tracked separately at [B-19](../backlog/B-19-secret-handling.md).

## 7. Quirks

* **Telegram's scheme is not a signature**, and calling it one in a UI would overstate what was
  proved. It appears by name.
* **A tolerance of `0` disables Stripe's recency check** rather than tightening it — that is the
  vendor's own wording, and the configuration refuses the value.
* **The verifier sees headers case-insensitively but the documentation writes them as the vendor
  does.** Anyone comparing this file with a packet capture should see the same spelling.
* **kotlincrypto ships no constant-time comparison**, so ours is hand-written; the risk is not that
  it is wrong but that some path stops calling it, which no timing test would notice.
