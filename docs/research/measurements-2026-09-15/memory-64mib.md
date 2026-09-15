# B-21 — VOID: the generator never started, and the table below is of an idle process

**Date:** 2026-09-15. Retracted the same day, before anything here was carried into a backlog item or
a README.

**Every one of the forty generator logs in `/tmp/xyk-memory-212808/*.log` contains exactly one line:**

```
time="2026-09-15T19:29:00Z" level=error msg="stat /bench/ingest.js: permission denied"
```

40 logs, 40 failures, no exceptions — counted rather than sampled. The k6 container exits in
milliseconds, the harness does not look at its exit status, and the round then measures a server
that received **no requests at all**. Ten rounds per arm, four arms, all of them idle.

## Why the mount failed

`bench/memory.sh` bind-mounted the working tree: `-v "$PWD/bench":/bench`. On this Linux box the
working tree is a **mutagen replica**, and mutagen writes files `0600` owned by the account that
syncs them. The `grafana/k6` image runs as a **non-root** user. So the container can see the path and
cannot read the file — and `permission denied` on a *scenario file* is not a failure of any request,
so nothing downstream goes red.

The fix is to stage the scenario outside the replica (`mktemp -d`, `chmod 755`, `chmod 644` the file)
and to mount that. `bench/memory.sh`, `bench/run.sh` and `bench/soak.sh` all did it the broken way;
all three now stage.

## What the retracted table said, and what each line actually measured

| arm | claimed | what it was |
|---|---|---|
| `default` | 10/10, peak 25 972–35 828 kB | an idle process under a 64 MiB limit |
| `fixed16` | 10/10, peak 7 744–14 344 kB | an idle process under a 64 MiB limit |
| `fixed16` + `MALLOC_ARENA_MAX=2` | 10/10, peak 7 516–8 116 kB | an idle process under a 64 MiB limit |
| `-Xallocator=std` | 10/10, peak 5 608–13 056 kB | an idle process under a 64 MiB limit |

**The allocator comparison does not survive either.** The arms differ from one another in the table,
so the numbers are not noise — they are a real measurement of *start-up and idle* footprint per
allocator, which is a different and much weaker claim than the one the document made. It is not
re-quoted here, because a number whose stated conditions were false should be re-taken, not re-worded.

**One thing in the run was real:** the positive control. `fixed16` at 6 MiB died before serving, twice
— and it needs no load to die, so that part stands. Which is the sting: the harness's own control
passed, correctly, while the thing it was controlling for had already failed in a way the control was
not built to see. A control that proves the stand can detect a death proves nothing about whether the
stand applied any load.

## The tell was in the document, explained away

The retracted version contained this sentence:

> every arm sat at **18 threads** throughout

and attributed it to the stand being unable to generate concurrency. Eighteen threads under a load
that was supposed to be 200 requests per second over 50 connections is not a weak stand — it is *no
requests*, and the number was sitting in the results table the whole time. The reading was available
and the wrong explanation was the comfortable one.

The lesson generalises past this harness: **a load generator that fails to start looks exactly like a
subject that is coping.** Flat memory, flat threads, every probe `200`. Everything a green run looks
like. Guards added in the same pass:

* `bench/soak.sh` asks the subject, thirty seconds in, whether any event has been stored, and aborts
  if none has;
* `bench/memory.sh` requires the generator's own summary — a round whose k6 produced no result is
  recorded as `no-load` and voids the run rather than scoring as a survival.

## Status

B-21 is **not measured**. The harness is fixed, the arms are unchanged, and the run has to happen
again. See [B-21](../../backlog/B-21-criterion-memory.md).

---

# The re-run, with the generator actually running (2026-09-15)

**Same host, same four arms, same ten interleaved rounds, same 6 MiB control — the only change is
that the scenario is staged where k6 can read it.** Load: k6 0.54.0, `constant-arrival-rate`, 200 rps
over 50 connections, 15 s, ingest through `POST /hooks/{id}` with a genuine GitHub signature. Every
round's generator output was checked for `http_reqs` before the round was scored.

**Control: 2 of 2 killed at 6 MiB.**

| arm | survived | cgroup peak, kB (min–max) | threads (max) |
|---|---|---:|---:|
| `default` (256 KiB pages) | **0/10** | killed every round | — |
| `fixed16` (what ships) | 10/10 | 45 112 – **65 536** | 146 |
| `fixed16` + `MALLOC_ARENA_MAX=2` | 10/10 | 42 120 – 56 188 | 117 |
| `-Xallocator=std` | 10/10 | **25 428 – 34 056** | 101 |

## What changed against the retracted table, and why it is not a small correction

| | retracted (no load) | measured (200 rps) |
|---|---|---|
| `default` | 10/10, 25 972 – 35 828 kB | **0/10 — killed every round** |
| `fixed16` | 10/10, 7 744 – 14 344 kB | 10/10, 45 112 – 65 536 kB |
| `fixed16-arena2` | 10/10, **7 516 – 8 116 kB** | 10/10, 42 120 – 56 188 kB |
| `std` | 10/10, 5 608 – 13 056 kB | 10/10, **25 428 – 34 056 kB** |
| threads | 16–18 | **49–146** |

**The ranking is inverted.** The retracted document concluded that `fixedBlockPageSize=16` "earns its
place: three to four times less memory than the default", and that `-Xallocator=std` had "no reason
to take". Under load, `default` does not survive at all and `std` is the only arm with real headroom —
half the shipping arm's peak and the steadiest of the four (spread 1.34× against 1.45×).

**The mechanism is what moved, exactly as predicted.** On this platform resident memory follows the
thread count ([research §1.8](../research-architecture.md)); the idle rounds sat at 16–18 threads and
these reach **146**. Everything about the earlier table was an artefact of that one difference.

## The reading that matters more than the ranking

**`fixed16` survives 10/10 with no margin.** Round 8 peaked at **65 536 kB — exactly the 64 MiB
limit**, which is the kernel reclaiming pages to fit rather than a process that had room to spare.
Ten survivals at the edge of the limit is not the same claim as ten survivals, and a tenth of the
declared load is what produced it.

**This is still not the declared criterion.** That says 2 000 rps over 200 connections; this stand
runs 200 rps over 50 ([B-20](../../backlog/B-20-criterion-throughput.md)). The difference is no longer
"the mechanism was never engaged" — it plainly is — but the margin above is thin enough that a ten-fold
load is the question, not a formality.

## What this supports

1. **The `default` allocator is out.** Not "worse": killed ten times out of ten at the declared limit.
2. **`-Xallocator=std` is now the arm to beat**, on this evidence and at this load. It was dismissed
   in the retracted document on numbers that described an idle process.
3. **The criterion is not yet answered either way** — `fixed16` at 10/10 touching the limit at a tenth
   of the load is a result that points at failure without demonstrating it.
