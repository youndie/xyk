# B-32 — the pre-registration, frozen

What follows was written on 2026-10-02 before any measurement of this item. Its bytes below the
marker are recorded in [B-32](../../backlog/B-32-arena-cap-on-paged-off.md) and checked by
`scripts/brief_freeze.py` in `make docs`; nothing below the marker is edited afterwards, including
where it turns out wrong — a correction is appended to the results, dated, beside the number it
affects.

<!-- ---8<--- everything after this line is the received text, byte for byte ---8<--- -->
# Pre-registration: `MALLOC_ARENA_MAX=2` on the `pagedAllocator=false` build

**Question:** the shipping image runs a binary linked with `-Xbinary=pagedAllocator=false` (every
Kotlin allocation goes to glibc malloc) and sets `MALLOC_ARENA_MAX=2`. What does each of the two buy
on xyk — memory under the 64 MiB limit and CPU per request — what does the pair buy, and is the
arena cap dangerous for this allocator: contention under parallel load, and the tenfold memory
regression it produced on `-Xallocator=std` on another service (research §1.8)?

**Budget:** S0 builds, lever and controls 1.5 h; S1, S2, S3 each 0.5 h of measuring plus up to
30 min of waiting for a quiet machine before it; write-up 1 h. A step that overruns is recorded as
"not completed", with the reason, and the next one starts.

**Output:** `docs/research/measurements-2026-10-02/arena-cap.md`, raw runs under `raw/` beside it,
a verdict per question in research §1.8, the `MALLOC_ARENA_MAX` comment in both Dockerfiles.

**Non-goals:** choosing a different allocator (B-28 is not reopened); tuning any other malloc
setting; a soak (B-30 owns time).

## Arms

All from one commit, one `docker/scratch.Dockerfile` build each, `XYK_HTTP_CLIENT=true` as
published. An arm "without" the cap runs the same image re-imported with only the
`MALLOC_ARENA_MAX` line left out of its configuration, so A0 and A1 are the same bytes.

| Arm | Binary | `MALLOC_ARENA_MAX` | Role |
|---|---|---|---|
| A0 | `paged-off` | 2 | what ships — the pair |
| A0b | `paged-off` | 2 | A0 again in its own slot: the ruler |
| A1 | `paged-off` | unset | the cap removed |
| C | `default` (`pagedAllocator=true`) | unset | neither; the positive control of the CPU column |

## Series

Every series runs `bench/memory.sh` on the Linux box: the subject on cpus 0-3, k6 on cpus 12-19,
k6 talking to the container's address, six rounds, arms in rotating order, round 1 discarded, five
counted. Each series carries its own memory control: the shipping arm at 6 MiB, two runs, both must
be killed or the series is void.

| Series | Limit | Offered load | Arms |
|---|---|---|---|
| S1 (light) | 64 MiB | 200 rps over 50 connections, 30 s | A0, A0b, A1, C |
| S2 (declared) | 64 MiB | 2 000 rps over 200 connections, 30 s — saturates the service | A0, A0b, A1 |
| S3 (unconstrained) | 512 MiB | 200 rps over 50 connections, 30 s | A0, A0b, A1, C |

200 rps is 53 % of the 379 rps this allocator delivered at the declared concurrency (B-28), inside
the 50-70 % operating band; the light series are valid only where delivered ≥ 95 % of offered.

## Units and estimator

- **Memory:** cgroup `memory.peak` (kB) and `oom_kill`, threads beside it. Survival = not killed
  within a 30 s round.
- **CPU:** µs of the subject cgroup's `usage_usec` over the load window per request answered `200`.
  rps is recorded and decides nothing, except in S2 where the delivered count at a fixed concurrency
  is the throughput question itself.
- **Latency:** k6 p50 and p99, relative between arms only (generator on the same host, disjoint
  cores); no absolute latency from this item is quotable.
- **Estimator:** `bench/paired.py`, the paired per-round difference in % of the base, 95 % Student
  interval over the counted rounds; its exclusions (killed, short of 95 % delivered, a host busy
  beside the subject by more than one core over the series median) are printed.
- **Ruler:** A0b vs A0 on the same metric in the same series. An effect counts only if
  distinguishable **and** ≥ its floor **and** ≥ twice the ruler's interval half-width. A ruler wider
  than 5 % on CPU per request voids that series' CPU verdicts (not measured).

## Questions and verdicts

| Q | Question | Green | Red |
|---|---|---|---|
| Q1 | Memory: does the cap lower the `paged-off` build's memory? | S3: A0's peak ≥ 10 % below A1's, distinguishable, ≥ 2× ruler; and in S1 and S2 A0 is killed in no more counted rounds than A1 | in S1 or S2 A0 is killed in more counted rounds than A1, or in any series A0's peak is ≥ 10 % above A1's, distinguishable, ≥ 2× ruler |
| Q2 | CPU: does the cap cost CPU per request on `paged-off`? | in S1, S2 and S3 the upper end of the interval of A0 vs A1 is under +5 % | in any series A0 vs A1 ≥ +5 %, distinguishable, ≥ 2× ruler |
| Q3 | Contention: does the cap slow requests under parallel load? | S2: the lower end of A0 vs A1 delivered requests is above −5 %; S1 and S3: the upper end of A0 vs A1 p99 is under +20 % | S2: A0 delivers ≥ 5 % fewer, distinguishable, ≥ 2× ruler; or S1/S3: A0's p99 ≥ 20 % above A1's, distinguishable, ≥ 2× ruler |
| Q4 | Does `paged-off` itself still earn its place under 64 MiB? | S1: C killed in ≥ 3 of 5 counted rounds while A0 is killed in none | S1: C survives all 5 counted rounds and its S3 peak is ≤ A0's |

Anything neither green nor red is grey, and the results say which green condition it missed. The
pair (A0 vs C) is reported as measured — S3 CPU and peak, S1 survival — with no verdict of its own:
it is Q1/Q2 plus Q4.

**Positive controls.** Memory: the 6 MiB kill in every series. CPU: A1 vs C in S3 must come out
distinguishable with A1 the more expensive — a 19 % difference in that direction was measured on
this service before (pgo-native-spike, quoted in kotlin-skills `memory-under-a-limit.md`). If it
does not, the CPU column cannot see a known difference and Q2 and the CPU half of Q3 are not
measured.

**Lever engaged.** Read from the subject after every round: `MALLOC_ARENA_MAX` in
`/proc/<pid>/environ` (A0 `2`, A1 `unset`) and the count of glibc heaps (64 MiB-aligned anonymous
rw mappings). If A1 never creates more heaps than A0 in a series, the cap had nothing to cap there,
and that series' A0-vs-A1 differences are read as noise, whatever their sign.

**Load delivered.** Every round's k6 summary export; a round without one is void and voids its
series (the harness refuses).

## Decision rule, declared now

- Any of Q1, Q2, Q3 red → `MALLOC_ARENA_MAX` is removed from both Dockerfiles and the image smoke
  is run green on the result.
- Q1 green and none red → kept, the comment carries the verdict.
- Q1 grey and none red → kept, and the comment says the benefit is not shown on this build.

## Kill and void conditions

- **The machine is not quiet.** Before every series: the CI runner on the box idle (`busy=false`),
  1-minute load average under 3, no Gradle, Kotlin daemon or `docker build` process above 10 % CPU,
  the rest of the host under one core in a 3-second `top` sample. Not quiet → wait up to 30 min,
  logged; still not quiet → that series is not measured, and it is not run on a noisy machine.
  After every series the runner is asked again; busy at the end → the series is re-run once if the
  budget allows, otherwise its verdicts are written as measured on a shared machine and marked so.
- The 6 MiB control survives → series void.
- Fewer than 4 counted rounds per compared pair after exclusions → that comparison is not measured.

## Believed and not yet checked

- `pagedAllocator=false` routes Kotlin allocation through malloc the way `-Xallocator=std` does, so
  §1.8's std+cap regression may apply — this item checks it on this build rather than by analogy.
- glibc's arena ceiling counts the host's cores, not the container's cpuset (research §1.8); the
  lever column shows how many heaps the subject actually made.

## Predictions

- **H1** — Q1 red or grey: on a build that sends every allocation through malloc with ~100 threads,
  two arenas accumulate the churn, as on std elsewhere; at most no gain.
- **H2** — Q3 shows the cost, if any, in S2 only: the light series has too few requests in flight
  to contend.
- **H3** — Q4 green: the default allocator is killed at 64 MiB as in B-21 (0/10 then).

## Threats to validity

- One host; the generator shares it on disjoint cores. Memory and CPU per request are valid under
  that; tail latency is relative only.
- 30-second rounds: survival here is not survival over time (B-30 measured ~2 kB per request
  growth with deliveries failing; every arm pays it equally here).
- Delivery workers run against an unresolvable subscriber, as in B-21, identical across arms.
