---
id: feature-delivery
title: Delivering to subscribers, with retries and timeouts
type: feature
status: active
owner: unassigned
involved_services:
  - xyk-server
  - chronik-sqlite
client_entries: []
api:
  - endpoint-journal
tags: [delivery, chronik]
---

# Delivering to subscribers, with retries and timeouts

## 1. Overview

Every accepted event owes one delivery per enabled subscriber. This feature is what happens between
the `200` xyk gave the sender and the `2xx` a subscriber eventually gives xyk: a POST with a
timeout, a retry schedule that backs off, a limit after which the delivery is dead-lettered instead
of retried forever, and a record of every attempt.

The machinery is chronik's, not ours ([research §1.2–1.3](../research/research-architecture.md)):
`TimerWorker` claims due timers under a lease, calls a sink, and interprets a thrown exception as a
failed attempt. What is ours is the sink — one HTTP POST, bounded by a timeout — and the decision of
how many workers there are.

**This half is blocked on an upstream release.** chronik publishes no native artifacts today, so
nothing here can be built until [B-02](../backlog/B-02-chronik-native-targets.md) lands.

## 2. Business rules

* **At-least-once.** `markFired` runs after the sink returns, so a crash in between re-delivers. The
  subscriber contract says so and gives the tools to cope: the event id and the attempt number are
  headers on every delivery.
* **A delivery is a POST of the stored bytes** with the original `Content-Type`, plus
  `X-Xyk-Event`, `X-Xyk-Attempt`, `X-Xyk-Endpoint`.
* **`2xx` is success. Everything else is a failed attempt**, including `3xx` — a redirect is a
  misconfiguration, and following it would deliver somebody's payload to an address the operator
  never approved.
* **Each attempt is bounded by `XYK_DELIVERY_TIMEOUT_MS`, enforced inside the sink.** chronik has no
  timeout of its own, and a worker's tick is a sequential loop over its batch — so without this,
  one hung subscriber stalls every delivery behind it.
* **Backoff is chronik's**: `base × 2^(attempt-1)` seconds, capped. Defaults are 1 s base, 300 s cap,
  5 attempts. They are configuration, and the values in use are recorded in the journal per
  endpoint, not only in a config file.
* **After the last attempt the delivery is dead-lettered**, which is a state, not a deletion: it
  stays in the journal and can be redelivered by hand.
* **Removing a subscriber does not cancel in-flight timers.** They fire, fail against a subscriber
  that is gone, and dead-letter. Cancelling them would mean a timer disappearing without a record,
  which is the one thing the journal exists to prevent.

## 3. Flow

1. A worker claims a batch of due timers under a lease (`owner` = its name, `leaseUntil` = now + 30 s
   by default).
2. For each timer, in order: the sink resolves the subscriber, POSTs the stored bytes with the
   timeout, and records an attempt row with status, duration and the response's first bytes.
3. A `2xx` returns normally → chronik marks the timer fired.
4. Anything else throws → chronik records the failure, computes the backoff and reschedules, or
   dead-letters at the limit.
5. The worker sleeps `pollInterval` and repeats.

## 4. Code anchors

| Service | Code |
|---|---|
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/DeliverySink.kt` — the `TimerSink` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/DeliveryWorkers.kt` — worker count, owner names, lifecycle |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/data/Sqlx4kDeliveryRepository.kt` — attempt rows |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/HttpClients.kt` — the curl engine and its CA configuration |
| chronik-sqlite | `chronik-sqlite/src/commonMain/kotlin/io/github/youndie/xyk/chronik/Sqlx4kTimerStore.kt` — `claimDue`, `markFired`, `markFailed`, `markDeadLettered` |
| upstream | `chronik/chronik-core/src/commonMain/kotlin/TimerWorker.kt` — the loop this feature depends on |

## 5. Scenarios (BDD / test cases)

The subscriber in these is a local server the harness can instruct to be slow, to fail, or to
succeed on the third try. **An `Automated:` line means a test runs it; its absence means the
scenario is checked by hand or not yet at all, and that asymmetry is the point of showing it.**

### Scenario: a delivery reaches the subscriber and is recorded

* **Given:** an accepted event and one enabled subscriber answering `200`
* **When:** the worker ticks
* **Then:** the subscriber received the stored bytes unchanged, with `X-Xyk-Event` and
  `X-Xyk-Attempt: 1`
* **And:** the journal shows the delivery as `delivered` with one attempt

**Automated:** `DeliverySinkTest.a 200 delivers the stored bytes unchanged with the promised headers`, and end to end against the released image in [B-26](../backlog/B-26-schedule-timers-on-ingest.md).

### Scenario: a failing subscriber is retried with growing gaps

* **Given:** a subscriber answering `500`, base backoff 1 s, cap 300 s
* **When:** the worker ticks repeatedly
* **Then:** attempts are scheduled 1, 2, 4, 8 seconds apart
* **And:** each attempt has its own row with its status and duration

**Not automated.** The sink's half is covered (`DeliverySinkTest.a 500 throws so the worker retries — and the row is written first`); the *gaps* are chronik's scheduling and are covered by its own conformance corpus, which this repository runs against its driver in `ChronikConformanceTest` rather than re-asserting here.

### Scenario: the fifth failure dead-letters instead of retrying

* **Given:** the same subscriber and `maxAttempts = 5`
* **When:** the fifth attempt fails
* **Then:** the delivery state is `dead` and no further timer exists
* **And:** the event is still in the journal, with all five attempts

**Automated:** `DeliverySinkTest.the last attempt dead-letters rather than staying pending` covers the state written at `maxAttempts`. That no further timer exists is chronik's, and is in its corpus.

### Scenario: a hung subscriber does not block the others *(this is why the timeout exists)*

* **Given:** a batch of 50 due timers, the first of which points at a subscriber that never responds
* **And:** `XYK_DELIVERY_TIMEOUT_MS = 2000`
* **When:** the worker ticks
* **Then:** the tick completes in under `50 × 2 s` and every other delivery is attempted
* **And:** the hung one is recorded as a timeout, not as a `5xx`

**Automated:** `DeliverySinkTest.a subscriber that never answers is bounded by the timeout and named as such`. The batch-level claim — that the other 49 are still attempted — is **not** automated: it needs 50 due timers and a real tick, which is an integration harness this repository does not have.

### Scenario: a redirect is a failure, not a hop

* **Given:** a subscriber answering `302` with a `Location`
* **When:** the delivery is attempted
* **Then:** the attempt is recorded as failed with status `302`
* **And:** nothing was sent to the address in `Location`

**Automated:** `DeliverySinkTest.a 302 is a failure rather than a hop`, which also asserts the sink posted exactly once. That the *engine* does not follow the redirect is set on the client (`followRedirects = false`) and is not separately tested.

### Scenario: a crash between the POST and the mark re-delivers

* **Given:** a subscriber that records what it receives and answers `200`
* **When:** the process is killed after the response is received and before the timer is marked
* **And:** the process is started again
* **Then:** the subscriber receives the same event a second time, with `X-Xyk-Attempt: 1`
* **And:** the journal shows two attempts against one delivery

**Not automated.** Killing a process between two statements needs a harness that can stop it there; the guarantee it rests on — that `markFired` runs after `deliver` returns — is read in chronik's source ([research §1.3](../research/research-architecture.md)).

### Scenario: a killed worker's claimed timers are picked up again

* **Given:** two workers and a batch claimed by the first
* **When:** the first is killed mid-batch
* **Then:** after the lease expires, the second claims the unfinished timers and delivers them
* **And:** nothing is delivered twice **before** the lease expires

**Not automated here.** The lease is chronik's and its corpus covers it; what is not covered anywhere is two *xyk processes* racing, which needs two containers and a shared volume.

### Scenario: HTTPS to an external subscriber works from inside the image

* **Given:** the released image and a subscriber at a public https URL
* **When:** a delivery is attempted from inside the container
* **Then:** it succeeds — the check is run in the image, because certificates exist on every
  developer machine and in no minimal base image

**Automated by hand, and recorded:** `GET https://example.com -> 200` from inside the released image ([B-10](../backlog/B-10-delivery-sink.md)), and a full delivery to a local subscriber from the image in [B-26](../backlog/B-26-schedule-timers-on-ingest.md). No https *delivery* to a public subscriber has been run — that needs an endpoint somebody owns.

## 6. Out of scope

* **Ordering.** Retries reorder by construction; xyk promises delivery, not sequence.
* **Circuit breaking per subscriber.** A subscriber that is down for an hour currently consumes
  attempts on its own backoff schedule and nothing else; suppressing its whole queue is a product
  decision that needs a way to un-suppress, and neither exists yet.
* **Fan-out to anything but HTTP.** No queues, no webhooks-to-email, no cloud buses.
* **Signing the outbound delivery** so subscribers can verify xyk the way xyk verifies GitHub. It is
  the obvious next feature and it is not in this one.

## 7. Quirks

* **The delivery worker swallows its own exceptions and keeps polling** — a broken store looks like
  an idle service. Readiness counts completed ticks for exactly this reason.
* **Delivery latency has a floor of about one second**, the poll interval. Nothing is wrong when a
  delivery starts a second after the `200`.
* **All curl I/O runs on one dispatcher thread**, so above some concurrency adding workers buys
  nothing. The number is measured at [B-14](../backlog/B-14-delivery-worker-count.md), not assumed.
* **`markFailed` and `markDeadLettered` can themselves fail**, and chronik reports that through
  `onWorkerFailure` rather than retrying it. A store that cannot write is a readiness failure, not a
  delivery failure, and the two must not be merged in the journal.
