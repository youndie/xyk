---
id: B-04
title: "Migrations, a pool of two, WAL truncation and walBytes in the health response"
status: done
priority: P0
size: M
stage: stage-0-foundations
---

# B-04 — Migrations, a pool of two, WAL truncation and walBytes

xyk is read (the journal) while it is written (ingest), which is the exact shape in which SQLite's
automatic checkpoint — always PASSIVE — never truncates the journal. Elsewhere that ended as a
931 MB `-wal` beside a 187 MB database, reads getting more expensive, in-flight requests growing,
threads and arenas following, and the process killed on memory with a small heap
([research §1.8](../research/research-architecture.md)).

- **Decision: the pool is two connections.** Each one is a driver thread with its own arena *and* one
  more reader keeping the checkpoint window shut. "More is better" is wrong here twice.
- **Decision: our own `PRAGMA wal_checkpoint(TRUNCATE)`, on a timer and on file size.** TRUNCATE, not
  RESTART: RESTART resets the journal and leaves the file at its peak. A timer alone was not enough
  elsewhere — on a wound-up loop the journal grows between passes.
- **Decision: `walBytes` is its own field in the health response, with no default.** `page_count *
  page_size` does not count the journal, so a size guard built on it looks past the file that is
  filling the disk; and a serializer with `encodeDefaults = false` drops a zero, so a reader cannot
  tell "empty" from "absent".
- **Pragmas are set per connection.** One sent through the pool reaches one connection: only
  `journal_mode` survives that, `synchronous` does not.
- Not covered: the retention purge, which is [B-19](B-19-secret-handling.md).

- AC: a probe of N concurrent transactions each running `PRAGMA synchronous;` shows the same value on
  every connection. **Done**, as a test on both targets — `ConnectionPragmaTest`, which carries its
  own positive control: a pragma sent the naive way through the pool must produce *both* values, or
  the probe cannot see the difference it exists to see and the first test means nothing.
- AC: under a run that writes and reads simultaneously, `walBytes` rises and returns rather than
  growing monotonically; the check is the file, not the database size. **Half done**: the mechanism
  is tested (`WalCheckpointTest` — the log grows, TRUNCATE takes it to zero, and the 200 rows are
  still there), and the long run that shows the loop it prevents is [B-24](B-24-soak-wal.md) by
  design.
- AC: `/health/ready` fails when `walBytes` crosses the configured ceiling. **Done** —
  `JournalCheckTest` asserts both directions against the same real journal, because a ceiling set at
  the sweep's own trigger would take the service out of the load balancer every time the sweep
  worked.

## Closed 2026-09-15

What shipped: `WalCheckpoint` (TRUNCATE + `walBytes` off the file), `WalSweep` (two triggers — the
clock and the size — with `0` turning it off as B-24's control arm), `pinSynchronousOnEveryConnection`
at start-up, a `journal` health check separate from the `sqlite` one, and
`XYK_WAL_CHECKPOINT_SECONDS` / `XYK_WAL_MAX_BYTES`. The readiness ceiling is deliberately **four
times** the sweep's trigger.

Nine tests on each of the two targets, green.

**Two things the run found that were not in the plan.**

1. **The pragma probe's control fires.** Sending `PRAGMA synchronous = FULL` the naive way through a
   pool of three produces `{1, 2}` — one connection changed, the others not. So the "a pragma
   reaches one connection" mechanism is confirmed on sqlx4k 1.13.0 by our own probe rather than
   inherited.
2. **The JVM driver answers `SQLITE_BUSY` while *creating* a pooled connection.** Holding several
   connections to one file at once makes sqlx4k's own `connectionFactory` fail — it runs
   `PRAGMA journal_mode` on each new connection, and that needs a write lock the siblings hold. It
   failed one `jvmTest` run in two and never once on native. `busy_timeout` cannot fix it (the
   failure is inside the driver, before any statement of ours), so the acquire waits it out; four
   consecutive JVM runs green afterwards. This is [[sqlx4k is two drivers]] again, and it is the
   half that does not ship.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/db/`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/health/Probes.kt`
