---
id: feature-ingest
title: Accepting a webhook
type: feature
status: active
owner: unassigned
involved_services:
  - xyk-server
client_entries: []
api:
  - endpoint-ingest
tags: [ingest, throughput]
---

# Accepting a webhook

> **Built.** The route, the verification, the single transaction and the storage answer exactly the
> statuses below — checked through real HTTP with a signature computed by `openssl` rather than by
> our own HMAC agreeing with itself. The transaction writes the event row, one **delivery** row per
> enabled subscriber **and one chronik timer per delivery row**, which is what makes a delivery
> happen at all ([B-26](../backlog/B-26-schedule-timers-on-ingest.md)); a row committed without its
> timer would be a webhook answered `200` that nobody will ever send. All five schemes exist
> ([B-09](../backlog/B-09-signature-verifiers.md)) and endpoints are rows rather than configuration
> ([B-07](../backlog/B-07-endpoint-registry.md)).

## 1. Overview

Somebody's server sends an HTTP POST. xyk decides whether it is genuine, writes the bytes down
together with the timers that will deliver them, and answers `200`. That is the whole feature, and
everything interesting about it is in the word *together*: an event that is stored without its timer
is never delivered, and a timer without its event is a delivery of nothing. Both are silent, and
both are what a webhook gateway is bought to prevent.

This is also the half the declared throughput criterion measures: 2 000 requests per second across
200 connections, with no slow state. Nothing here may be lazy, batched or deferred, because the
`200` is a promise that the transaction committed.

`client_entries: []` — there is no client. The senders are other people's servers.

## 2. Business rules

* The body is read **once**, as bytes, and stored **unchanged**. Nothing on this path parses it.
* A request is accepted only if the endpoint exists, is enabled, and its scheme verifies the request
  ([feature-signature-verification](feature-signature-verification.md)).
* The event row and one timer per enabled subscriber are written in **one** transaction. If any part
  fails, the answer is `500` and nothing is stored — a partial accept is worse than a refusal,
  because the sender's own retry would then duplicate the half that succeeded.
* `200` is written after that transaction commits, never before.
* A body above `XYK_MAX_BODY_BYTES` is refused with `413` **without being read into memory** — the
  limit is a defence, and a defence that buffers first defends nothing.
* An endpoint with no enabled subscribers still stores the event. The journal is a product, not a
  side effect of delivery.
* xyk does **not** deduplicate. A sender that redelivers produces a second event with its own id.
  See §6.

## 3. Flow

1. `POST /hooks/{endpointId}` arrives.
2. The endpoint is looked up. Unknown or disabled → `404`.
3. The body is read to a byte array, bounded by the configured maximum.
4. The endpoint's scheme verifies `(headers, bytes, now)`. Failure → `401` with the reason.
5. In one transaction: insert the event; for each enabled subscriber, `chronik.schedule(tx, ...)`
   with `at = now` so the next worker tick claims it.
6. Commit, then answer `200` with the event id.

There are no cross-service calls: the whole path is in one process, against one SQLite file.

## 4. Code anchors

| Service | Code |
|---|---|
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/ingest/` — the routing, the use case, the repository |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/contract/IngestResource.kt` — the typed resource |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/db/Migrate.kt` — `events`, `deliveries`, `timers` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/TimerScheduler.kt` — the timer written inside the caller's transaction |
| xyk-twin-go | `twin-go/main.go` — the same path, for the second column |

## 5. Scenarios (BDD / test cases)

**An `Automated:` line means a test runs the scenario; its absence means it is checked by hand or
not at all, and that asymmetry is worth seeing.** The
`**Automated:**` line is added to each as the test that covers it appears.

### Scenario: a genuine webhook is accepted and stored

* **Given:** an enabled endpoint with scheme `github` and a known secret
* **And:** two enabled subscribers
* **When:** a POST arrives with a body and a matching `X-Hub-Signature-256`
* **Then:** the response is `200` with `{"event":"<id>"}`
* **And:** one row exists in `events` whose stored bytes are byte-identical to the request body
* **And:** two deliveries exist in state `pending` (and, from B-03, two timers due now)
* **Automated:** `AcceptEventTest`

### Scenario: the timer and the event commit together, or not at all

* **Given:** the same endpoint, and a store rigged to fail on the second timer insert
* **When:** a genuine webhook arrives
* **Then:** the response is `500` with `{"error":"not stored"}`
* **And:** no row exists in `events` for that request
* **And:** no timer exists
* *(Manual: rigging the store to fail on the second insert needs a seam the repository does not have
  yet. The transaction is real — the tests above show event and deliveries arriving together — but
  "and not at all" is unproven until something makes it fail.)*

### Scenario: an unknown endpoint is indistinguishable from a disabled one

* **Given:** endpoint `A` disabled, endpoint `B` never created
* **When:** a POST arrives at each
* **Then:** both answer `404` with `{"error":"unknown endpoint"}`, and the responses are identical
  byte for byte
* *(Half automated: `AcceptEventTest` covers the unknown id. "Disabled" needs a way to disable one,
  which arrives with [B-07](../backlog/B-07-endpoint-registry.md).)*

### Scenario: an oversized body is refused without being buffered

* **Given:** `XYK_MAX_BODY_BYTES` of 1 MiB
* **When:** a POST arrives declaring 64 MiB
* **Then:** the response is `413`
* **And:** the process's resident memory does not rise by more than a small constant over the run
* *(Measured at B-08 rather than automated: `VmRSS` 42 880 kB → 46 560 kB over three 64 MiB bodies.
  A test would have to read `/proc`, which is one platform's answer to a question the suite asks on
  two.)*

### Scenario: an endpoint with no subscribers still records the event

* **Given:** an enabled endpoint with every subscriber disabled
* **When:** a genuine webhook arrives
* **Then:** the response is `200`
* **And:** the event appears in the journal with state `delivered` and zero attempts — *decision:* an
  event nobody is waiting for is complete, not pending
* **Automated:** `AcceptEventTest`

### Scenario: a redelivery by the sender becomes a second event

* **Given:** a webhook that was already accepted
* **When:** the same bytes and the same signature arrive again
* **Then:** the response is `200` with a **different** event id
* **And:** both events are visible in the journal
* **Automated:** `AcceptEventTest`

### Scenario: 2 000 rps across 200 connections, no slow state *(criterion)*

* **Given:** the released image under its declared limit, an open-model generator off-host
* **When:** 2 000 requests per second are offered for the declared duration across 200 connections
* **Then:** `dropped_iterations` is zero, no response is above the declared latency ceiling, and
  resident memory and `walBytes` are flat over the last half of the run
* **And:** the same scenario is run against `xyk-twin-go` in the same invocation, interleaved

## 6. Out of scope

* **Deduplication.** Deciding that two requests are "the same webhook" means parsing the body for a
  vendor-specific id, and this path does not parse. The event id is exposed instead, so a subscriber
  can dedupe on what it already understands.
* **Payload transformation, filtering and routing rules.** A subscriber gets the bytes that arrived.
* **Rate limiting per sender.** Worth having; not in the first version, and named so that its absence
  is a decision rather than an oversight.

## 7. Quirks

* **`200`, not `202`.** `202` is the honest code for "accepted for processing", and it is not used
  here, because the thing xyk promises is that the event is *stored* — which has happened by the
  time the status is written. Several senders also treat any non-`2xx` as failure and nothing else,
  so the distinction buys nothing on the wire and costs a wrong promise in the documentation.
* **The `events` insert and the timers are in one transaction with the endpoint lookup deliberately
  outside it.** The lookup is a read of nearly-static data; holding SQLite's writer lock across it
  would serialise every ingest behind one read.
* **The throughput criterion measures a path with a commit in it.** Numbers from services that
  answer out of memory are not comparable to these, including most published Ktor benchmarks.
