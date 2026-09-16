---
id: B-10
title: "The delivery sink: one POST, one timeout, one attempt row"
status: done
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

## Built, 2026-09-16 — with one acceptance criterion honestly unmet

**The sink exists and its seven single-attempt cases are green on the native target**
(`DeliverySinkTest`, 7 tests, 0 failures): a `200` delivers the stored bytes byte for byte with the
four `X-Xyk-*` headers; a `500` throws so the worker retries; a `302` is a failure and the sink posts
exactly once; a subscriber that never answers is cut at the timeout and recorded with a **null**
status rather than a sentinel; a refused connection is a failed attempt rather than a crash; the
fifth attempt writes `dead` instead of `pending`; and a delivery whose row is gone returns normally
so the timer retires instead of retrying against nothing.

**`OutboundPost` is a port because of the linker, not because of the tests.** The default binary
links no HTTP engine — `ktor-client-curl` is 8.8 MB of image and only a build that delivers needs it
— so `commonMain` may not name a Ktor type. `outboundPost()` is `expect`, with the curl binding in
the `with-curl` variant and `null` everywhere else. The testability is a by-product, and the better
half: the timeout, the redirect and the never-answers cases need no socket.

**`followRedirects = false` is set on the client, not per request**, so no future call site can
forget it. The sink treating a `3xx` as a failure only means something if there is a `3xx` to treat.

**Migration v6 adds `delivery_attempts`** — status, duration and a bounded prefix of the response.
The `attempts` counter on `deliveries` answers "how many times"; an operator is asking "what did it
say". The prefix is bounded because that string is written by somebody else and arrives on every
failed attempt.

**A cancellation is not an attempt.** The first version caught `Exception` around the POST, which
swallows the `CancellationException` the worker's scope raises during shutdown — it would have
recorded a failed attempt nobody made and let a cancelled coroutine run past the drain. Caught and
rethrown ahead of the transport catch, below the timeout one, because
`TimeoutCancellationException` is itself a `CancellationException`.

### The https acceptance criterion is half met, and the half that is missing is stated

**Measured:** `GET https://example.com -> 200` from **inside** the `gcr.io/distroless/cc-debian13`
image built with the curl engine. That settles the part this criterion is really about — certificates
exist on every developer machine and in no minimal base image, and the failure looks like a
connection error rather than a missing file.

**Not measured: a delivery.** Nothing calls the sink yet; the worker that does is
[B-11](B-11-delivery-workers.md). A POST through the sink from inside the image is that item's to
run, and writing "https delivery works" on the strength of a `GET` would be exactly the substitution
this repository keeps catching.

- AC: **met** for the single-attempt scenarios.
- AC: **half met** for https from inside the image — the engine and the certificates are measured, a
  delivery is not, and it moves to B-11 rather than being counted here.
- AC: the delivery scenarios of [feature-delivery](../features/feature-delivery.md) that concern a
  single attempt pass, including the timeout one, measured against a subscriber that never responds.
- AC: an https delivery succeeds **from inside the image** — certificates exist on every developer
  machine and in no minimal base image.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/DeliverySink.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/HttpClients.kt`

## The half that was outstanding is closed, by B-26 rather than here

This item left one acceptance line half met: certificates were measured from inside the image
(`GET https://example.com -> 200`) but no delivery had been made, because nothing called the sink
until the workers landed.

[B-26](B-26-schedule-timers-on-ingest.md) ran it: a webhook posted to the released image reached a
real subscriber with the body byte for byte, the sender's own content type, `X-Xyk-Attempt: 1`, and
the journal showing `deliveries: 1, pending: 0, dead: 0`.

- AC: **met**, in the item that could run it. Recorded here rather than left as a half, because an
  acceptance line that is satisfied elsewhere and not marked is a line somebody re-does.
