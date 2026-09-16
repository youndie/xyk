---
id: B-29
title: "The memory criterion has not been measured on the configuration that ships"
status: done
priority: P0
size: S
stage: stage-3-verdict
epic: feature-ingest
blocked_by: [B-21, B-14]
---

# B-29 — The memory criterion was measured on a build that cannot deliver

Found while reading [B-14](B-14-delivery-worker-count.md)'s results next to
[B-21](B-21-criterion-memory.md)'s. Both are sound and they do not describe the same binary.

B-21 ran the **static, no-outbound-engine** build. That build's `outboundPost()` returns `null`, so
`deliveryWorkers()` is never constructed and no worker runs. Its 10/10 at 64 MiB is therefore a
measurement of **ingest plus journal**, with the entire delivery half absent — while the image that
ships links `ktor-client-curl` and runs four workers.

The choice was deliberate and is defended in that document: the twin has no delivery either, so
including it would have put background work in one column and not the other. That argument is right
for the *throughput* table, where the twin is the comparison. **The memory criterion has no twin in
it** — it is a claim about one container under one limit — so nothing was bought by leaving delivery
out, and the claim now describes a configuration nobody deploys.

- **Decision: re-run B-21's arms on the image, with the engine linked and workers running.** Same
  limit, same declared load on ingest, plus a subscriber so the workers have something to do.
- **Decision: the subscriber is slow rather than instant.** A delivery that returns immediately keeps
  no state; the interesting case is deliveries in flight, which is what a real subscriber produces
  and what costs memory.
- **Rejected: assuming the delta is small because B-14 saw flat RSS across worker counts.** That was
  measured on a twenty-core machine with no memory limit and no ingest load at the same time. Three
  differences, each of which could be the one that matters.
- Not covered: the twin's memory, which has no delivery half to compare against.

- AC: ten rounds at 64 MiB with the shipping image, delivery on, four workers, a subscriber at a
  named latency, and a positive control that dies.
- AC: if the shipping configuration misses the limit where the ingest-only build met it, the
  allocator decision of [B-28](B-28-allocator-decision.md) is re-opened rather than quietly kept.
- Anchors: `bench/memory-declared.sh`, `docker/native.Dockerfile`

## Measured, 2026-09-16 — [memory-shipping-config.md](../research/measurements-2026-09-16/memory-shipping-config.md)

**10 / 10 survived**, with the engine linked, four workers running and **3 914 deliveries actually
made** to a subscriber answering in 100 ms. Control killed twice of two at 6 MiB.

| | ingest-only | shipping |
|---|---|---|
| survived | 10/10 | **10/10** |
| peak, kB | 54 864 – 65 656 | **60 928 – 66 108** |
| threads | 56 – 108 | **58 – 123** |

**The criterion holds on the configuration that deploys**, so B-28's allocator decision stands — that
item's acceptance line made re-opening conditional on this run missing, and it does not.

**It holds without margin.** Four rounds of ten peaked at or above the 65 536 kB limit; a peak at the
limit is the kernel reclaiming to fit. "Meets 64 MiB by reclaim" is the honest phrasing and it is not
the same as "uses 60 of its 64".

**The delivery half was asserted rather than assumed** — the harness asks the subscriber how many
arrived and voids a round that received none. That guard voided the positive control on its first
run, because a subject killed at 6 MiB delivers nothing by definition. A completeness guard without a
scope condemns the round whose job is to fail; it now asks only of rounds that survived.

- AC: **met** — ten rounds, shipping image, four workers, a subscriber at a named latency, control
  that dies.
- AC: **not triggered** — the shipping configuration did not miss where the ingest-only build met, so
  B-28 is not re-opened.
