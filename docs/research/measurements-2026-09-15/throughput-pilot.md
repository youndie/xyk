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

---

# The cap was found, 2026-09-16, and it was in this file's own harness

**"Something limits in-flight concurrency to a fraction of the 200 connections it claims to use" was
the first of the three things this pilot said had to happen before a number is quoted. It was the
scenario's VU pool, and the line that set it explained itself in the comment above it.**

`bench/ingest.js` read:

```js
preAllocatedVUs: connections,   // 200
maxVUs: connections,            // 200
```

with the reasoning that the criterion names 200 connections, so the generator must not open a 201st.
**A VU under `constant-arrival-rate` is not a connection.** It is occupied for the whole round trip,
so the pool caps *requests in flight*, and the highest rate such a pool can offer is `VUs ÷ latency`.
At the 380 ms the service was showing, 200 VUs cannot offer more than **526 rps** — and the three
arms of the table above came out at 523, 568 and 630.

So the "signature of a shared ceiling" was real and the ceiling was ours. Sizing the pool by the
connection count silently converts the open model back into a closed one, which is precisely what the
scenario's own header says that executor exists to prevent.

## The generator's real ceiling, measured on the same pair

bench-b → bench-a over the private network, against the Go twin's `/health/ready`, `maxVUs` 2 000–3 000:

| offered | delivered | dropped | latency |
|---:|---:|---:|---|
| 1 000 | 999.6 | 0 | p50 649 µs |
| 2 000 | 1 999.3 | 0 | p50 542 µs |
| 4 000 | 3 998.0 | 0 | p50 515 µs |
| 8 000 | 7 996.5 | 0 | p50 498 µs |
| 16 000 | 15 429.5 | 4 560 | p99 200 ms |
| 24 000 | 20 693.8 | 32 718 | p99 218 ms |

**The generator is not the limit anywhere near this criterion.** It offers 8 000 rps — four times
what B-20 asks for — with zero dropped iterations and half a millisecond of latency, and only begins
to strain at 16 000. Any ceiling seen below that belongs to the subject.

The twin binary used here is the one the pilot left on the host and is **stale** — it predates
migrations v5–v7 and the delivery half. That does not matter for this measurement, which is about
what k6 can push, not about what the twin can serve; it would matter for a column in the table and
that column is not being quoted.

## And the criterion reads differently once the two numbers are joined

"2 000 rps at 200 connections" is not two independent constraints. Under an open model the
concurrency is an outcome — `rate × latency` — so holding 2 000 rps inside 200 requests in flight is
arithmetically the same statement as **a mean latency under 100 ms**. The harness now asserts it that
way: the threshold is declared before the run, and `bench/run.sh` reads the peak VU count out of k6's
own summary and records a violation when the service forced more concurrency than the criterion
allows. What was an unmeasured half of the line is now the half that is easiest to check.
