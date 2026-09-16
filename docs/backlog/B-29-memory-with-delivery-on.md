---
id: B-29
title: "The memory criterion has not been measured on the configuration that ships"
status: open
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
  measured on a twenty-core box with no memory limit and no ingest load at the same time. Three
  differences, each of which could be the one that matters.
- Not covered: the twin's memory, which has no delivery half to compare against.

- AC: ten rounds at 64 MiB with the shipping image, delivery on, four workers, a subscriber at a
  named latency, and a positive control that dies.
- AC: if the shipping configuration misses the limit where the ingest-only build met it, the
  allocator decision of [B-28](B-28-allocator-decision.md) is re-opened rather than quietly kept.
- Anchors: `bench/memory-declared.sh`, `docker/native.Dockerfile`
