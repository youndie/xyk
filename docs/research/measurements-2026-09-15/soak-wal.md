# B-24 — the journal read while ingest writes, twenty minutes an arm

**Date:** 2026-09-15. **Host:** the Linux box, 20 cores, cgroup v2. **Subject:** `xyk-mem:fixed16` on
`distroless/cc-debian13`, `--memory=64m`, pinned to cores `0-3`, its `/data` bind-mounted so the
`-wal` file can be measured from outside. **Load:** k6 0.54.0, `constant-arrival-rate`, 200 rps
offered over 50 connections through `POST /hooks/{id}` with a genuine GitHub signature, generator
pinned to `12-19`; **four concurrent `GET /journal` readers** at 1 rps, which are the point — without
an overlapping reader SQLite checkpoints on its own and there is nothing to measure. Sampled every
10 s: the `-wal` file, the database file, the cgroup's `memory.current`, the thread count, liveness
and what `/health/ready` answers.

**Arms:** `unswept` = `XYK_WAL_CHECKPOINT_SECONDS=0`, SQLite's own PASSIVE behaviour and nothing
else — the control. `swept` = the same with `wal_checkpoint(TRUNCATE)` on a 30 s timer.

**One run per arm. Nothing below is a measurement yet** — see "What has to happen before any of this
is quoted" at the end.

## Round 1

| | `unswept` (control) | `swept` |
|---|---:|---:|
| `-wal` peak | **72 256 kB** | **36 812 kB** |
| `-wal` shape | 6 MB for 61 s, then `6 272 → 29 420 → 72 256` in twenty seconds, then flat for 18 min | a sawtooth: 27 100 → 31 452 → 15 680 → 7 772 → 26 192 → 21 284 → 16 256 → 5 384 |
| database peak | 55 712 kB | 49 056 kB |
| `memory.current` | pinned 63 760 – 65 524 kB against a 65 536 kB limit | pinned ≈65 200 – 65 456 kB |
| threads peak | 75 | 88 |
| `/health/ready` = 503 | **23 of 120 samples (19 %)** | **40 of 120 samples (33 %)** |
| delivered | 87.1 rps of 200 offered | **76.6 rps** of 200 offered |
| p50 / p95 / p99 / max | 4.76 ms / 2.30 s / 5.47 s / 8.37 s | 3.80 ms / 1.87 s / **10.55 s** / **24.06 s** |
| requests failed | 0 of 104 972 | 0 of 92 992 |
| `oom_kill` | 0 | 0 |

## What this says, and the direction is not the comfortable one

**The sweep does what it claims to the journal.** Half the peak, and the sawtooth is the mechanism
visible directly: the file is truncated and refills, truncated and refills, ending the run at 5 MB
where the control ended at 70.6 MB and had not moved since its first ninety seconds.

**And every service-level number is worse with it on.** Eleven rps fewer delivered, p99 roughly
doubled, max nearly tripled, and a readiness probe failing in a third of the samples instead of a
fifth. A `TRUNCATE` checkpoint has to wait for readers and then does its work while the writer is
still arriving; on a stand that is already saturated — 200 rps offered, 77–87 delivered — that cost
lands on the request path. **This is the opposite of what the item assumed when it called the sweep
the mitigation**, and it is the finding of the run rather than a disappointment in it.

**Neither arm died.** `oom_kill` is 0 in both, no request was ever rejected, and the container was
alive at every sample. The degradation is entirely latency and readiness.

## What the control did **not** reproduce

The mechanism inherited from a neighbouring service (and written into
[B-24](../../backlog/B-24-soak-wal.md) before the run) says: the journal grows linearly, **the
database file stops growing**, and the process is eventually killed on memory. Two of those three did
not happen here.

* **The database grew the whole time**, 3 140 → 55 712 kB. PASSIVE checkpoints were moving pages
  across throughout. What they cannot do while a reader is alive is *reset the file*, so what is
  guaranteed is a high-water mark that is never given back — not unbounded growth.
* **There was no ramp and no cliff.** 6 MB for a minute, a step to 70.6 MB over twenty seconds, then
  eighteen minutes of nothing. What puts that ceiling there is **not established**; two candidates,
  both untested: the writer slowed enough that PASSIVE checkpoints began keeping up, or the readers'
  gaps grew long enough to let one complete.

## Round 2 — and it withdraws half of the reading above

`unswept` ran to completion; `swept` was stopped by hand at **54 of 120 samples (~9 min)**, so its
row is partial and its latency figures do not exist — k6 never printed a summary.

| | `unswept` r1 | `unswept` r2 | `swept` r1 | `swept` r2 (partial) |
|---|---:|---:|---:|---:|
| `-wal` peak, kB | 72 256 | **52 980** | 36 812 | 38 688 |
| database peak, kB | 55 712 | 54 424 | 49 056 | 39 380 |
| `memory.current` peak | 65 524 | **65 536** | ≈65 456 | 65 536 |
| threads peak | 75 | **89** | 88 | 34 |
| `/health/ready` = 503 | 23 / 120 | 27 / 120 | 40 / 120 | **1 / 54** |
| delivered, rps | 87.1 | 85.1 | 76.6 | — |
| p95 / p99 / max | 2.30 / 5.47 / 8.37 s | 2.67 / 5.60 / 8.01 s | 1.87 / 10.55 / 24.06 s | — |

**The control is reproducible.** Two runs agree closely on everything that describes the service:
85.1 against 87.1 rps delivered, p99 5.60 against 5.47 s, 27 against 23 readiness failures. So the
stand is steady enough to compare arms on.

**The latency cost attributed to the sweep does not survive.** It rested on one `swept` run, and the
partial second one points the other way — **one** readiness failure in its first 54 samples where the
control had 23 and 27 across 120. Nine minutes is not twenty and the degradation in every run arrives
late, so this does not reverse the finding either; it removes it. **Round 1's "the sweep costs
latency and readiness" must not be quoted.**

**The journal difference holds in direction and not in magnitude.** All four pairings put `swept`
below `unswept` (36 812 and 38 688 against 52 980 and 72 256). But the control's own spread between
identical runs is **1.36×** (52 980 → 72 256), and the closest gap between the arms is **1.37×**
(38 688 against 52 980) — the same size. Two runs per arm cannot separate an effect from a spread
that large. What is worth noting is that the two `swept` values agree with each other to 5 % while
the two `unswept` values differ by 36 %, which is the sawtooth doing what a sawtooth does.

**And the unexplained ceiling was not a ceiling.** Round 1's journal stopped dead at 72 256 kB and
stayed there; round 2's peaked at 52 980. So there is no fixed cap to explain — the number is
wherever that run happened to land, and the "step, not a ramp" shape is the thing that still wants an
explanation.

## What has to happen before any of this is quoted

**One run per arm is not a measurement.** The journal difference is large (2×) and has a visible
mechanism, so it is likely to survive; the latency and readiness differences are single samples from
a saturated stand and could be noise of the same size. A second interleaved round of both arms is
running; three rounds per arm, interleaved, is the bar this repository has used elsewhere, and the
first run of a restarted service measures warm-up rather than the service.
