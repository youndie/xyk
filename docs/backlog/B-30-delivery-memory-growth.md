---
id: B-30
title: "The delivery half grows without bound, and the memory criterion cannot see it"
status: open
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
  for that is an outbound that does no HTTP.
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
