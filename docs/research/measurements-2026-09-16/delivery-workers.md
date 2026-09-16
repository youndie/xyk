# B-14 — the curl dispatcher is not the ceiling, and the hypothesis was wrong

**Date:** 2026-09-16. **Host:** the Linux box, 20 cores, docker. **Subject:** the shipping image with
`ktor-client-curl`. **Subscriber:** a Python sink with a fixed delay, counting rather than logging.
**Method:** 600 events posted first, then the clock starts — what is measured is how fast the workers
drain a queue that already exists. Three interleaved rounds per arm.

## The two arms

| workers | slow subscriber (100 ms) | instant subscriber |
|---:|---:|---:|
| 1 | 6.3 · 6.3 · 6.3 /s | 17.6 · 17.4 · 17.6 /s |
| 2 | 12.8 · 12.8 · 12.8 /s | 38.2 · 38.2 · 37.4 /s |
| 4 | 27.2 · 27.2 · 27.5 /s | 91.5 · 103.8 · 94.8 /s |
| 8 | 46.8 · 46.7 · 46.5 /s | 176.0 · 253.2 · 284.4 /s |

Resident memory: **28 976 – 32 960 kB in every arm of every round**, with no trend against the worker
count.

The slow arm's reproducibility is the strongest thing here: three rounds at one worker took 95.24,
95.09 and 94.85 seconds. That is the arm the hypotheses disagree about, and it is not noisy.

## The hypothesis, and its refutation

[B-14](../../backlog/B-14-delivery-worker-count.md) recorded, before the run: *the ceiling belongs to
the `curl-dispatcher` thread, and two workers reach it.*

**It does not, and they do not.** Throughput doubles from 1 to 2 (2.03×), doubles again to 4 (2.13×)
and rises 1.72× more at 8. `ktor-client-curl` runs its I/O on one `newSingleThreadContext`, and that
thread multiplexes — which is what libcurl's multi interface is for. A single-threaded event loop is
not a single request at a time, and the design read that predicted otherwise confused the two.

**The control behaved as both hypotheses predicted it would not.** It was chosen expecting a flat
curve against an instant subscriber; instead it scales too, and harder. That does not void the run —
the slow arm is where the hypotheses differed, and it answered — but it does mean the control tested
less than it was designed to. What it did establish is that nothing in the *service* serialises
deliveries either.

## What sets the number, since the dispatcher does not

At `N` workers and 100 ms per delivery the ceiling would be `N × 10/s`; measured efficiency is
**58–68 %** across every arm, flat in the worker count. The missing third is chronik's own shape: a
worker claims a batch, walks it sequentially, then sleeps one poll interval. That cost is per worker
and does not grow, which is why the curve stays straight.

**More workers cost no memory here** — the worker count is coroutines on a shared dispatcher, not OS
threads, so the usual "resident memory follows the thread count" does not apply to this knob.

**Four is taken as the default**, from this table: it is four times the delivery rate of one at no
measured memory cost, and it leaves headroom in a measurement that was still linear at eight rather
than sitting on the last point measured. Eight is available and is not the default because nothing
here bounds what a subscriber will tolerate, and the number that decides that is theirs.

## What this does not cover

**It was measured on twenty cores, not four.** The service's HTTP path is pathological at four
visible cores ([research §1.18](../research-architecture.md)); whether outbound delivery is too has
not been measured, and the bench host is where that would be answered.

**The database pool is 2 and was not varied.** Eight workers sharing two connections still scaled, so
the pool is not the ceiling at these rates — but "not yet" is what that says, and the rate at which
it becomes one is unmeasured.

**The shipping build's memory has not been measured with delivery on.** B-21's arms were the static
build with no engine, therefore no workers at all. That is a gap in the memory criterion rather than
in this item, and it is [B-29](../../backlog/B-29-memory-with-delivery-on.md).
