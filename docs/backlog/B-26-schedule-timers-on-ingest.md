---
id: B-26
title: "Nothing schedules a timer yet, so nothing is ever delivered"
status: open
priority: P0
size: S
stage: stage-1-product
epic: feature-delivery
blocked_by: [B-11]
---

# B-26 — Nothing schedules a timer yet, so nothing is ever delivered

**Found by building B-11 rather than by reading it.** The outbound half is now complete on both
ends — the sink posts and records ([B-10](B-10-delivery-sink.md)), the workers claim, tick and are
watched by readiness ([B-11](B-11-delivery-workers.md)) — and the two ends are not joined. Ingest
writes a row into `deliveries` and **no timer anywhere**, so `claimDue` returns an empty batch for
ever. A running service reports `delivery is on — 2 worker(s)`, answers every probe `200`, and
delivers nothing.

That shape is the one this repository keeps paying for: every part works, nothing is wired, and the
whole is green. It is written down as its own item rather than folded into B-11 so that the gap has
a number somebody can close rather than a paragraph somebody can miss.

- **Decision: the timer is written inside the transaction that writes the delivery row.** That is
  the entire reason chronik's store is a `TransactionalTimerStore` and the reason this design chose
  chronik at all ([research §1.2](../research/research-architecture.md)): a delivery row committed
  without its timer is a webhook accepted with `200` that nobody will ever send, and it is invisible
  — the journal shows it pending for ever.
- **Decision: the payload is the delivery id and nothing else.** `DeliveryWorkers` already reads it
  that way. Anything richer makes the timers table a second copy of `deliveries` that drifts from
  the first.
- **Decision: it goes behind the same `expect` seam as the workers.** chronik has no `macosArm64`
  variant, so `Sqlx4kEventRepository` may not import it; the scheduler is a port with a no-op actual
  where chronik is absent, and a build without it must **say so** rather than accept events it will
  not deliver.
- Not covered: redelivery from the journal page, which writes delivery rows through a different path
  ([B-13](B-13-journal-api.md)) and needs the same seam.

- AC: a webhook posted to a live service with one subscriber is delivered, and the journal shows the
  attempt — the first end-to-end delivery in this repository.
- AC: with the scheduler absent, start-up says the delivery half is off, rather than reporting
  workers that can never receive work.
- AC: killing the process between the delivery row and the timer leaves neither — the transaction is
  the test, and a fake that commits one and drops the other proves it.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/ingest/data/Sqlx4kEventRepository.kt`,
  `server/src/variants/with-chronik/kotlin/io/github/youndie/xyk/delivery/`
