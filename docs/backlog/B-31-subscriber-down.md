---
id: B-31
title: "What a subscriber being down costs"
status: done
priority: P2
size: S
stage: stage-3-verdict
epic: feature-delivery
blocked_by: []
---

# B-31 — What a subscriber being down costs

Split out of [B-30](B-30-delivery-memory-growth.md), where it turned up by accident: the first
wiring of the soak pointed the subject at an address it could not reach, and the subject was
OOM-killed inside thirty seconds — faster than the arm that was working. A subscriber being down is
the most ordinary condition a webhook gateway meets, and nothing had measured it.

- **Decision: three kinds of down, not one.** Refused, timed out and unresolvable are different
  costs — one returns immediately, one occupies a worker for the whole delivery timeout — and an arm
  that measured "unreachable" would have averaged them into a number describing nothing.
- **Rejected: treating it as a new defect.** The per-attempt cost is B-30's growth and a failure is
  still a request; what this item adds is the multiplier, not a second mechanism.

- AC: the cost of a failed attempt against a delivered one, per kind of failure, on the shipping
  image. **Met** — [subscriber-down.md](../research/measurements-2026-09-16/subscriber-down.md):
  ~2 kB delivered, ~14 kB refused, ~60 kB timed out, two runs an arm.
- AC: whether the service survives it, said honestly rather than as one word. **Met, and the answer
  is two words** — at 64 MiB it is bimodal: one run rode the limit for five minutes delivering
  nothing, one was killed inside thirty seconds, same image and same limit. At 256 MiB both runs
  died.
- Anchors: `bench/soak.sh`, `docs/research/measurements-2026-09-16/subscriber-down.md`

## The two things worth carrying out of it

**A failed attempt costs more than a delivered one**, which inverts the intuition that a gateway
with nowhere to deliver idles. It still makes the request, and the request is where the memory goes.

**A smaller limit does not make it safer, it makes it quieter.** At 64 MiB the surviving run made
3 611 attempts in five minutes where the 256 MiB run made 18 561: the limit starves the workers, so
fewer attempts are made, so memory stays under it. Five minutes in that run had 3 442 events
accepted with a `200` and not delivered. Both are recorded in
[`services/xyk-server.md` §8](../services/xyk-server.md), because an operator choosing a limit is
choosing between those two failures and should be told so.

## Recovery, measured 2026-09-16 — and it is not one

Two runs, a sixty-second outage at a limit with room, the subscriber brought back without restarting
the service:

* **~1 600 events are dead before it returns** — about thirty seconds of accepted traffic. Five
  attempts on chronik's doubling backoff from a 1 s base are spent in thirty-one seconds, and a
  refused connection spends them as fast as they arrive.
* **Dead is final.** Neither run retried one. `POST /api/events/{eventId}/redeliver` is by hand and one
  event at a time, and there is no sweep.
* **The rest does not drain.** A healthy service sits at ~220 pending; these sit at 2 000–2 240 for
  six minutes, one of them drifting up. Delivery once recovered runs at 54–59 /s against the same
  ingest — there is no spare capacity to work a backlog off with.
* **At 256 MiB there is no recovery at all**: a two-minute outage reaches the limit at 110 s and the
  process is OOM-killed just after the subscriber returns, having delivered 94 of 4 878.

**So "it comes back" is not a state this service reaches on its own.** An operator gets the traffic
back only by redelivering by hand, and only for what the journal still holds.

**How short is short enough, measured:** nothing dies up to **fifteen seconds**; at twenty, 90 and
143 events do. Five attempts with 1, 2, 4 and 8 seconds between them span fifteen seconds from the
first, so that is the threshold and it is arithmetic rather than luck. But the queue is left
elevated by **every** outage, including a five-second one — 458 pending two minutes later against a
healthy 220 — because the capacity that would drain it is not there.

**Still not measured:** whether that threshold moves with the ingest rate. It was found at 60 rps
and nowhere else.
