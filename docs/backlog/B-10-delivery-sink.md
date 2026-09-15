---
id: B-10
title: "The delivery sink: one POST, one timeout, one attempt row"
status: open
priority: P1
size: M
stage: stage-1-product
epic: feature-delivery
blocked_by: [B-03]
---

# B-10 — The delivery sink: one POST, one timeout, one attempt row

chronik's `TimerWorker` calls a sink and reads a thrown exception as a failed attempt. This item is
that sink: resolve the subscriber, POST the stored bytes with the original `Content-Type` and the
`X-Xyk-*` headers, bound it with a timeout, write the attempt row, and return or throw.

- **Decision: the timeout is enforced here, inside the sink.** chronik has none, and `tick()` is a
  sequential loop over the batch — so without it one hung subscriber stalls up to 49 other
  deliveries. With it, a tick is bounded by `batchSize × timeout`, which is a number that can be
  reasoned about.
- **Decision: `3xx` is a failure, not a hop.** Following a redirect would deliver somebody's payload
  to an address no operator approved.
- **Decision: the attempt row is written even when the POST throws**, and before the exception
  propagates — otherwise the one case an operator most needs to see is the one case with no record.
- **Rejected: a sink that returns before the POST completes** to gain concurrency. It would silently
  turn every delivery into a success and disable retries entirely.
- Not covered: how many workers call it ([B-11](B-11-delivery-workers.md),
  [B-14](B-14-delivery-worker-count.md)); signing the outbound request.

- AC: the delivery scenarios of [feature-delivery](../features/feature-delivery.md) that concern a
  single attempt pass, including the timeout one, measured against a subscriber that never responds.
- AC: an https delivery succeeds **from inside the image** — certificates exist on every developer
  machine and in no minimal base image.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/DeliverySink.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/HttpClients.kt`
