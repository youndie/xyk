---
id: B-24
title: "The soak: the journal page reading while ingest writes, long enough to find the cliff"
status: wip
priority: P1
size: M
stage: stage-3-verdict
blocked_by: [B-04, B-12]
---

# B-24 — The soak: reading while writing, long enough to find the cliff

Every other measurement here is minutes long. This one is not, because the failure it looks for
appeared at minute 33 in another service and at minute 65 without the mitigation — and a short run
that survives is exactly what makes people believe the mitigation is unnecessary.

The mechanism: SQLite's automatic checkpoint is always PASSIVE and does not truncate while a reader
is alive; the journal grows linearly while **the database file stops growing**; reads get more
expensive; at a fixed offered rate the number of in-flight requests rises; threads follow, arenas
follow, and the process is killed on memory with a small heap.

- **The scenario: ingest at a steady rate with the journal page polled throughout** — the reader is
  the product, not a synthetic load.
- **Decision: what is watched is the `-wal` file and the thread count**, not the database size.
  `page_count * page_size` does not count the journal: elsewhere 931 MB of journal beside a 183 MB
  database was reported as "well under the limit" by a guard looking past the file that was filling
  the disk.
- **Decision: the run is long enough to cross the point where the cliff was found elsewhere**, and
  the duration is chosen before the run rather than "until it looks stable".
- **Decision: a control arm with the checkpoint disabled.** If nothing dies in either arm, the soak
  did not reproduce the mechanism and proves nothing about the mitigation — a negative result needs
  a positive control.
- Not covered: multi-instance behaviour. One SQLite file has one writer, and a second instance is a
  different product.

- AC: two arms — with and without `wal_checkpoint(TRUNCATE)` — plotted over time: `walBytes`,
  database size, RSS, threads, in-flight requests. **The harness exists** (`bench/soak.sh`) and
  samples every ten seconds: the `-wal` file, the database file, the cgroup's `memory.current`, the
  thread count, whether the container is alive and what readiness answers.

## How it is set up (2026-09-15)

The data directory is **bind-mounted from the host**, which is the only way to watch the journal file
from outside: the image has no shell, and the number that matters is the size of a file the service
never reports. The readers are real `GET /journal` requests rather than a synthetic query, because
the overlapping reader *is* the product — without one SQLite checkpoints on its own and there is
nothing to measure.

The control arm is `--arm unswept`: `XYK_WAL_CHECKPOINT_SECONDS=0`, which turns the sweep off and
leaves SQLite's own PASSIVE behaviour. It runs **first**. If the journal does not run away there, the
soak has not reproduced the mechanism and the treated arm's good behaviour would say nothing about
the mitigation.
## The control arm, measured (2026-09-15) — and the mechanism is not the one predicted

`--arm unswept` (`XYK_WAL_CHECKPOINT_SECONDS=0`), 20 minutes, 64 MiB, 200 rps offered through
ingest, 4 concurrent `GET /journal` readers, subject on cores `0-3`, generator on `12-19`.

| | |
|---|---|
| `-wal` peak | **72 256 kB (70.6 MB)**, reached at **81 s** and flat for the remaining 18 minutes |
| database | grew throughout, 3 140 → 55 712 kB |
| `memory.current` | pinned at **63 760 – 65 516 kB** against a 65 536 kB limit for most of the run |
| `oom_kill` | **0** — it was never killed |
| threads | 34–35 throughout, 75 in the last two samples |
| `/health/ready` | **503 in 23 of 120 samples (19 %)**, spread across the run |
| delivered | **87 rps** of 200 offered; 135 029 dropped iterations against 104 972 completed |
| latency | p50 4.76 ms, p95 **2.3 s**, p99 **5.47 s**, max 8.37 s |
| failures | **0 out of 104 972** — nothing was rejected, everything was slow |

**Two things the item predicted did not happen, and they are the finding.**

1. **"The database file stops growing."** It did not. PASSIVE checkpoints copied pages across the
   whole run; the database more than doubled. What a PASSIVE checkpoint cannot do while a reader is
   alive is *reset the file*, so the journal keeps its high-water mark — which is a different claim
   from a journal that grows without bound.
2. **"Linear growth to a cliff."** There was no ramp at all. The journal sat at **6 MB for the
   first 61 seconds**, went `6 272 → 29 420 → 72 256 kB` across the next twenty, and then did not
   move again for eighteen minutes. A step and a ceiling, not a slope. The 931 MB seen elsewhere in
   this portfolio does not reproduce on this service at this rate.

   **What puts the ceiling there is not established**, and it is the open question of this item. It
   is not a configured `journal_size_limit` — nothing sets one. Two candidates worth one probe each:
   the writer slowed enough (87 rps delivered of 200 offered) that PASSIVE checkpoints began keeping
   up, so the file stopped needing to grow; or the readers' gaps became long enough to let a
   checkpoint complete. Both are guesses and are written here as guesses.

**What the control did reproduce is the slow state, precisely.** Memory pinned on the limit,
readiness failing in nearly a fifth of the samples taken, p99 at 5.47 s, and a service accepting 87
of every 200 requests offered while rejecting none of them. That is the "no slow state" half of the declared criterion
failing, in the arm that was supposed to fail.

**So the control misbehaves, and the run is not void — but the claim it can support is narrower than
the AC assumed.** It cannot support "the sweep prevents the kill", because nothing was killed. It can
support a comparison on journal size, readiness and latency. The treated arm has to be run and read
against those three, not against a death.
## Both arms, round 1 (2026-09-15) — [soak-wal.md](../research/measurements-2026-09-15/soak-wal.md)

| | `unswept` (control) | `swept` |
|---|---:|---:|
| `-wal` peak | 72 256 kB | **36 812 kB** |
| `/health/ready` = 503 | 23 of 120 samples | **40 of 120** |
| delivered of 200 rps offered | 87.1 | **76.6** |
| p99 / max | 5.47 s / 8.37 s | **10.55 s / 24.06 s** |
| `oom_kill` | 0 | 0 |

**The sweep halves the journal and makes everything else worse.** The sawtooth in the `swept` arm
(27 100 → 31 452 → 15 680 → 7 772 → …) is the mitigation working, visibly. The cost lands on the
request path: a `TRUNCATE` checkpoint waits for readers and then does its work while the writer is
still arriving, and this stand is already saturated at 200 rps offered against 77–87 delivered.

**This item called the sweep "the mitigation" before the run. On one round it looked like a trade —
journal size bought with latency and readiness. Round 2 withdrew that half** (below); the cost is not
established and must not be quoted.

## Round 2 (2026-09-15) — the control repeats, the cost does not

`unswept` completed; `swept` was stopped by hand at 54 of 120 samples, so it has no latency figures.

| | `unswept` r1 | `unswept` r2 | `swept` r1 | `swept` r2 (part) |
|---|---:|---:|---:|---:|
| `-wal` peak, kB | 72 256 | 52 980 | 36 812 | 38 688 |
| readiness 503 | 23/120 | 27/120 | 40/120 | **1/54** |
| delivered, rps | 87.1 | 85.1 | 76.6 | — |
| p99 | 5.47 s | 5.60 s | 10.55 s | — |

**The stand is steady** — the two control runs agree to within 2 rps and 0.13 s of p99.

**The latency and readiness cost is withdrawn.** It rested on a single `swept` run; the partial
second one had one readiness failure in nine minutes where the control had 23 and 27 in twenty. Not
reversed — removed.

**The journal difference holds in direction only.** All four pairings put `swept` below `unswept`,
but the control's own run-to-run spread is 1.36× and the closest gap between arms is 1.37×. Two runs
per arm cannot tell those apart.

**The round-1 ceiling was not a ceiling:** round 2's journal peaked at 52 980 kB, not 72 256. There is
no fixed cap to explain. The step shape — flat, then a jump inside twenty seconds, then flat — is what
still wants one.

- AC: **three completed runs per arm, interleaved, first discarded**, before any number here is
  quoted. Two per arm, one of them partial, is what exists; the control's own spread is as large as
  the effect being looked for.
- AC: the step shape needs one probe of its own — it is the only thing in this soak that no
  explanation covers.

- AC: **superseded by the reading above** — the control was to fail, and it failed in latency and
  readiness rather than in memory. The comparison the treated arm answers is narrowed to match.
- **Not yet run: the `swept` arm.** Started and stopped when the session paused; nothing from it is
  recorded.

- AC: the control arm fails. If it does not, the run is void and is redesigned rather than published.
- Anchors: `bench/soak.sh`, `server/src/commonMain/kotlin/io/github/youndie/xyk/db/`
