---
id: B-28
title: "Decide the allocator on both criteria, not on the one that was measured last"
status: done
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

## Decided, 2026-09-16 — [allocator-decision.md](../research/measurements-2026-09-16/allocator-decision.md)

The missing column exists: `std` in the Kotlin column of B-20's table, three interleaved rounds, the
first discarded, against the same twin and control.

| | `fixed16` | `std` |
|---|---:|---:|
| ingest | 437 (423–452) | **379** (375–384) |
| control | 434 | **331** |
| memory, 64 MiB × 10 | **1/10** | **10/10** |

**`-Xallocator=std` ships.** `server/build.gradle.kts`'s default is changed and its comment now
quotes xyk's own numbers, as the comment itself said it would.

**The Go column calibrates the two campaigns against each other** — the same binary a day apart, 601
against 620, overlapping. Without that, comparing two separate runs would be comparing two stands.

**The trade is 13 % of ingest throughput for the memory criterion**, and it is easy only because the
throughput criterion does not separate the arms: neither reaches a third of 2 000 rps, so 58 rps
changes no verdict. One criterion distinguishes them and one does not; the one that does decides.

- AC: **met** — the table exists with `std` in the Kotlin column.
- AC: **met** — the allocator comment quotes xyk's measurements rather than katcher's.
- Left in place deliberately: the other arms, and the inherited warning in research §1.8. The
  decision is re-runnable on a host where throughput is reachable, and the warning was true where it
  was measured.

## The shipped spelling is deprecated, and was replaced the same day

`-Xallocator=std` makes the compiler say:

```
w: Std allocator is deprecated in Kotlin/Native compiler and will be removed in the future.
   Please consider using -Xbinary=pagedAllocator=false compiler flag instead.
```

**The warning had been in the build output all along and was not read**, because the build was
grepped for `^e:` and `BUILD`. A filter over output hides what it was not asked for — which this
repository has paid for before, in a harness that read a run's result by grepping for the lines it
expected.

Shipping a flag scheduled for removal is a build that breaks on a Kotlin upgrade, in the one place
this service cannot afford it. So the replacement was verified rather than assumed: the binaries
differ by md5, which proves nothing either way since a build stamp differs too, and the memory arm
was re-run — five interleaved rounds at 64 MiB under the declared load, control dead twice of two:

| spelling | survived | peak, kB | threads |
|---|---|---:|---:|
| `-Xallocator=std` | 5/5 | 56 760 – 65 348 | 57 – 81 |
| `-Xbinary=pagedAllocator=false` | 5/5 | 60 904 – 65 852 | 85 – 121 |

**Both meet the criterion, and they are not identical** — the replacement sits closer to the limit
and runs more threads. `paged-off` is the default because the alternative is scheduled for removal;
the deprecated spelling stays selectable rather than deleted, because a difference that small is one
somebody may need to re-measure.
