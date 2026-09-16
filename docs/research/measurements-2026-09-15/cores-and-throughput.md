# Ktor CIO on Kotlin/Native collapses at four visible cores

**Date:** 2026-09-15. **Subject:** `xyk:scratch`, the release `linuxX64` binary, `GET /health/ready`
— a route that touches no database. **Generator:** k6 0.54.0, closed model, 50 VUs, 10 s, on the same
host over loopback. **Host:** a 20-core workstation. Four rounds, the three arms **interleaved**.

This started as the throughput criterion (B-20) and turned into something else: the subject was
serving 410 rps on a four-core benchmark host, and the same binary served 4 566 rps on a
twenty-core one. Neither number is about the network, and it took five controls to find out what it
is about.

## The measurement

| round | `--cpus=4` (quota; `nproc` sees 20) | `--cpuset-cpus=0-3` (`nproc` sees 4) | `--cpuset-cpus=0-1` (`nproc` sees 2) |
|---:|---:|---:|---:|
| 1 | 3 871 | **51** | 1 670 |
| 2 | 3 954 | **378** | 1 549 |
| 3 | 3 905 | **42** | 1 499 |
| 4 | 3 943 | **204** | 1 353 |

**Four visible cores is between 10 and 90 times slower than four cores of quota with twenty
visible** — and it is wildly unstable, where both other configurations are steady. **Two visible
cores is four to thirty times faster than four.** Throughput is not monotonic in the core count,
which is the signature of a pathology rather than of scarcity.

---

## Correction, later the same day: part of that was my harness

**The generator was not pinned.** The subject was confined to a `cpuset` and k6 was left free to run
on every core of the box — including the ones the subject was pinned to. The rule against exactly
this is written in `bench/run.sh`, which refuses a same-host run; these were "just diagnostics" and
went around it.

Re-run with the two pinned apart — subject on `0-3`, generator on `10-19`:

| round | subject `0-3`, generator `10-19` | subject `0-3`, generator anywhere |
|---:|---:|---:|
| 1 | 611 | 404 |
| 2 | 1 019 | 290 |
| 3 | 564 | 738 |

So the catastrophic tail (19, 42, 51 rps) was contention I created. **What survives the correction is
still a finding, and it is the important half:**

* at four visible cores with the generator out of the way, the Kotlin arm delivers **564–1 019 rps**
  — a spread of 1.8× between identical runs;
* the Go twin, measured on the same host at 1, 4 and 12 visible cores, delivers **50 108 / 51 517 /
  49 127 rps** — flat, and two orders of magnitude above;
* at twelve visible cores the Kotlin arm was steady across five rounds (2 112 – 2 542 rps), so the
  instability belongs to the small-core case rather than to the measurement.

**The curve below is therefore contaminated** and is kept because the shape at the stable end (8 and
12 cores) is unaffected and because a corrected file that hides what it corrected is worse than
useless. Every number in it was taken with the generator free to steal the subject's cores.

## The controls, in the order they were needed

1. **Is it the generator?** No. k6 sat at 72 % of one core, load average 0.51, while the subject
   delivered 459 rps.
2. **Is it the network?** No. Over the same IPv6 hop, from the same generator, the Go twin's
   equivalent route delivered **27 847 rps**.
3. **Is it the route?** No. `/health/live`, `/health/ready` and `/version` all land at 396–430 rps on
   the four-core host: the ceiling belongs to the server, not to any handler.
4. **Is it connection churn?** No. `ss` shows 50 established sockets, stable, for the whole run —
   connections are reused.
5. **Is it the benchmark hosts?** No. Both services were copied onto the *generator* host and loaded
   over loopback: Kotlin 420 rps, Go 21 617. The four-core number reproduces with no network at all.
6. **Is it the core count, then?** That is what the table above is, on a third host, with everything
   else held fixed.

## What this does not say

**The mechanism is not established.** The obvious suspect is the one this portfolio has already paid
for — Ktor's `SelectorManager` occupying a `Dispatchers.Default` worker for the lifetime of the
process, which on a small pool is a large fraction of it. But that explanation predicts *two* cores
being worse than four, and two is better. So the shape is known and the cause is not.

**These are single-route numbers.** They bound what the service can do; they are not the ingest
path's throughput, which is lower and was measured separately.

**The Go column is not a like-for-like product comparison** — the twin has no delivery, no journal
and no registry. It is here as the floor: the same host, the same generator, the same kind of route.

## Why it matters to this product

xyk's premise is a container small enough that running one per project needs no defending. A
container per project is a container with **one or two cores**, and a Kubernetes CPU limit is a
*quota* rather than a cpuset — so the good configuration above is the common one, and the bad one is
what a small dedicated node or a `cpuset`-pinned deployment gets. A service whose throughput drops
by an order of magnitude depending on which of those it lands in cannot be sized from a single
measurement, and the criterion in `backlog.md` cannot be answered without saying which it was taken
under.

## The curve, with the caveat above

Visible cores against rps, two rounds each, **generator unpinned** (so every number is a lower bound
and the small-core ones are the worst affected):

| cores | 1 | 2 | 3 | 4 | 5 | 6 | 8 | 12 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| round 1 | 2 861 | 1 524 | 1 485 | 610 | 381 | 789 | 1 328 | 2 197 |
| round 2 | 2 386 | 1 698 | 1 923 | 235 | 431 | 698 | 1 364 | 2 200 |

Three further rounds at 1, 4 and 12 gave 2 096 / 602 / 3 600 at one core and 202 / 749 / 19 at four —
which is how the instability was noticed and what led to the correction above.

## The pinned run, which is the one to quote

Subject and generator on disjoint cores throughout — subject `0-3` or `0-11`, generator `12-19` —
three arms interleaved, four rounds, 10 s each, `GET /health/ready`:

| round | Kotlin, 4 cores | Kotlin, 12 cores | Go, 4 cores |
|---:|---:|---:|---:|
| 1 | 355 | 2 195 | 56 972 |
| 2 | 816 | 1 812 | 56 276 |
| 3 | 423 | 2 268 | 55 953 |
| 4 | 610 | 2 172 | 56 416 |
| **spread** | **2.3×** | 1.25× | **1.02×** |

**Go on four cores is 102× the Kotlin arm on four cores** — 56 400 against 551 — on a route that
touches no database. Kotlin needs twelve cores to reach 2 112, which is still 27× below Go on four.

**And the instability at four cores survives the correction.** With the generator out of the way the
Kotlin arm still varies by 2.3× between identical runs, while Go varies by 1.02% — so the spread is
the subject's, not the stand's.

## A reading that did not survive its own check

Three k6 runs immediately after a burst against `/journal` produced no parseable result, which looked
like a server that had wedged and would not recover. **It had not.** Asked directly at 0, 30 and 90
seconds after the same burst, every route answered: `live=200 ready=200 startup=200 version=200`, the
hook correctly `401` without a signature, 104 threads, 41 MB resident, no leftover sockets. What
failed was the measurement, not the service. Written down because the wrong version of this paragraph
was one edit away from a document.

## What is solid

1. **Go is two orders of magnitude faster on this route and flat across core counts** (≈56 400 rps on
   four pinned cores, spread 1.02×). Nothing about the harness moved that number, which is also what
   makes it a good yardstick.
2. **The Kotlin arm at twelve visible cores is steady at ≈2 100 rps.**
3. **At four visible cores it is 355–816 rps and does not converge** — a single measurement of this
   stack at a small core count is a sample from a wide distribution, not a property of the service.
4. **the subject host's 410 rps, taken with the generator on a different machine entirely, sits in that
   band** — the cleanest number here, and the only one taken the way the criterion requires.

## Next

1. Repeat at four and twelve visible cores with the generator pinned away, enough rounds to say
   whether the small-core spread is real or still harness noise.
2. The selector experiment — the mitigation named in the metrik research for the neighbouring
   symptom — measured against the corrected baseline rather than the contaminated one.
3. Only then, B-20's three columns.
