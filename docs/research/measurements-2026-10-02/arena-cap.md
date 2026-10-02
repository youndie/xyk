# B-32 — `MALLOC_ARENA_MAX=2` on the `pagedAllocator=false` build

**Date:** 2026-10-02. **Host:** the Linux box (WSL2, kernel 6.6.87.2, 20 cores), subject on cpus 0-3,
k6 0.54.0 on cpus 12-19, k6 talking to the container's own address. **Binaries:** one commit
(`7704e0b`), both linked by `docker/scratch.Dockerfile` with the curl engine, as published —
`paged-off` sha256 `8e5faa76…8321568` (24 609 448 bytes), `default` sha256 `90ad2205…812f533`
(24 662 152 bytes). The arms without the cap run the same image re-imported with only the
`MALLOC_ARENA_MAX` line left out; the re-imported binary has the same digest
([raw/S0/S0-build.log](raw/S0/S0-build.log)). **Pre-registration:**
[arena-cap-brief.md](arena-cap-brief.md), frozen in `6c07f36` before the first run.

## Verdicts

| Q | Question | Verdict | The number | Where |
|---|---|---|---|---|
| Q1 | does the cap lower memory on `paged-off`? | **GREY** | `memory.peak` at 512 MiB −19.6 % [−72.9, +33.7], below resolution (the ruler alone is ±92 %); at 64 MiB both arms 6/6 and both peak at the limit | S3, S1r, S2r |
| Q2 | does the cap cost CPU per request? | **GREY** | +1.74 % [−1.69, +5.16] in the one series whose ruler held (±3.5 %); the green line was +5 % for the upper end | S1r |
| Q3 | does the cap slow requests under parallel load? | **GREY** | declared load: delivered +19.7 % [−14.7, +54.2], p50 −1.7 % [−9.3, +5.8]; light load p99 +11.7 % [−10.6, +34.0] | S2r, S1r |
| Q4 | does `paged-off` still earn its place under 64 MiB? | **GREEN** | the default allocator killed in 6 of 6 rounds, twice (S1, S1r); the `paged-off` arms in none | S1, S1r |

**The hazard the item was opened for did not reproduce.** On `-Xallocator=std` elsewhere the cap
meant a tenfold peak and three kills in ten (research §1.8). On this build at 64 MiB it meant no
kill in any of 48 capped runs (A0 and A0b, four series of six rounds, at the light and at the
declared load) — the uncapped arm survived its 24 as well — and the anonymous memory it
leaves is **lower**, not higher — that is Q1's red condition not met, not a green.

**Decision, by the rule declared in the brief:** Q1 grey and nothing red → `MALLOC_ARENA_MAX=2`
stays, and the comment beside it says the benefit is not shown on the declared metric.

**What the declared metric could not see, and an exploratory column did.** `memory.peak` counts
file pages — the 24 MB binary's mapping, SQLite's database and WAL in the page cache — and under a
roomy limit those swung a single arm's peak between 40 and 350 MB from round to round. The column
that separates them (`anon_kb`/`file_kb` from `memory.stat`, added after the first three series
and deciding nothing here) shows the cap working on exactly what it should:

| | anon at round end, cap (A0) | without (A1) | paired | ruler (A0b vs A0) |
|---|---:|---:|---:|---:|
| S1r — 64 MiB, 200 rps / 50 | 16.0 MB | 28.7 MB | **−43.0 %** [−46.9, −39.0] | ±6.6 % |
| S2r — 64 MiB, 2 000 rps / 200 | 30.1 MB | 50.0 MB | **−39.6 %** [−43.6, −35.7] | ±2.4 % |
| S3x — 512 MiB, 200 rps / 50 (shared machine) | 16.1 MB | 29.1 MB | **−43.4 %** [−46.4, −40.4] | ±1.1 % |

At the declared load the uncapped arm holds 50 MB of anonymous memory inside a 64 MiB limit and
survives by squeezing the page cache down to 3–10 MB; the capped one holds 30 MB. Same survival
count, different margin. That is the claim this item can make for the cap — a third less of the
allocator's own memory — and it is exploratory: it was not the pre-registered metric.

## The lever, read from the subject

`MALLOC_ARENA_MAX` from `/proc/<pid>/environ`: `2` in every A0/A0b round, `unset` in every A1 and C
round. glibc heaps (64 MiB-aligned anonymous rw mappings) per round:

| | A0, A0b (cap) | A1 (no cap) | C (default allocator, no cap) |
|---|---|---|---|
| light load | 0–1 | 54–101 | 71–100 (512 MiB only; dead at 64) |
| declared load | 0 | 78–136 | — |

136 heaps on a four-cpu cpuset: glibc's ceiling counts the host's twenty cores (8 × 20 = 160), not
the container's four — the believed item in the brief, confirmed on this build. Paged-on allocation
also reaches malloc (C's 71–100 heaps): the sqlx4k Rust half and curl go there whatever Kotlin does.

## Controls

- **Memory:** the shipping arm at 6 MiB was killed 2 of 2 in every series.
- **CPU:** `paged-off` against the default allocator, both uncapped, S3: **+18.13 %**
  [+7.72, +28.54], n = 5 — the known direction and size (+19.01 % measured by another study on
  this service). The CPU column can see a difference of that size.
- **Load delivered:** every round's k6 summary is in its series directory; at the light load every
  counted round of S1r and S3 answered ≥ 95 % of 6 000 offered with `200`.

## The pair, against neither

| | `paged-off` + cap (ships) vs default allocator, no cap |
|---|---|
| survival at 64 MiB, light load | 6/6 + 6/6 against 0/6 + 0/6 |
| CPU per request, 512 MiB (S3) | **+20.83 %** [+13.17, +28.49] |
| anon at round end, 512 MiB (S3x, shared machine) | 16 MB against 154–215 MB |

The pair's price is the allocator's (+18 %); the cap adds nothing distinguishable to it.

## How

`bench/memory.sh` (extended in this item: arms from `ARMS`, rotating order, CPU from the subject's
`cpu.stat`, the host's busy time beside it, the lever, k6's summary export), six rounds per series,
round 1 discarded; `bench/paired.py` for every comparison (its `--selftest` passes). Before every
series the box was required quiet — runner idle, load under 3, no build above 10 % CPU, the rest
under one core — and asked again after it ([raw/S0/g1-quiet.sh](raw/S0/g1-quiet.sh),
[raw/S0/g1-series.sh](raw/S0/g1-series.sh)).

| Series | Limit, load | Runner jobs started during it | Counted for |
|---|---|---|---|
| [S1](raw/S1/) | 64 MiB, 200 rps / 50 | 4 | survival only: CPU and latency had 1 counted round after the noise and delivery exclusions |
| [S2](raw/S2/) | 64 MiB, 2 000 rps / 200 | 4 | survival only: 3 counted rounds, under the brief's 4 |
| [S3](raw/S3/) | 512 MiB, 200 rps / 50 | 0 | peak, the CPU control, the pair; its own CPU ruler (±6.8 %) voids its Q2 reading |
| [S1r](raw/S1r/) | S1 re-run, the brief's one re-run | 2 (round 1 only, discarded) | Q2, Q3, survival |
| [S2r](raw/S2r/) | S2 re-run | 0 | Q3, survival; its CPU ruler (±5.24 %) voids its Q2 reading |
| [S3x](raw/S3x/) | S3 again, exploratory, for `anon_kb` | 8 | nothing — anon only, labelled shared |

Same host, same day, same images in every row: **yes**. Same machine load: **no** — S1 ran at
6–8 ms of CPU per request where S1r ran at 3.8 on the same arms, which is the contamination the
re-run exists for; no number from S1 or S2 is compared with any other series.

## What it does not show

- **A soak.** 30-second rounds; survival here is not survival over minutes (B-30's ~2 kB per
  request still applies, to every arm alike).
- **Absolute latency.** The generator shares the host on disjoint cores; p50/p99 are relative
  between arms of one series only.
- **CPU to 5 %.** The rulers were ±3.5, ±5.2 and ±6.8 % at five counted rounds. A cap costing
  between 0 and ~5 % per request is not excluded; one costing 10 % would have been seen.
- **The page-cache side of the 64 MiB margin.** The anon/file split is one reading at round end,
  not a peak.
