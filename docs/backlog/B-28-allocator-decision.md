---
id: B-28
title: "Decide the allocator on both criteria, not on the one that was measured last"
status: open
priority: P0
size: M
stage: stage-3-verdict
epic: feature-ingest
blocked_by: [B-20, B-21]
---

# B-28 — Decide the allocator on both criteria

Two criteria now point in opposite directions about the same line in `server/build.gradle.kts`, and
the temptation is to follow whichever was measured most recently.

| | `fixedBlockPageSize=16` (ships) | `-Xallocator=std` |
|---|---|---|
| **memory**, 64 MiB at 200 connections, 10 rounds ([B-21](B-21-criterion-memory.md)) | **1/10 survived** | **10/10 survived**, 54.9–65.7 MB |
| **throughput**, 2 000 rps over 200 connections ([B-20](B-20-criterion-throughput.md)) | 437 rps, 3 interleaved rounds, spread 1.07× | 250–442 rps, **one run per round of a memory campaign**, 0 % failed |
| inherited evidence | the portfolio default | measured *worse* on another service with SQLite on the request path |

**The memory column is decisive and the throughput column is not.** `std`'s 250–442 rps came out of
B-21's rounds, where the run was not interleaved against anything, the arms were not alternated for
throughput, and the first round was not discarded. It sits in the same band as `fixed16`'s 437, which
is evidence that the swap is not obviously expensive — and evidence is not a column.

- **Decision: the swap is not made until B-20's table exists with `std` in the Kotlin column.**
  Choosing on one of two declared criteria is how a service ends up meeting the criterion nobody
  deployed against.
- **Decision: the inherited "std is worse with SQLite on the request path" is treated as a
  hypothesis about *this* service until that table exists.** It was true where it was measured. It
  did not transfer to xyk's memory behaviour; whether it transfers to throughput is the open half.
- **Rejected: shipping both and choosing at deploy time.** A `binaryOption` is a build, and two
  builds means the image nobody measured is the one somebody runs.
- Not covered: `MALLOC_ARENA_MAX`, which the earlier run found buys steadiness rather than a lower
  floor and which is an environment variable rather than a build.

- AC: `bench/columns.sh` run with the `std` binary in the Kotlin column, three interleaved rounds,
  first discarded, against the same twin and control.
- AC: whichever way it comes out, `server/build.gradle.kts`'s allocator comment stops quoting the
  katcher numbers and quotes xyk's own — the comment there already says that is what should happen.
- Anchors: `server/build.gradle.kts`, `bench/columns.sh`, `bench/memory-declared.sh`
