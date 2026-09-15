---
id: B-25
title: "The journal page collapses under fifty concurrent readers"
status: done
priority: P1
size: M
stage: stage-1-product
epic: feature-journal
---

# B-25 — The journal page collapses under fifty concurrent readers

Found while measuring something else (B-20). Fifty concurrent `GET /journal` against the four-core
benchmark host: **1 request per second, p50 30 056 ms, 65 % of them failed.** The same server on the
same host answers `/health/ready` at ~410 rps and the ingest path at ~520.

For comparison, the numbers either side of it in that run:

| route | rps | p50 |
|---|---:|---:|
| `/health/live` | 410 | 101 ms |
| `/version` | 430 | 98 ms |
| `POST /hooks/{id}` | 523 | 379 ms |
| **`GET /journal`** | **1** | **30 056 ms** |

- **This is not the core-count pathology of [research §1.18](../research/research-architecture.md).**
  Every other route on the same binary, host and run was three orders of magnitude faster. Something
  in the page's own path serialises.
- **The first suspect is the query, not the rendering.** `SELECT_EVENTS` carries three correlated
  subqueries per row — `count(*)` over `deliveries` three times — and the list runs it for fifty
  rows. With a pool of two connections and fifty readers, that is fifty queries queueing behind two
  connections, each doing 150 subquery executions.
- The second suspect is the pool itself: two connections is a storage decision
  ([B-04](B-04-database-pool-and-journal.md)) and the journal is the one reader that competes with
  ingest for them.
- Not covered: making the page paginate by default (it already caps at 50) or caching it. Measure
  before choosing.

**Reproduced on a second host with the generator pinned away (2026-09-15): 132 rps** against 478 for
`/health/live` on the same container — so the collapse is the page's own, not the core-count effect
of [research §1.18](../research/research-architecture.md), and not contention with the generator.

**It does not take the server with it.** After the burst, every route answers at 0, 30 and 90 seconds
— `live=200 ready=200 startup=200 version=200`, the hook `401` without a signature, 104 threads, 41 MB
resident, no leftover sockets. An earlier reading said the opposite; it was the measurement that had
failed, not the service.

- AC: the same fifty-reader load produces no failures and a latency a person would call a page load.
- AC: the fix is chosen from a measurement that names which of the two suspects it was, not from
  whichever was easier to change.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/data/Sqlx4kJournalRepository.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/db/Database.kt`

## The suspect was named by measurement, 2026-09-16 — and it was neither of the two

**It reproduces, and the variable that decides it was never written down: how many rows are in the
table.** At **500 events** the page answers 471 rps with no failures. At **39 000** events, fifty
concurrent readers over twenty seconds complete **not one request** — k6 prints
`No script iterations fully finished` and `data_received: 0 B`. The original reading was taken after
a throughput run had filled the database; this item recorded the symptom and not the volume, which is
why the first attempt to reproduce it nearly closed it as unreal.

**`/api/events` collapses identically to `/journal`, which exonerates the rendering.** Same query,
no HTML, same nothing-finished. That is the discrimination the criterion asked for, and it cost one
extra arm.

**The cost is the query, and specifically the index SQLite chose for it.** Measured directly against
a seeded database, one page over 28 781 events takes **1 568 ms**, and the plan says why:

```
SCAN e USING INDEX events_received_at
CORRELATED SCALAR SUBQUERY 1
  SEARCH d USING COVERING INDEX deliveries_event (event_id=?)
CORRELATED SCALAR SUBQUERY 2
  SEARCH d USING INDEX deliveries_state (state=?)        <-- every pending row in the table
CORRELATED SCALAR SUBQUERY 3
  SEARCH d USING INDEX deliveries_state (state=?)        <-- again
```

Two of the three subqueries filter on `event_id` **and** `state`, and with only `(event_id)` and
`(state, created_at)` to choose from, SQLite took the one on `state`. Every delivery in a healthy
service is `pending` for a moment and `delivered` after, so that index selects a large fraction of the
table and filters by `event_id` afterwards — per event row, fifty-one times a page.

**`deliveries(event_id, state)` takes the same page on the same data from 1 568 ms to 1.7 ms**, and
both subqueries move to `COVERING INDEX deliveries_event_state (event_id=? AND state=?)`. That is
migration v7; `deliveries_event` is dropped in the same step, because the composite covers its
queries as a prefix and keeping both would make every ingest write two index entries where one does.

### After, at the same 39 000 events and fifty readers

| arm | pool | rps | med | p95 | failed |
|---|---:|---:|---:|---:|---:|
| `/health/ready` | 2 | 3 382 | 7.7 ms | 39.7 ms | 3.01 % |
| `/api/events?limit=50` | 2 | **1 085** | 44.9 ms | 55.2 ms | 0 % |
| **`/journal`** | **2** | **1 053** | **46.8 ms** | **53.4 ms** | **0 %** |
| `/journal` | 8 | 294 | 168.5 ms | 184.9 ms | 0 % |

From nothing-finishes to a thousand pages a second at fifty concurrent readers, with a median a
person would call instant.

**The second suspect answered too, and the answer is "no".** A pool of eight is **three and a half
times worse** than a pool of two on this page — 294 rps against 1 053 — so raising it would have made
things worse while appearing to address the report. It stays at two, and now for a measured reason as
well as the journal-truncation one ([B-04](B-04-database-pool-and-journal.md)).

**One number here is not clean and is left as it is:** `/health/ready` reports 3.01 % failures in the
arm that runs immediately after seeding, while the same route at pool 8 reports 0.82 %. That arm
follows forty seconds of ingest at ~1 000 rps and the journal sweep is still catching up; it is noted
rather than explained, because nothing in this item turns on it.

- AC: **met** — no failures, and a median page load of 47 ms.
- AC: **met** — the fix names its suspect: the query, and within it the index choice, with the plan
  before and after and the pool tested rather than assumed.
