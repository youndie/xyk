---
id: endpoint-journal
title: Journal — what arrived and what happened to it
type: api_endpoints
status: draft
services:
  - xyk-server
contract_source:
  - xyk:server JournalResource
parent_feature: feature-journal
---

# API: journal

> The **complete** route reference for the operator-facing half: the rendered pages and the JSON
> behind them.
>
> **Status `draft`, but every route exists** — the pages at B-12 and the JSON at B-13 (2026-09-15),
> both checked through HTTP. It stays `draft` while two of its answers cannot yet be produced: `410`
> needs retention (B-19), and delivery attempts need a worker (B-10).

## Routes — all of them, no exceptions

| Method and path | Service | Auth tier | In a generated schema? | Purpose |
|---|---|---|---|---|
| `GET /` | xyk-server | whatever the deployment puts in front | n/a | redirect to `/journal` |
| `GET /journal` | xyk-server | same | n/a | the page: recent events, filterable |
| `GET /journal/{eventId}` | xyk-server | same | n/a | one event and every delivery attempt |
| `GET /api/events` | xyk-server | same | n/a | the same list, as JSON |
| `GET /api/events/{eventId}` | xyk-server | same | n/a | one event, without the payload |
| `GET /api/events/{eventId}/payload` | xyk-server | same | n/a | the stored bytes, as received |
| `POST /api/events/{eventId}/redeliver` | xyk-server | same | n/a | schedule a fresh delivery to one subscriber or to all |

**There is no login and no session.** The journal is protected by the cluster — forward-auth, an
ingress rule, a network policy — and saying so here is the point: an operator route that looks
authenticated because it sits behind a proxy on one deployment is unauthenticated on the next one.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| `GET /journal`, `GET /journal/{eventId}` | `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/JournalRouting.kt` |
| the markup | `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/JournalPage.kt` |
| `GET /api/events*` | `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/JournalApi.kt` |
| `POST .../redeliver` | `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/domain/RedeliverUseCase.kt` |
| the resources | `server/src/commonMain/kotlin/io/github/youndie/xyk/contract/JournalResource.kt` |

## Query parameters on the list

Filtering exists so an operator can answer "did *that* one arrive", so the filters are the
coordinates of a single incident rather than a general query language:

| Parameter | Meaning |
|---|---|
| `endpoint` | endpoint id |
| `state` | `pending` / `delivered` / `failing` / `dead` |
| `since`, `until` | epoch seconds, inclusive |
| `cursor`, `limit` | keyset pagination; `limit` is capped server-side |

**Keyset, not offset.** The list is written to while it is read, and an offset page under insert
traffic shows some rows twice and skips others — which, on a page whose only job is to be believed,
is worse than being slow.

## Responses

| Condition | Status | Body |
|---|---|---|
| list, page rendered or JSON returned | `200` | page / `{"events":[...],"cursor":"..."}` |
| event unknown | `404` | page: an empty state naming the id; JSON: `{"error":"unknown event"}` |
| payload purged by retention | `410` | `{"error":"payload purged","purgedAt":<epoch>}` |
| redelivery accepted | `202` | `{"event":"<id>","scheduled":<n>}` |
| redelivery of an event with no subscribers | `409` | `{"error":"no subscribers"}` |

**`410` and not `404` for a purged payload** — the event existed and its record is still there; only
the bytes are gone. Collapsing the two would make retention look like data loss on the page.

**`202` for redelivery, never `200`.** It schedules a timer; the delivery has not happened when the
response is written, and a `200` would claim it had.

## Bodies

The shapes are the `@Serializable` classes in
`server/src/commonMain/kotlin/io/github/youndie/xyk/contract/JournalResource.kt` — linked, not
copied. One property of them is a contract rather than a detail and so is written here: the
serializer is configured with `encodeDefaults = false` in this codebase, which means **a field equal
to its default disappears from the response**. Numeric counters that can legitimately be zero
(`attempts`, `walBytes`) therefore carry no default, or a reader sees an absent key and cannot tell
it from a zero.

`GET .../payload` streams through an explicit 64 KB `respondBytesWriter` loop, not `respondSource`,
which holds the whole body in memory ([research §1.9](../research/research-architecture.md)). The
bytes are paged out of SQLite with `substr` for the same reason: the driver hands back a value, so
reading the blob whole would put the payload in memory twice before any of it reached the socket.

It answers with the `Content-Type` that arrived and **an ETag that is the event id** — corrected at
B-13 from "computed from the content". An event is immutable once stored, so its id identifies its
bytes exactly; hashing the body would mean reading all of it, which is what the chunked read exists
to avoid. Native Ktor has no modification time to build a `Last-Modified` from either way.
