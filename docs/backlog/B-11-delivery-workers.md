---
id: B-11
title: "Delivery workers: owners, lifecycle, and a readiness check that counts ticks"
status: open
priority: P1
size: S
stage: stage-1-product
epic: feature-delivery
blocked_by: [B-10]
---

# B-11 — Delivery workers: owners, lifecycle, and a readiness check that counts ticks

Start N `TimerWorker`s with distinct `owner` values, stop them in the right place in kore's sequence,
and notice when they stop working.

- **Decision: readiness counts completed ticks.** `TimerWorker.start` catches everything that is not
  a cancellation, reports it and keeps polling — so a store that cannot be read looks like an idle
  service rather than a crashing one. Nothing else in the system would notice.
- **Decision: the workers stop after the engine drains and before the pool closes.** That is the
  whole reason kore is in this design: on Kotlin/Native the idiomatic ordering runs those two in the
  wrong order and cuts work that was already accepted.
- **Decision: the selector gets its own thread.** `SelectorManager` occupies a `Dispatchers.Default`
  worker forever, and on a two-core pod that makes `delay` stop firing process-wide — which would
  stop the poll loop and look like nothing at all.
- Not covered: the *number* of workers, which is a measurement ([B-14](B-14-delivery-worker-count.md)).

- AC: killing a worker mid-batch and waiting out the lease shows another worker completing the
  delivery, and no delivery happening twice before the lease expires.
- AC: with the store made unreadable, `/health/ready` fails within a bounded number of poll
  intervals.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/DeliveryWorkers.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/health/Probes.kt`
