---
id: B-12
title: "The journal page and its five states"
status: done
priority: P1
size: M
stage: stage-1-product
epic: feature-journal
blocked_by: [B-06]
---

# B-12 — The journal page and its five states

One page rendered by the server: the list of events, one event with its attempts, and the states in
between. Empty, Content, Detail, Purged, Degraded — the last one because a journal that looks normal
while nothing is being delivered is worse than one that is down.

- **Decision: server-rendered, no client, no bundle.** The only reader is an operator asking about
  one delivery; a client would double the image and add a build.
- **Decision: no compression.** `ktor-server-compression` is JVM-only; the page is built small
  instead. Anything static that appears later is compressed at image build time.
- **Decision: the empty state carries the endpoint's URL and a `curl` line.** On a fresh install the
  page is the only documentation anybody reads.
- Not covered: search inside payloads, charts, authentication.

- AC: the scenarios of [feature-journal](../features/feature-journal.md) concerning the page pass.
  **Done** — 13 tests over the renderer on both targets, plus the run against the image.
- AC: the image smoke test requires a **rendered** page containing a timestamp, not a status code —
  a smoke test that stops at `200` passes on an image that cannot render anything. **Done**:
  `dev/image-smoke.sh`, now part of `make build`. It creates an endpoint, sends a signed webhook, and
  greps the journal for `YYYY-MM-DD HH:MM`.

## Closed 2026-09-15

Five states, all rendered by a pure function of a model, which is the only reason two of them can be
tested at all: **Purged** needs retention (B-19) and **Degraded** needs a worker (B-11), so neither
is reachable from the database yet and both are covered from a hand-made model.

**Rendered by hand — no template engine, no `kotlinx.html`.** That is a size decision, not taste:
the image budget is 10 MB against a binary already at 19.4 MB linked with its HTTP client
(B-05/B-23), and a markup library is paid for in exactly the place that hurts. The price is that
escaping is ours, so everything that reaches the page goes through `escapeHtml` — including the
`Content-Type` a sender chose, which is tested next to an operator-written field for exactly the
reason those bugs happen.

**The timestamp formatter is hand-rolled, and its test constants come from `date -u`** — the same
rule as the signature vectors. A hand-written calendar checked against its own output agrees with
itself about February; five constants from GNU `date` do not.

**The control on the smoke test was wrong the first time, and that is the finding.** `SECRET=wrong`
changes the endpoint *and* the signature, so the script passed. It now takes `SIGN_SECRET`, which
breaks only the signing side: the control exits 1 with "the webhook was not accepted (401)" and the
real run exits 0. What that control still does **not** exercise is the branch the script was written
for — an image that starts, answers `200` and renders nothing because its charset converters are
missing. The natural negative for that is a `scratch` image without the gconv tree, and it arrives
with [B-18](B-18-scratch-image.md).

**Size, measured after the page landed:** binary 9 662 208 → **10 145 072** bytes, image
14 163 968 → **14 288 384** pull bytes. The journal cost 483 KB of binary.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/JournalPage.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/JournalRouting.kt`,
  `dev/image-smoke.sh`
