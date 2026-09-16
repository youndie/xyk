---
id: B-14
title: "How many delivery workers, and is the curl dispatcher the real ceiling?"
status: done
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

## Refuted, 2026-09-16 — [delivery-workers.md](../research/measurements-2026-09-16/delivery-workers.md)

| workers | slow subscriber (100 ms) | instant |
|---:|---:|---:|
| 1 | 6.3 /s | 17.5 /s |
| 2 | 12.8 /s | 38 /s |
| 4 | **27.2 /s** | ~97 /s |
| 8 | 46.7 /s | ~238 /s |

Three rounds an arm, resident memory 29–33 MB with no trend against the worker count, and the slow
arm reproducible to within 1 % (95.24 / 95.09 / 94.85 s at one worker).

**The hypothesis recorded above is wrong.** The ceiling does not belong to the `curl-dispatcher`
thread and two workers do not reach it: throughput doubles from 1 to 2, doubles again to 4, and rises
1.72× more at 8. A single-threaded event loop multiplexes — that is what libcurl's multi interface
is for — and reading "one thread" as "one request at a time" is what produced the prediction.

**The control tested less than it was built to.** It was chosen expecting flat against an instant
subscriber under both hypotheses; it scales instead. The slow arm is where the two differed and it
answered, so the run stands — but what the control established is the weaker "nothing in the service
serialises deliveries either".

**The default is now four**, quoted in `ServerConfig.DEFAULT_DELIVERY_WORKERS` with these numbers
beside it. Four rather than eight because the curve was still linear at eight and the last point
measured is a poor place to sit; what bounds it in a deployment is what the subscriber tolerates,
which is not ours to pick.

- AC: **met** — the table, the host, three rounds, and which hypothesis survived (neither the
  dispatcher one nor "two is enough").
- AC: **met** — the default is set from the measurement and the numbers are where the constant is.
- **Found by this run rather than in it:** the memory criterion was measured on a build with no
  delivery half at all. [B-29](B-29-memory-with-delivery-on.md).
