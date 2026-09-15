# B-20 pilot — VOID, and both reasons are findings

**Date:** 2026-09-15. **Subject:** `bench-a`, 4 cpu, 7.7 GB, Ubuntu, glibc 2.43 — the two **static**
binaries, run directly, no docker. **Generator:** `bench-b`, 4 cpu, k6 **v1.4.1**, ~1 ms away.
**Twin driver:** `modernc.org/sqlite`, pure Go. Offered rate 2 000 rps, 200 connections, 15–20 s.

This is not a result. It is the harness finding two things wrong before any number was quoted, which
is what it is for.

## The numbers, and why none of them may be used

| arm | rps delivered | p50 | p99 | dropped | failed |
|---|---:|---:|---:|---:|---:|
| go (ingest) | 630 | 286.7 ms | 820.1 ms | 27 203 | 0 |
| kotlin (ingest) | 523 | 378.7 ms | 454.0 ms | 29 385 | 0 |
| **control** (Kotlin binary, `/health/live`, no database) | **568** | 388.4 ms | 1006.4 ms | 28 488 | 0 |

**The control is not the fastest, so the run is void by the rule declared before it.** A route that
touches no database delivering fewer requests than an ingest path that does means the limit is not in
the service. All three arms land between 523 and 630 rps — the signature of a shared ceiling.

**And the ceiling is not the generator's CPU.** Measured during a run: k6 at **72.7 % of one core**,
load average 0.51 on a 4-core box. The same control arm run alone immediately afterwards delivered
**717 rps at p50 8.6 ms** — a fiftieth of the p50 it showed in the sequence above. So the harness has
at least two defects: something limits in-flight concurrency to a fraction of the 200 connections it
claims to use, and runs interfere with each other when they follow one another without a pause.

**Nothing here says anything about Kotlin/Native, Ktor or SQLite.** Quoting 523 rps as "the platform"
would be exactly the mistake this column exists to prevent.

## What the pilot did establish

**The twin had a real defect under load, and the parity gate could not see it.** At 2 000 rps offered
the Go arm reported **48.7 % failed requests** — `500 {"error":"not stored"}` — because
`modernc.org/sqlite` returns `SQLITE_BUSY` the moment two connections want the writer lock, and
nothing was waiting for it. The Kotlin side waits; the twin did not, so it was *fast because it gave
up*. Fixed with `?_pragma=busy_timeout(5000)` in the DSN; failures went to zero.

**Parity under load is a different claim from parity.** `bench/parity.sh` exercises one request at a
time and passed both before and after this defect. A twin that answers correctly one request at a
time and sheds half of them under load is not doing the same work, and no gate in this repository
noticed. The gate now runs a short concurrent burst as its last case.

**The static binaries run on a host that has nothing to do with the build.** Built against glibc 2.39
on WSL, copied to a 2.43 Ubuntu box, started and served with no runtime, no image and no
dependencies. That is the `-static` claim of B-05 confirmed somewhere it could have failed.

## What has to happen before a number is quoted

1. Find what caps in-flight concurrency in the generator — 200 connections at p50 8.6 ms should be
   thousands of requests per second, not hundreds. Suspects, in order: k6's VU scheduling under
   `constant-arrival-rate`, connection reuse, and the per-iteration `check()`.
2. A settle between arms, and a check that the subject is idle before a round starts.
3. Only then: three arms, interleaved, rounds repeated, first discarded.
