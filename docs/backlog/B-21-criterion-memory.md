---
id: B-21
title: "Criterion: 64 MiB limit, ten runs out of ten survive"
status: wip
priority: P0
size: L
stage: stage-3-verdict
blocked_by: [B-06, B-17]
---

# B-21 — Criterion: 64 MiB limit, 10/10

**Declared before the code, on 2026-09-15:** under the scenario of
[B-20](B-20-criterion-throughput.md), the container survives a 64 MiB limit ten times out of ten.

**And declared with it: the existing evidence says this is the criterion that fails.** A Ktor service
on Kotlin/Native with **no database at all**, measured on 2026-09-15 at exactly this load, peaked at
65.3 MB with the recommended allocator settings — above the line, before SQLite is in the picture.
The arm that would fit, `-Xallocator=std` at 39.3 MB, is the one that measured *worse* on a service
with SQLite on the request path; and combining it with `MALLOC_ARENA_MAX=2` produced a tenfold
regression and three kernel kills out of ten
([research §1.8](../research/research-architecture.md)).

Saying that now is the point. A criterion quietly relaxed after the run measures nothing, and "how
far off, and because of what" is the more interesting result either way.

- **The four arms, on xyk's own binary:** default; `fixedBlockPageSize=16`; `fixedBlockPageSize=16` +
  `MALLOC_ARENA_MAX=2`; `-Xallocator=std`. Ten runs each, interleaved.
- **Decision: a positive control is part of the measurement.** The same image at a deliberately small
  limit **must** be killed with `exit=137`. A harness that cannot detect a failure cannot report its
  absence — and a run in which every arm survives is re-run rather than published.
- **Decision: `fixedBlockPageSize` is verified to have reached the compiler**, by md5 against a build
  without it. A flag that silently does nothing leaves the measurement comparing one thing with
  itself.
- **Decision: what is recorded is peak RSS *and* peak thread count.** Resident memory on this
  platform follows the thread count, not the live heap; without threads in the table the number has
  no mechanism attached to it.
- **Rejected: tuning `GC.targetHeapBytes` to fit.** It is a collection threshold, not a process
  limit: a 4 MiB target still left 110 MB resident elsewhere. Kotlin/Native does not read its cgroup
  limit at all — which is itself one of the three columns' findings, since the JVM and Go both do.
- Not covered: the long-run memory behaviour under a growing journal, which is
  [B-24](B-24-soak-wal.md).

- AC: a table of four arms × ten runs with survivals and peak RSS, the positive control included, the
  host named. **The harness exists** (`bench/memory.sh`) and enforces every part of this line by
  itself.

## What the harness found before it measured anything (2026-09-15)

**The first control limit was wrong, and the harness said so instead of proceeding.** 24 MiB was
picked as "obviously too small"; the service survived it twice with a peak of 15–16 MB, so the run
was declared VOID and stopped. A control that does not die cannot show that a survival means
anything.

**Then the metric turned out to be wrong too, and the corrected number is better.** The first search
used the process's `VmHWM`, which reported 9 600 kB for a container the kernel was holding under 8
MiB — a number larger than its own limit, which is the tell. The kernel charges a cgroup for what it
accounts, so `memory.peak` and `memory.events` are what the harness reads now
([research §1.19](../research/research-architecture.md)).

Measured that way:

| limit | starts and serves? | cgroup peak | |
|---|---|---|---|
| 12 MiB | yes | 9 424 kB | |
| 10 MiB | yes | 9 232 kB | |
| **8 MiB** | **yes** | 8 192 kB | the floor — everything this service is, inside 8 MiB |
| 6 MiB | no | — | the control |
| 5 MiB | no | — | |

At 8 MiB the peak sits exactly on the limit, because the kernel reclaims the mapped pages of the
binary to fit; given 64 MiB it settles around 15 MB.

And the enforcement itself was checked rather than assumed: a container told to allocate 200 MB under
a 24 MiB limit is killed with `exit=137` on this host, so the limits are real and cgroup v2 is doing
what it says.

**The three binaries differ.** `fixed16`, `default` and `std` have different md5 sums — the check the
skill demands, because a `binaryOption` that silently does nothing would leave the measurement
comparing one build with itself three times. The fourth arm, `MALLOC_ARENA_MAX=2`, is an environment
variable and needs no build.

## The run of 2026-09-15 is RETRACTED — the generator never started

Control: 2 of 2 killed at 6 MiB, and that part stands. Everything after it does not.

**All forty generator logs of that run say `stat /bench/ingest.js: permission denied`.** The harness
bind-mounted the working tree, which on the Linux box is a mutagen replica at mode `0600`, and the
`grafana/k6` image runs as a non-root user. k6 exited in milliseconds every time, the harness never
looked at its status, and each round then measured a container that received **no requests at all**.
The table it produced — four arms, 10/10 each, peaks from 5 608 to 35 828 kB — is a table of an idle
process, and it is retracted in full:
[memory-64mib.md](../research/measurements-2026-09-15/memory-64mib.md),
[research §1.20](../research/research-architecture.md).

**The allocator ranking goes with it.** The arms did differ from one another, so those numbers are a
real measurement of idle footprint per allocator — a different and much weaker claim than the one
that was written. They are not re-quoted, because a number whose stated conditions were false is
re-taken rather than re-worded.

**The tell was in the table.** Every arm sat at 18 threads, which the retracted document noticed and
explained as a stand too weak to generate concurrency. Eighteen threads under a nominal 200 rps over
50 connections is not a weak stand, it is no load, and the row saying so was on the page.

**Two guards, both of which this run would have failed:**

* `bench/memory.sh` now requires `http_reqs` in each round's generator output, records a round without
  it as `no-load`, and refuses to print a summary table if any round is one;
* `bench/soak.sh` asks the **subject** — not the generator — thirty seconds in whether any event has
  been stored, and aborts if none has. Asking the subject is the point: it is the question a broken
  generator cannot answer in the affirmative.

Both harnesses had a positive control for the subject dying, and both controls behaved correctly.
Neither was a control for whether any load was applied, which is the general lesson: **a control
proves the stand can detect the failure it was built for, and nothing else.**

**Status: unmeasured.** The harness is fixed and the arms are unchanged; the run has to happen again.

## The re-run (2026-09-15) — [memory-64mib.md](../research/measurements-2026-09-15/memory-64mib.md)

Same host, same arms, same ten interleaved rounds, same 6 MiB control (2 of 2 killed). The only
change is that the scenario is staged where the generator can read it, and every round is now scored
only after `http_reqs` is found in its output.

| arm | survived | cgroup peak, kB | threads (max) |
|---|---|---:|---:|
| `default` | **0/10 — killed every round** | — | — |
| `fixed16` (ships) | 10/10 | 45 112 – **65 536** | 146 |
| `fixed16` + `MALLOC_ARENA_MAX=2` | 10/10 | 42 120 – 56 188 | 117 |
| `-Xallocator=std` | 10/10 | **25 428 – 34 056** | 101 |

**The ranking is the opposite of the retracted one.** `default`, which the idle table had surviving
10/10, does not survive at all. `-Xallocator=std`, which the idle table said there was "no reason to
take", is the only arm with real headroom — half the shipping arm's peak and the steadiest of the
four. Threads went from 16–18 to 49–146, which is the whole difference: resident memory on this
platform follows the thread count, and the retracted run had no threads to follow.

**`fixed16` survives with no margin.** Round 8 peaked at exactly 65 536 kB — the limit itself, which
is the kernel reclaiming to fit rather than a process with room. Ten survivals at the edge is a
weaker claim than ten survivals.

**The prediction in this item is neither confirmed nor refuted, and is now close to being decided.**
It said 64 MiB would fail. At a tenth of the declared load one arm fails outright, the shipping arm
passes while touching the limit, and one arm has room. The remaining question is the ten-fold load,
which is [B-20](B-20-criterion-throughput.md)'s to answer.

**Open: whether `-Xallocator=std` should ship.** It measured worse elsewhere in this portfolio on a
service with SQLite on the request path, and that is the only argument against it now that its
numbers here are the best. It needs the throughput column beside it before the swap, not instead of.

- AC: whichever way it comes out, the verdict is written into
  [research §1.8](../research/research-architecture.md) at the point where the prediction is, as a
  confirmation or a refutation — not appended elsewhere as a new fact.
- Anchors: `bench/memory.sh`, `server/build.gradle.kts`, `docker/native.Dockerfile`
