---
id: B-20
title: "Criterion: 2 000 rps over 200 connections with no slow state, in three columns"
status: done
priority: P0
size: L
stage: stage-3-verdict
epic: feature-ingest
blocked_by: [B-06, B-16]
---

# B-20 — Criterion: 2 000 rps over 200 connections, three columns

**Declared before the code, on 2026-09-15:** the ingest path sustains 2 000 requests per second
across 200 connections without entering a slow state, and the same scenario is reported for the Go
twin — three columns, one table.

The third column is the arm that makes the other two readable: xyk, the twin, and the **control** —
the same generator against a route that answers a constant without touching the database. Without it,
a number that turns out to be the generator's ceiling reads as the service's.

- **Decision: the generator is open-model (`constant-arrival-rate`) with honest
  `dropped_iterations`.** A closed loop physically cannot show the failure this criterion is about: a
  sagging server receives fewer requests, so "slower → more in flight" never happens and the run
  looks healthy right up to the point it is not.
- **Decision: the generator does not run on the host under test.** On the subject's machine it does
  not merely add noise; it competes for exactly the cores being measured.
- **Decision: "no slow state" is defined before the run, not after** — a latency ceiling at a named
  percentile, `dropped_iterations` at zero, and resident memory and `walBytes` flat over the second
  half. A definition written after seeing the numbers describes the numbers.
- **Decision: the first run after a restart is discarded**, explicitly, as warm-up.
- **Decision: arms are interleaved and repeated.** One run per arm is not a measurement; on a machine
  shared with anything else, throughput has been seen to swing by a factor of five between rounds.
- Not covered: delivery throughput, which is [B-14](B-14-delivery-worker-count.md).

- AC: a table in `docs/research/measurements-<date>/` with three columns, the host, the run count,
  the generator's own version, and the SQLite driver of the twin named. **The table exists and says
  VOID** — [throughput-pilot.md](../research/measurements-2026-09-15/throughput-pilot.md).
- AC: the control arm is present and faster than both — if it is not, the harness is what was
  measured and the run is void. **The rule fired on the first real run, and was itself wrong**: the
  control shares a binary with the Kotlin arm, so it bounds that arm and says nothing about the Go
  one. Corrected in `bench/summarise.py`, together with a new check for the ceiling every arm shares.

## Where it stands (2026-09-15)

**The two-host setup exists and works.** Subject on `bench-a` — the two **static binaries run
directly**, no docker on that box and none needed, which is also the first confirmation of B-05's
`-static` claim somewhere it could have failed (built against glibc 2.39, running on 2.43).
Generator on `bench-b`, k6 v1.4.1, about a millisecond away.

**The first run is void and produced three findings instead of a number:**

1. **The twin was shedding load, and the parity gate could not see it.** 48.7% of its requests failed
   with `500` at 2 000 rps offered — `modernc.org/sqlite` answers `SQLITE_BUSY` the instant two
   connections want the writer lock, and nothing waited. It was *fast because it gave up*. Fixed
   (`busy_timeout(5000)`), and the gate now ends with a concurrent burst, because **parity under load
   is a different claim from parity** and one request at a time could not tell the difference.
2. **Every arm landed between 523 and 630 rps** — including the control, which touches no database.
   Three services do not coincidentally share a number; a harness does.
3. **And it is not the generator's CPU:** k6 sat at 72% of one core, load 0.51, while that happened.
   The same control arm run alone straight afterwards delivered p50 8.6 ms against 388 ms in the
   sequence.

**What the generator turned out to be: innocent.** Five controls later
([cores-and-throughput.md](../research/measurements-2026-09-15/cores-and-throughput.md)), the ceiling
is the subject's — and it depends on something nobody had thought to record: **how many cores the
runtime can see.** Four visible cores give 42–378 rps where four cores of quota with twenty visible
give 3 871–3 954, and two visible give 1 353–1 670. Full table and consequences in
[research §1.18](../research/research-architecture.md).

**So this criterion needs a condition it was declared without.** "2 000 rps over 200 connections" is
answerable only once the host is described by what the runtime sees, because the same binary with the
same CPU budget differs by up to ninety times between two ways of giving it. That is a change to a
criterion declared before the code, so it is recorded rather than quietly applied — and the
criterion's own table will carry the core count next to every number.

**A correction the same day, and it is mine.** A dozen of those diagnostics let the generator run on
the very cores the subject was pinned to — the mistake `bench/run.sh` exists to refuse, gone around
because each run "was only a quick check". With the two pinned apart the catastrophic tail (19, 42,
51 rps) disappears. What survives: **564–1 019 rps at four visible cores**, 2 112–2 542 at twelve,
against a Go twin that is flat at ≈50 000 across 1, 4 and 12. The corrected tables are in the
measurement file, at the point of divergence rather than in place of what they correct.

**The rule now lives in the harness rather than in its header**: `bench/run.sh` pins the subject, and
refuses a same-host run unless `GENERATOR_CPUS` names cores disjoint from it. A rule that only a
careful reader applies is a rule for careful readers.

**The pinned rounds are done and the spread is real** (four interleaved rounds, generator on disjoint
cores): Kotlin 355–816 rps at four cores — 2.3× between identical runs — 1 812–2 268 at twelve, Go
55 953–56 972 at four with a spread of 1.02×. **102× between the arms on four cores.**

**What B-20 waits on now, and it is no longer technical.** The criterion requires the generator to be
off the machine under test. The two hosts that made that possible are out of scope from 2026-09-15 at
the owner's request, and this workstation is the only machine left — so the harness's own refusal
(`bench/run.sh` without `--same-host`) is what stands between here and a number that would not mean
what it claims. Everything else is ready: the arms agree, the gate runs, the pinning is enforced by
the harness rather than by memory.
- Anchors: `bench/run.sh`, `bench/scenario.js`, `docs/research/`

## The blocker named by the pilot is cleared, 2026-09-16

The pilot listed three things that had to happen before a number could be quoted. The first is done,
and it was the one that mattered: **the cap on in-flight concurrency was the harness's own VU pool**,
set to the connection count on a misreading of what a k6 virtual user is
([research §1.23](../research/research-architecture.md),
[throughput-pilot.md](../research/measurements-2026-09-15/throughput-pilot.md)).

Measured on the real pair, bench-b → bench-a: the generator offers **8 000 rps with zero dropped
iterations at p50 0.5 ms**, and first strains at 16 000. Four times this criterion, so nothing below
it is the generator's.

**And the criterion now has a checkable form.** `2 000 rps at 200 connections` under an open model is
`rate × latency ≤ 200` — a mean under 100 ms. That threshold is declared in the scenario, and
`bench/run.sh` reads the peak VU count out of k6's summary and records a violation when the service
forced more concurrency than the line allows. The half of the criterion that nothing measured is now
the half that is hardest to fudge.

Still to do before the three columns are run: the settle between arms and the idle check the pilot
also asked for, then interleaved rounds with the first discarded.

## The three columns exist, 2026-09-16 — [throughput-three-columns.md](../research/measurements-2026-09-16/throughput-three-columns.md)

Two machines, generator off the subject, three rounds interleaved, first discarded, 2 000 rps offered
over 200 connections.

| arm | mean rps | spread | p50 | failed | peak concurrency |
|---|---:|---:|---|---:|---:|
| xyk ingest | **437** | 1.07× | 426–465 ms | 0 % | 200 |
| twin ingest | **601** | 1.04× | 295–307 ms | 0 % | 200 |
| control (no database) | **434** | 1.05× | 442–455 ms | 0 % | 200 |

**The criterion is not met, by either column.** Best delivered is 601 against 2 000 offered. Nothing
failed and nothing timed out: at 200 connections throughput is `200 ÷ latency`, and these latencies
are 300–460 ms where 2 000 through 200 needs 100 ms. It fails on latency, and `dropped_iterations` is
the arithmetic saying so.

**The control equals the ingest arm — 434 against 437 — so the database is not the cost.** The
signature check, the SQLite write and the WAL together add nothing measurable to a route that does
none of them. The cost is the HTTP path, which is
[research §1.18](../research/research-architecture.md)'s four-visible-core pathology, measured this
time with the generator on another machine and a spread of 1.05× rather than 2.3×.

- AC: **met** — the table exists, with three columns, both hosts named, the round count, k6's version
  and the twin's driver.
- AC: **met** — the control is present. It is not faster than the twin and that is the correct
  outcome rather than a void run: it shares a binary with the Kotlin arm and bounds *that* arm, which
  is exactly what it did.
- AC: **the criterion itself is answered: no.** 437 rps against 2 000, on four visible cores, with the
  reason named.

Left open deliberately: the same table on a host with more visible cores. §1.18 measured 2 112 rps at
twelve against 355–816 at four on a route of this shape, so the number here is the small end of the
range the product is aimed at rather than the whole answer.

**Closed 2026-09-16.** The criterion is answered — *no*, by both columns, with the reason named
(latency, not throughput: at 200 connections the rate is `200 ÷ latency` and these latencies are
300–460 ms where 2 000 through 200 needs 100). The table exists, the harness exists, and the arm that
ships changed since ([B-28](B-28-allocator-decision.md)) — the `std` column is in
[allocator-decision.md](../research/measurements-2026-09-16/allocator-decision.md) rather than
re-stated here.
