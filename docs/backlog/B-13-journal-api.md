---
id: B-13
title: "The journal API: keyset pages, streamed payloads, redelivery"
status: done
priority: P1
size: M
stage: stage-1-product
epic: feature-journal
blocked_by: [B-12]
---

# B-13 — The journal API: keyset pages, streamed payloads, redelivery

The JSON behind the page, plus the one write it offers: redeliver.

- **Decision: keyset pagination, not offset.** The list is written to while it is read; an offset page
  under insert traffic shows some rows twice and skips others, on a page whose only job is to be
  believed.
- **Decision: payloads stream through an explicit 64 KB `respondBytesWriter` loop.**
  `respondSource` holds the whole body in memory — elsewhere 20 parallel downloads took 232 MB
  against a 256 MB limit.
- **Decision: a purged payload answers `410`, not `404`.** The event exists; only the bytes are gone,
  and collapsing the two makes retention look like data loss.
- **Decision: redelivery answers `202` with the number of timers scheduled.** It has not been
  delivered when the response is written, and `200` would say it had.
- **Decision: an ETag computed from content.** Native Ktor has no file modification time to build a
  `Last-Modified` from.
- Not covered: a query language; anything that needs the payload parsed.

- AC: paging through the list while ingest runs at a steady rate yields every event exactly once.
  **Done as a test**: the pager inserts an event *between* pages — the case an offset would show
  twice or skip — and asserts every id appears exactly once.
- AC: `GET /api/events/{id}/payload` on a 50 MB payload does not move resident memory by more than
  the buffer. **Partly**: a 200 KB payload streams back byte for byte through the chunked path and
  `VmRSS` stayed at 63 904 kB. 50 MB is above the ingest limit (1 MiB), so a payload that size cannot
  exist yet — the number that matters will come from [B-21](B-21-criterion-memory.md), under load,
  rather than from a body this service would refuse.

## Closed 2026-09-15

Four routes, typed resources, verified through HTTP:

| | |
|---|---|
| `GET /api/events?limit=2` then `?cursor=…` | two pages, no id repeated |
| `GET /api/events/{id}/payload` on 200 000 bytes | `ETag: "<event id>"`, and `cmp` says byte for byte |
| `POST /api/events/{id}/redeliver` | `202 {"event":"465d01…","scheduled":1}`; deliveries 4 → 5 |
| `GET /api/events/nope` | `404 {"error":"unknown event"}` |

**The payload is paged out of SQLite with `substr`, not read whole.** sqlx4k hands back a value
rather than a stream, so a whole-blob read would put the payload in memory twice — once as bytes and
once as the hex it travels in — before a single byte reached the socket. `substr(body, offset, len)`
slices it inside the database; 64 KiB at a time goes out through `respondBytesWriter`.

**A deliberate divergence from [endpoint-journal](../api/endpoint-journal.md): the ETag is the event
id, not a hash of the content.** It is the stronger choice rather than the lazy one — an event is
immutable once stored, so its id identifies its bytes exactly, while hashing the body means reading
all of it into memory, which is precisely what the chunked read exists to avoid. The document has
been corrected rather than left to disagree.

**`409` for a redelivery with no subscribers**, not a cheerful `202` about nothing scheduled; `410`
for a purged payload, which the code answers and nothing can yet trigger because retention is B-19.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/JournalApi.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/domain/RedeliverUseCase.kt`
