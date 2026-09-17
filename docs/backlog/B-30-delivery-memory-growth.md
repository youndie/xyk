---
id: B-30
title: "The delivery half grows without bound, and the memory criterion cannot see it"
status: dropped
priority: P0
size: L
stage: stage-3-verdict
epic: feature-delivery
blocked_by: []
---

# B-30 — The delivery half grows without bound, and the memory criterion cannot see it

Found by the first soak ever run against the **shipping** image with the outbound half linked —
[delivery-memory-growth.md](../research/measurements-2026-09-16/delivery-memory-growth.md).

At 60 rps of ingest with a subscriber that answers immediately, resident memory rises about
**440 kB/s — roughly 6 kB per delivery** — and does not stop. 64 MiB buys ninety seconds, 128 MiB
buys 256, 256 MiB was still climbing at 174 MB when the run ended. Every death is `OOMKilled`.

It is not the files: over the same 266 seconds the journal was flat, the database grew 13 MB, and
memory grew 110 MB. It is not threads: 36 throughout. The control — the same host, rate and readers
with delivery **off** — grew 14 MB against a 13 MB database, which is what page cache looks like.

**This blocks the published image.** `ghcr.io/youndie/xyk:main` and the chart in `charts/xyk` both
ship the configuration measured here, and the chart's default limit is the one that dies in ninety
seconds.

- **The criterion it invalidates is a margin, not the answer.**
  [B-21](B-21-criterion-memory.md) and [B-29](B-29-memory-with-delivery-on.md) both answer *yes* on
  rounds of `DURATION=30s`. Thirty seconds is less than the time this takes to kill the process, so
  those rounds are honest about what they measured and silent about what they could not. The
  criterion does not become *no*; it becomes **undetermined past thirty seconds**, and saying so is
  this item's first job.
- **Decision: the mechanism is not guessed at here.** One `HttpClient(Curl)` is built for the life
  of the process and each response is read to a bounded prefix, so the obvious accumulations are not
  in `OutboundEngine.native.kt`. Whether it is the curl engine, the Kotlin/Native runtime declining
  to return pages, or the attempt path is unmeasured, and a document that named one would be
  inventing it.
- **Rejected: raising the chart's limit.** Three limits were measured and the slope is the same in
  all three; a bigger number moves the death later and calls it a fix.

- AC: the discriminating run first — the shipping image at a limit high enough to run long,
  sampled until the curve either plateaus or reaches the limit. "Grows without bound" is currently
  an extrapolation from five minutes and deserves to be one measurement rather than three.
- AC: the mechanism named with the evidence that names it, and separated from the Kotlin/Native
  runtime's own behaviour. **Half done, 2026-09-16:** with `GC.maxHeapBytes` pinned at 32 MiB and
  confirmed applied, the process still climbs to 102 MB — so the growing part is **not the managed
  heap**, and "the GC could not see the limit" is excluded. What remains to separate is native
  allocation on the delivery path: the curl engine, the Rust half of sqlx4k, thread stacks. The arm
  for that is an outbound that does no HTTP. **Built and run, 2026-09-16** (`-Pxyk.outbound=noop`):
  with the request removed and everything else kept, growth above the database file falls from
  61–97 MB in five minutes to 0.7–7 MB. The term is the request. What it does not separate is the
  response handling, which goes with it — though that allocates in a heap that is pinned while the
  growth is outside it. Two runs an arm, same configuration.
- AC: **met** — the mechanism is named and it is not ours. `bench/curl-leak`, a standalone binary
  with one client and one loop, grows ~2.0 kB per request on `ktor-client-curl` and ~0.3 kB
  flattening on CIO, same loop and same ceiling. Reading the response body makes no difference.
  CIO is not a way out: it speaks plain HTTP on native and subscribers are `https` (research §1.6),
  so what is left is upstream or a mitigation that bounds the growth.
- AC: whatever is found, `README.md` and [B-21](B-21-criterion-memory.md) stop claiming a memory
  answer that no run longer than thirty seconds supports.
- AC: **met** — both missing memory recipes are applied (`MALLOC_ARENA_MAX=2` in both images, the
  ceiling as `XYK_HEAP_BYTES`) and re-measured rather than assumed. They buy time — 87 s → 123 s →
  223 s at the same limit — and do not remove the growth.
- Anchors: `bench/soak.sh`, `server/src/nativeMain/kotlin/io/github/youndie/xyk/HeapCeiling.native.kt`,
  `docker/scratch.Dockerfile`,
  `server/src/variants/with-curl/kotlin/io/github/youndie/xyk/delivery/OutboundEngine.native.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/DeliverySink.kt`

## Found alongside, and not the same thing

**An unreachable subscriber killed the subject at 64 MiB and again at 96 MiB, inside thirty
seconds** — faster than the growth above. That arm was an accident: the first wiring of the soak
pointed the subject at `127.0.0.1`, which inside a bridge container is the container itself. It is
the most ordinary condition a webhook gateway meets — the subscriber is down — and it deserves its
own measurement rather than a line in someone else's item.

## Closed without a fix, by the owner, 2026-09-16

**No issue upstream and no mitigation.** The diagnosis is the deliverable and it is complete; the
remedy is declined, so this is a **known problem** rather than a task, and it is written where an
operator meets it — [`services/xyk-server.md` §8](../services/xyk-server.md), the README's criteria
table, and a comment on the chart's memory limit — rather than only here.

`dropped` rather than `done`, because `[x]` beside a title that says *grows without bound* would
read as fixed. Nothing is fixed. What is settled is whose it is and what it costs:

* about **2 kB per request** in `ktor-client-curl`, reproduced outside this service in
  [`bench/curl-leak`](../../bench/curl-leak) with one client and one loop;
* so a container's memory limit is **a time budget, not a headroom**: 64 MiB is about ninety
  seconds at 60 rps, 128 MiB about four minutes, 256 MiB about eight;
* and the two memory recipes applied on the way — `MALLOC_ARENA_MAX=2` and the heap ceiling —
  roughly halve the slope and are worth keeping on their own account. They are in the images and
  they stay.

**Part of it is ours after all, and the earlier wording was too strong.** Under a profiler the
heap peaked at 27.23 MB against the 32 MiB ceiling it was given, and varying that ceiling moves the
result: 37 280 kB of RSS at 32 MiB against 30 080 at 4 and 8. So roughly a quarter of the growth is
managed heap the GC keeps because the ceiling permits it — tunable, and ours. The other three
quarters survive a 4 MiB ceiling, an explicit GC, a closed client and a changed allocator, and do
not appear on CIO at all.

**Four consumer-side explanations were tested afterwards and refuted** — an explicit GC, never
reading the response, recycling the client, and the shipping allocator — because the attribution had
been made by subtraction and subtraction admits more than one story. None of them changed the slope;
the allocator arm made it steeper. The CIO control on the shipping allocator sat at 19 200 kB
without moving at all. The engine's handle lifecycle also reads correct on inspection. That does not
make the conclusion certain — reading code is not running it — but it is no longer one experiment
wide.

**Squeezed further, 2026-09-17.** It does not plateau — a straight line to 197 MB over 50 000
requests with the heap capped at 8 MiB, stopped there rather than bending. Under a profiler every leaked byte is a Kotlin allocation
(`CustomAllocator::CreateObject` and `CreateArray`, 61 MB of 62) with nothing under a libcurl frame,
and `-Xallocator=std` grows identically, so it is not the allocator backend either. One fork is left
open and written down rather than guessed: whether the objects are reachable and the heap ceiling is
not enforced as assumed, or they are collected and the memory is never returned. Deciding it needs a
reference-path dump for the Kotlin/Native heap.

**Widened, 2026-09-17, because one version on one platform is not a finding.** ktor **3.6.0** still
grows (and retains ~5.4 MB less than 3.5.2 over the same 2 000 requests in the same session);
**HTTPS** costs 2.6 MB more than plain HTTP, which is the half that matters since subscribers are
`https`; and **macosArm64** reproduces it — ~3.6 kB per request on curl against ~0.3 on CIO, a
second OS and a second architecture with the control in the same session.

**And the rate decides the price.** The same 2 000 requests spread over ten minutes instead of
thirty seconds cost about a fifth as much, and every arm gives some back within sixty seconds of
going quiet and then nothing more. So it is not a fixed cost per delivery: it is a rate the runtime
does not keep up with. At three deliveries a second it does; at forty it does not — and B-31's arms
are at the wrong end of that scale.

**What would reopen this.** A ktor release that changes the curl engine's allocation, or a second
HTTPS engine on Kotlin/Native. `bench/curl-leak` is the check either way: it takes a minute to
build and answers in five.
