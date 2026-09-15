---
id: B-14
title: "How many delivery workers, and is the curl dispatcher the real ceiling?"
status: question
priority: P2
size: S
stage: stage-3-verdict
epic: feature-delivery
blocked_by: [B-11]
---

# B-14 — How many delivery workers, and is the curl dispatcher the real ceiling?

chronik's worker delivers a batch sequentially, so throughput per worker is roughly
`batchSize / (batchSize × attempt time)` — one delivery at a time. Parallelism means more workers.
But the curl engine runs **all** its I/O on one `newSingleThreadContext("curl-dispatcher")`
([research §1.6](../research/research-architecture.md)), so above some number, more workers buy
nothing.

*Hypothesis:* the ceiling belongs to the dispatcher, and two workers reach it. Recorded here so that
the measurement can refute it rather than confirm whatever was built.

- **The measurement:** a subscriber with a fixed, known latency; 1, 2, 4 and 8 workers; deliveries
  per second and peak RSS for each; interleaved order, several runs per arm.
- **The control that makes it mean something:** the same sweep against a subscriber that answers
  instantly. If the curve is flat in both, the ceiling is ours; if it is flat only in the slow one,
  it is the dispatcher.
- **Rejected: deciding the number from the design.** Both mechanisms are plausible and they predict
  different answers, which is exactly when a measurement is cheap and an argument is not.
- Not covered: changing the engine. There is no second HTTPS engine on native to change to.

- AC: a table in `docs/research/` with the arms, the host, the run count and which of the two
  hypotheses survived; the default worker count set from it, with the number quoted where it is set.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/DeliveryWorkers.kt`,
  `bench/delivery.sh`
