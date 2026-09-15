---
id: B-25
title: "The journal page collapses under fifty concurrent readers"
status: open
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
