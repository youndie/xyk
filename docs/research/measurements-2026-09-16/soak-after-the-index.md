# B-24 — the soak's subject disappeared, and the index took it

**Date:** 2026-09-16. Same harness, same arm, same host as
[soak-wal.md](../measurements-2026-09-15/soak-wal.md): `--arm unswept`, which sets
`XYK_WAL_CHECKPOINT_SECONDS=0` and leaves SQLite's own PASSIVE behaviour with nothing helping it.
200 rps of ingest, four concurrent `GET /journal` readers, 64 MiB, twenty minutes.

**The only difference is the build.** This one carries migration v7 — the composite index
`deliveries(event_id, state)` that [B-25](../../backlog/B-25-journal-page-collapses-under-concurrency.md)
added, which took one journal page from 1 568 ms to 1.7 ms.

## The control arm, which is supposed to misbehave, does not

| | 2026-09-15, before the index | **2026-09-16, after it** |
|---|---:|---:|
| `-wal` peak | 72 256 kB | **8 996 kB** |
| `/health/ready` = 503 | 23 of 120 samples | **0 of 120** |
| delivered, of 200 rps offered | 87 | **200.0** |
| p50 / p99 | 4.76 ms / 5.47 s | **2.82 ms / 12.04 ms** |
| failed | 0 of 104 972 | 0 of **240 001** |
| threads peak | 75 | 76 |
| `oom_kill` | 0 | 0 |

The database grew 4 096 → 155 640 kB across the run — it absorbed nearly three times the events of
the earlier run, at the full offered rate, while the journal never passed 9 MB.

## What this says, and it is not about checkpoints

**The mechanism this soak was built to find was a query plan.** The chain, now visible end to end:

1. `SELECT_EVENTS` had two correlated subqueries filtering on `event_id` and `state`, and SQLite
   chose the index on `state` — which in a healthy service selects most of the table.
2. A journal page therefore held a read transaction open for **1.5 seconds**.
3. Four readers at 1 rps meant a reader was almost always live.
4. SQLite's automatic checkpoint is only ever PASSIVE and **cannot truncate the journal while a
   reader is alive**, so the `-wal` file only grew.
5. Reads got slower, in-flight requests rose, readiness failed, throughput collapsed to 43 % of the
   offered rate.

**Every symptom was in storage and the cause was in a query plan.** The soak, the WAL sweep and the
pool size were all reasoning about step 4 — which is true, and was never the thing to fix.

## What follows for the WAL sweep

**The sweep is now a mitigation with no demonstrated problem on this service.** That is not the same
as a mitigation that does not work, and it is not a reason to delete it:

* the mechanism is real and was measured elsewhere in this portfolio at 931 MB of journal beside a
  183 MB database;
* twenty minutes at 200 rps with four readers is one point, and the condition that produces it is
  *any* long-running read — a future query with a bad plan puts it straight back;
* what changed is the cost of this service's one long-running reader, not SQLite's behaviour.

So it stays, and what this measurement retires is the claim that it is load-bearing here. Anything
quoting the earlier soak's numbers is quoting a service with a missing index.

## What would demonstrate it again

A reader that holds a transaction open for a second or more, on purpose — not a slow page, since
there is no longer one. That is a different harness from this one: it would stop measuring the
product and start measuring SQLite, which is a legitimate thing to do and a different item.
