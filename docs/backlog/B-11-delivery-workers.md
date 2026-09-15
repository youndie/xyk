---
id: B-11
title: "Delivery workers: owners, lifecycle, and a readiness check that counts ticks"
status: done
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

## Built and verified in a container, 2026-09-16

**Two workers with distinct owners, their poll loop owned here rather than chronik's.**
`TimerWorker.start` swallows everything that is not a cancellation and keeps polling — correct for a
poll loop, and it makes a store that cannot be read look exactly like a service with nothing to do.
`tick()` is public precisely so the loop can be owned, so it is: each pass increments a counter,
each failure is kept as a string, and a cancellation is rethrown so shutdown actually ends the job.

**The first draft counted the wrong thing.** It used chronik's `onLostRace` as a tick signal. That
callback fires only when a worker claims nothing **and** due timers exist — a lost race, not a pass —
so the counter would have sat at zero on a healthy idle service and made readiness fail for the
opposite of the reason it exists. Caught by reading chronik's source instead of its callback names.

**Readiness watches movement, not a rate.** A service with no due timers still ticks; counting
deliveries instead would make an idle Sunday read as an outage. Five unit cases cover the probe
itself, including that it recovers rather than latching.

### The acceptance criterion, run against a real image

| step | result |
|---|---|
| service up, `XYK_DELIVERY_STALL_SECONDS=5` | `ready=200` across 25 s — five windows, so the loop is demonstrably ticking |
| `DROP TABLE chronik_timers` from a second connection | — |
| ~3 s later | `ready=200` (inside the window) |
| ~7 s later | **`ready=503`**, `live=200` |
| the probe's own words | `delivery workers have not completed a pass in 16.0s (2 worker(s), 8 tick(s) total); last failure — poll: SQLError: [Database] :: [1] (code: 1) no such table: chronik_timers` |

Liveness staying `200` is not incidental: a failing liveness probe would restart the pod in the
middle of the incident it is reporting.

**The stop order holds with the workers in it.** `SIGNAL ANNOUNCE DRAIN RELEASE_CONSUMERS
RELEASE_POOLS EXIT`, all `COMPLETED`, against the image — the workers release between the drain and
the pool, which is the position kore is in this design for.

**`XYK_DELIVERY_STALL_SECONDS` exists because the doc claimed it should.** The window's right value
is `batchSize × deliveryTimeout` plus a margin, which is a deployment's arithmetic rather than a
constant — and a configurable window is also what made the probe checkable in a container in seconds
instead of two minutes.

- AC: **met** for the unreadable store — `/health/ready` fails within one window plus one check
  interval, and says why.
- AC: **not run** for killing a worker mid-batch and waiting out the lease. It needs timers to exist,
  and nothing schedules one yet: see [B-26](B-26-schedule-timers-on-ingest.md), which this item
  uncovered. The lease itself is covered by chronik's conformance corpus on our driver
  ([B-03](B-03-chronik-sqlite-store.md)); what is not covered here is two xyk processes racing.
- AC: killing a worker mid-batch and waiting out the lease shows another worker completing the
  delivery, and no delivery happening twice before the lease expires.
- AC: with the store made unreadable, `/health/ready` fails within a bounded number of poll
  intervals.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/DeliveryWorkers.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/health/Probes.kt`
