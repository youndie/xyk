# B-20 — the three columns, on two machines: nobody reaches the line

**Date:** 2026-09-16. **Subject:** the subject host — a 4-core, 7 GB cloud VM, Ubuntu, glibc 2.43 — both static
binaries run directly, no docker between the measurement and the thing measured. **Generator:**
the generator host, 4 cpu, **k6 v1.4.1**, on a private network, about half a millisecond away.
**Load:** `constant-arrival-rate`, **2 000 rps offered over 200 connections**, 30 s an arm, three
rounds, arms interleaved, **round 1 discarded as warm-up** — all declared before the run.

**The Kotlin arm is the ingest-only build**: statically linked, no outbound engine, therefore no
delivery workers. The twin has no delivery either. Measuring the shipping build here would put
background work in one column and not the other.

## The table

| arm | round 2 | round 3 | mean | spread | p50 | p99 | dropped | failed | peak concurrency |
|---|---:|---:|---:|---:|---|---|---:|---:|---:|
| **xyk** (`POST /hooks/{id}`) | 422.8 | 451.6 | **437** | 1.07× | 426–465 ms | 519–555 ms | ~46 700 | **0 %** | 200 |
| **twin** (`POST /hooks/{id}`) | 612.7 | 589.8 | **601** | 1.04× | 295–307 ms | 840–896 ms | ~41 800 | **0 %** | 200 |
| **control** (`GET /health/live`, xyk's binary, no database) | 424.4 | 444.3 | **434** | 1.05× | 442–455 ms | 916 ms – 1.58 s | ~46 800 | **0 %** | 200 |

Round 1 was 463.3 / 588.4 / 436.3 — within the spread of the rounds that count, so the warm-up rule
cost nothing here and is kept because it costs nothing.

**The accounting closes.** 2 000 × 30 = 60 000 offered; xyk delivered ≈13 100 and dropped 46 700,
which sums to 59 800. The generator is not losing anything quietly.

**The generator is not the ceiling.** Unpooled, this same pair offers **8 000 rps at p50 0.5 ms with
zero dropped iterations** and first strains at 16 000
([the pilot's correction](../measurements-2026-09-15/throughput-pilot.md)).

## The verdict, which is the same for both columns

**Nobody meets the criterion.** 2 000 rps offered over 200 connections; the best column delivers 601.
It is not a Kotlin/Native verdict — the Go twin misses the line by the same kind of margin.

**And nothing failed.** Zero failed requests in every arm of every round, peak concurrency exactly
200, no slow state in the sense of errors or timeouts. What happened is simpler: at 200 connections,
throughput is `200 ÷ latency`, and the latencies here are 300–460 ms rather than the 100 ms that
2 000 through 200 requires. The criterion fails **on latency**, and `dropped_iterations` is the
arithmetic saying so.

## The finding worth more than the verdict

**The control is the same as the ingest arm: 434 against 437.** A route that parses nothing, verifies
nothing and touches no database delivers what the route that does all three delivers. So on this host
**the database is not the cost** — not the signature check, not the SQLite write, not the WAL. The
cost is in the HTTP path itself.

**And the twin's ingest beats xyk's health route by 38 %** — 601 against 434 — while doing strictly
more work: the same signature, the same insert, into SQLite through a pure-Go driver.

That is [research §1.18](../research-architecture.md)'s four-visible-core pathology, measured at last
with the generator on another machine, every arm interleaved, and a spread of 1.05× instead of 2.3×.
The earlier readings of that effect were taken with a contaminated stand and were right anyway.

## What this does not say

**It does not say what xyk does on a machine that is not four cores.** §1.18 measured 2 112 rps at
twelve visible cores against 355–816 at four, on a route like this one. The criterion named no host,
and this one is the small end of the range the product is aimed at — a container per project — so the
number is the relevant one and is not the only one.

**It does not compare products.** The twin has no delivery, no journal, no registry and no
migrations beyond the two tables it needs. It is the floor of the same host, the same generator and
the same route shape, which is what makes the Kotlin column readable.

## A defect found while restarting the arms, and not by the measurement

Between campaigns the Kotlin binary died on start **six times out of twelve**, strictly alternating —
every time a previous instance was still holding the port. The cause is `EADDRINUSE`, and the way it
arrives is the defect: it is wrapped in `JobCancellationException: LazyStandaloneCoroutine is
cancelling`, it arrives **after** Ktor has logged `Application started in 0.003 seconds`, and the
process aborts with a core dump. Raised as [B-27](../../backlog/B-27-bind-failure-is-unreadable.md).
None of the numbers above were taken on a process in that state — the nine rounds ran on one healthy
start each.
