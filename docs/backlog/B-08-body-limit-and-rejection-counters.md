---
id: B-08
title: "A body limit that does not buffer, and counters for what was refused"
status: done
priority: P1
size: S
stage: stage-1-product
epic: feature-ingest
blocked_by: [B-06]
---

# B-08 — A body limit that does not buffer, and counters for what was refused

A public write endpoint with no size limit is a memory exhaustion switch, and a limit that reads the
body before measuring it is the same switch with extra steps. Separately: a rejected request leaves
nothing behind by design, which means an operator debugging "the webhook never arrives" currently
has nothing at all to look at.

- **Decision: the limit is enforced before the body is in memory**, from the declared length where
  there is one and by counting bytes as they arrive where there is not.
- **Decision: rejections are counted per endpoint and per reason** — missing header, invalid, stale,
  too large, unknown endpoint — and shown on the endpoint's row. Counters, not rows: storing rejected
  bodies would make the endpoint a free write endpoint after all, which is what the rejection was
  for.
- **Rejected: a rejection log with payloads behind a flag.** It is the useful thing to have and it is
  a foot-gun in the one place where a foot-gun is unacceptable.
- Not covered: rate limiting per sender.

- AC: a 64 MiB body against a 1 MiB limit answers `413` and the process's resident memory does not
  move beyond a small constant. **Measured** on the build machine: `VmRSS` 42 880 kB before, 46 560 kB
  after three 64 MiB bodies in a row — **+3.6 MB against 192 MB offered**. All three answered `413`
  and no event row was written.
- AC: the counters distinguish all five reasons, and the journal's empty state points at them —
  "nothing arrived" and "everything was refused" look identical without it. **Six reasons, done**
  (`NOT_STORED` joined the five); the counters are on the endpoint in the API. The journal's empty
  state is [B-12](B-12-journal-page.md), which now has something to point at.

## Closed 2026-09-15

The limit was already enforced before the read at B-06 — declared length first, then one byte past
the limit — so this item is really the counters, and the measurement that shows the limit works.

**Counted in memory, flushed on a timer and at shutdown, rather than written per rejection.** A
flood of bad signatures is exactly what these counters exist to show, and an upsert per rejection at
a few thousand a second would take SQLite's single writer lock away from the genuine traffic beside
it. The price is stated rather than hidden: **a crash loses at most one flush interval of counts**,
which is the right trade for a number read during an incident rather than billed against.

**A rejection for an endpoint that does not exist is counted against a global bucket**, not against
the id in the URL — anything else lets anyone with a URL bar create unbounded rows in somebody
else's database. The bucket is the sentinel string `(unknown)` rather than `NULL`, because SQLite
treats NULLs as distinct in a unique index and the upsert would insert a new row every time.

End to end, and the table after a `SIGTERM`:

```
(unknown)                        | UNKNOWN_ENDPOINT   | 2
0659bb77e6b58415bb1766e25b2b8ee8 | BODY_TOO_LARGE     | 3
0659bb77e6b58415bb1766e25b2b8ee8 | SIGNATURE_INVALID  | 1
0659bb77e6b58415bb1766e25b2b8ee8 | SIGNATURE_MISSING  | 1
```
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/ingest/IngestRouting.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/registry/data/`
