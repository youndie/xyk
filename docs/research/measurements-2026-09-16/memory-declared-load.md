# B-21 at the criterion's own load: what ships does not survive, and the arm that does was the one dismissed

**Date:** 2026-09-16. **Subject:** the subject host — a 4-core, 7 GB cloud VM, Ubuntu, glibc 2.43, cgroup v2 — the binary
under a transient `systemd-run` unit with `MemoryMax=64M`, `MemorySwapMax=0`. **Generator:**
the generator host, k6 v1.4.1, over the private network. **Load:** the criterion's own — `2 000 rps offered
over 200 connections`, 30 s a round, **ten rounds per arm, interleaved**. **Control:** the shipping
arm at 6 MiB, which died twice out of two before the table was allowed to exist.

**Two arms**, both statically linked, no outbound engine, md5-distinct so the `binaryOption` is known
to have reached the compiler: `fixedBlockPageSize=16` (what ships) and `-Xallocator=std`.

## The result

| arm | survived | cgroup peak, kB | threads | delivered rps (of 2 000 offered) |
|---|---|---:|---:|---:|
| `fixed16` — **what ships** | **1 / 10** | 65 868 (the one survivor) | 57 | — |
| `-Xallocator=std` | **10 / 10** | 54 864 – 65 656 | 56 – 108 | 250 – 442, **0 % failed** |

**The shipping allocator fails the criterion nine times out of ten.** Its single survivor peaked at
**65 868 kB — above the 65 536 kB limit** — which is the kernel reclaiming mapped pages to fit rather
than a process with room to spare.

**`-Xallocator=std` meets it 10/10**, and serves while it does: 0 % failed requests in every round,
250–442 rps delivered, which is the same band B-20 measured for the shipping arm (437).

## One number here must not be read as throughput

The killed rounds report **1 933–1 964 rps at 99.9 % failed**. That is not the service going fast; it
is the generator meeting a closed port. A refused connection is cheap, so a dead subject produces a
*higher* request rate than a live one — 58 006 requests against `std`'s 12 740. The rate column of a
failed arm measures the failure, and it is quoted here only to say so.

It also confirms the kills were under load rather than at rest: the generator did send tens of
thousands of requests, and the service took some of them before dying.

## Why this inverts the earlier reading

The previous run of this criterion ([memory-64mib.md](../measurements-2026-09-15/memory-64mib.md))
had `fixed16` surviving 10/10 and `std` merely lowest. It ran at **200 rps over 50 connections** on a
twenty-core box, because there was no second machine.

**The variable that matters is the concurrency, not the rate.** B-20 measured that this service
absorbs about 437 rps whatever is offered, so the arrival rate is bounded by its own latency; what
the criterion's scenario adds is 200 requests in flight instead of 50. On this platform resident
memory follows the **thread count**, the thread count follows the concurrency, and the two allocators
answer that differently: `std` ran 56–108 threads inside the limit, `fixed16` did not fit.

## What this does and does not decide

**It decides the criterion:** 64 MiB, ten out of ten, at the declared load — **met by
`-Xallocator=std`, not met by what ships today**.

**It does not by itself decide what to ship.** `-Xallocator=std` measured *worse* elsewhere in this
portfolio on a service with SQLite on the request path, which is why it was not the default here; and
B-20's three columns were taken on `fixed16`, so the throughput column for `std` is one run rather
than an interleaved campaign. What this measurement supplies is 250–442 rps at 0 % failures across
ten rounds — the same band — which is evidence and not yet the column.

**Open, and it is the next thing:** B-20's table re-run with `std` in the Kotlin column, so the swap
is made on both criteria rather than on one.
