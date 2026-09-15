---
id: B-26
title: "Nothing schedules a timer yet, so nothing is ever delivered"
status: done
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

## The first end-to-end delivery in this repository, 2026-09-16

One webhook, one subscriber, the released image with the curl engine, a Python sink on the host that
prints what it receives:

```
POST /hooks/hook-1  ->  {"event":"7049721a372a378d8cf8b5e1758bff77"}

the subscriber saw:
  body     {"zen":"Non-blocking is better than blocking."}   — byte for byte
  ct       application/json                                   — the sender's own, unchanged
  event    7049721a372a378d8cf8b5e1758bff77                   — the id ingest answered with
  attempt  1
  delivery 28891b85fa45d1b93f805da8f5710127
  ua       xyk

the journal said:  "deliveries":1, "pending":0, "dead":0
```

Everything the happy path of [feature-delivery](../features/feature-delivery.md) promises, in one
run against a real image.

## What changed, and the one decision worth arguing with

**One timer per delivery row, written in the same transaction, with the delivery's own id.** The
timer and the delivery are one thing in two tables, so they share an id: giving the timer an id of
its own would add a join to "is this delivery scheduled?", which is a question the journal asks on
every page.

**A build with no scheduler writes no delivery rows at all** — not rows without timers. A row nobody
will claim shows `pending` in the journal for ever and describes a queue that does not move; an
honest empty column is better. The accepted-event response reports the number actually written, so
it does not describe another build's behaviour either.

**Redelivery from the journal goes through the same seam**, and returns `0` rather than writing
unclaimable rows when there is no scheduler. Without that, the page would report a redelivery that
never happens.

**The invariant now has a test that breaks it on purpose.** `TimerPairingTest` gives the repository
a scheduler that throws, and asserts that **the event itself is gone too** — not merely the delivery
rows. Half a rollback would be worse than the failure: an event with no deliveries reads in the
journal as "nobody was subscribed", which is a sentence about configuration rather than about a
storage failure, and the route answers `500` so the sender retries.

**Four existing tests failed when this landed, and they were right to.** They built the repository
without a scheduler and asserted delivery rows existed; under the new rule that combination writes
nothing. They now pass a recording fake, which is strictly more than they checked before — the ids
scheduled are asserted to be *the same ids* that reached the table, because a scheduler called the
right number of times with the wrong ids satisfies a count and delivers nothing.

- AC: **met** — the delivery happened and the journal shows it.
- AC: **met** — a build without chronik prints `delivery is OFF … events are journalled only` and
  writes no unclaimable rows.
- AC: **met** — `TimerPairingTest`, by failing the schedule and finding nothing left behind.
- AC: a webhook posted to a live service with one subscriber is delivered, and the journal shows the
  attempt — the first end-to-end delivery in this repository.
- AC: with the scheduler absent, start-up says the delivery half is off, rather than reporting
  workers that can never receive work.
- AC: killing the process between the delivery row and the timer leaves neither — the transaction is
  the test, and a fake that commits one and drops the other proves it.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/ingest/data/Sqlx4kEventRepository.kt`,
  `server/src/variants/with-chronik/kotlin/io/github/youndie/xyk/delivery/`
