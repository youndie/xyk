---
id: feature-journal
title: The journal — what arrived and what happened to it
type: feature
status: active
owner: unassigned
involved_services:
  - xyk-server
client_entries: []
api:
  - endpoint-journal
tags: [operator, ui]
---

# The journal — what arrived and what happened to it

> **Built** — the page, the JSON routes and redelivery. All five states are reachable from data:
> **Purged** since retention ships on by default at seven days
> ([B-19](../backlog/B-19-secret-handling.md)) and **Degraded** since the delivery workers and the
> readiness check that counts their ticks ([B-11](../backlog/B-11-delivery-workers.md)).
>
> **The page's cost was a defect and is fixed:** two of its subqueries were using the wrong index, so
> one page took 1 568 ms over 28 781 events and fifty concurrent readers completed nothing in twenty
> seconds. With `deliveries(event_id, state)` the same page costs 1.7 ms and the same load runs at
> 1 053 rps, median 47 ms, no failures ([B-25](../backlog/B-25-journal-page-collapses-under-concurrency.md)).

## 1. Overview

One page, and it answers one question: *did that webhook arrive, and what happened to it?* An
operator opens it when a customer says an order never appeared, and the answer has to be visible in
one screen: the event, when it came, which endpoint, whether it verified, and every delivery attempt
with its status and its duration.

The page is rendered by the same binary that ingests — there is no client application, no build
step, no bundle. `client_entries: []` is therefore an answer and not an omission: **this
documentation has no `screens/` layer on purpose**, and the states the page can be in are listed
below rather than in a screen document
([research, D3](../research/research-architecture.md)).

## 2. Business rules

* Every accepted event appears in the journal, including events with no subscribers and events whose
  deliveries all failed.
* A rejected request does **not** appear as an event — it was never stored. Rejections are counted
  per endpoint and per reason, and the counts are on the endpoint's row.
* The page shows, per event: time, endpoint, scheme that verified it, size, state, and the number of
  attempts. Per delivery: subscriber, attempt number, status, duration, and the first bytes of the
  response.
* **The raw payload is retrievable** for as long as retention keeps it; after that the event stays
  and the bytes are gone, and the page says so rather than showing an empty body.
* **Secrets never appear.** Not in the payload view, not in the endpoint list, not in a log line. An
  endpoint shows a secret *fingerprint*, and only so that two secrets can be told apart.
* Redelivery from the page schedules a new timer and says so; it does not claim the delivery
  happened.
* The list is filterable by endpoint, state and time, with keyset pagination — an offset page under
  concurrent inserts shows rows twice and skips others.

## 3. Page states

Listed here because there is no screen document; the names are the ones the renderer uses.

- **Empty:** no events match — a sentence naming the filter that is on, and a way to clear it. On a
  fresh install, the endpoint's URL and a `curl` line that sends a test webhook.
- **Content:** the list. Newest first, one row per event, the failing ones marked.
- **Detail:** one event with its attempts, and the payload behind a disclosure rather than inline —
  the payload is the largest and least often needed thing on the page.
- **Purged:** the event exists, the bytes do not; the row says when they were purged.
- **Degraded:** the database answers but the delivery workers have not ticked, so the numbers on the
  page are stale in a specific way that the page names. This state exists because a journal that
  looks normal while nothing is being delivered is worse than one that is down.

## 4. Code anchors

| Service | Code |
|---|---|
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/JournalRouting.kt` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/JournalPage.kt` — the markup |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/JournalApi.kt` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/domain/RedeliverUseCase.kt` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/data/Sqlx4kJournalRepository.kt` |
| xyk-server | `dev/image-smoke.sh` — the check that the page actually renders inside the image |

## 5. Scenarios (BDD / test cases)

**An `Automated:` line means a test runs the scenario.**

### Scenario: an event and its attempts are on one page

* **Given:** an event delivered on the third attempt
* **When:** the operator opens `/journal/{eventId}`
* **Then:** the page shows three attempts with their statuses and durations, the endpoint, and the
  scheme that verified the request
* **Automated:** `JournalPageTest` for the rendering; attempts themselves arrive with B-10

### Scenario: the page renders inside the released image

* **Given:** the image as published, with one event in the database
* **When:** `GET /journal` is requested from inside the container
* **Then:** the response is `200` and the body contains the event's timestamp rendered as
  `YYYY-MM-DD HH:MM`
* **Automated:** `dev/image-smoke.sh`, in `make build`
* **And:** this check runs in CI against the built image — a smoke test that stops at a status code
  passes on an image that cannot render anything
  ([research §1.7](../research/research-architecture.md))

### Scenario: a purged payload is not a missing event

* **Given:** an event older than the retention horizon
* **When:** its payload is requested
* **Then:** the response is `410` with `purgedAt`
* **And:** the event's page still shows every attempt
* **Automated:** `JournalPageTest` renders the state; the `410` is B-13 and the data is B-19

### Scenario: redelivery says what it did

* **Given:** a dead-lettered delivery
* **When:** the operator redelivers it
* **Then:** the response is `202` with the number of timers scheduled
* **And:** the page shows a new attempt only after it has actually been made
* **Automated:** `JournalRepositoryTest` for the scheduling; the `202`/`409` pair was checked
  through HTTP

### Scenario: the page is honest when the workers have stopped

* **Given:** delivery workers that have not completed a tick for several poll intervals
* **When:** the journal is opened
* **Then:** the page is in the **Degraded** state, naming the fact
* **And:** `GET /health/ready` is failing for the same reason
* **Automated:** `JournalPageTest`

### Scenario: the list is stable while events are arriving

* **Given:** ingest running at a steady rate
* **When:** the operator pages through the list
* **Then:** no event is shown twice and none is skipped
* **Automated:** `JournalRepositoryTest` — it inserts an event between pages, which is the case an
  offset gets wrong

### Scenario: no secret is ever rendered

* **Given:** endpoints of every supported scheme
* **When:** every page and every JSON route is fetched and searched for the stored secrets
* **Then:** none of them appears in any response
* **Automated:** the end-to-end grep in B-07; the page renders no secret field at all

## 6. Out of scope

* **Authentication and per-user access.** The deployment is responsible; the routes are documented
  as unauthenticated so nobody assumes otherwise.
* **Search inside payloads.** It means indexing bodies xyk deliberately does not parse.
* **Charts, rates, retention analytics.** The page answers about one event; a dashboard is a
  different product and would drag in a client.
* **Editing an event.** There is no such operation, and there should not be: the journal's value is
  that it is a record.

## 7. Quirks

* **The page collapses under concurrent readers**: fifty at once gave 1 rps, a 30-second p50 and 65 %
  failures on a four-core host, where every other route on the same binary managed hundreds per
  second. Found by B-20 while measuring something else; tracked as
  [B-25](../backlog/B-25-journal-page-collapses-under-concurrency.md). Until it is fixed, the journal
  is a page for one operator, not a dashboard on a wall.

* **The page reads the same database ingest writes**, which is exactly the shape that stops SQLite
  from ever truncating its journal. The pool of two connections and the explicit
  `wal_checkpoint(TRUNCATE)` exist because of this page
  ([research §1.8](../research/research-architecture.md)).
* **No compression.** `ktor-server-compression` is JVM-only; the page is built small instead, and
  static assets are pre-compressed at image build time if they ever appear.
* **Payload responses stream through an explicit 64 KB loop.** `respondSource` holds the whole body
  in memory — 20 parallel downloads took 232 MB against a 256 MB limit on another service.
* **Timestamps render in UTC and ignore `TZ`.** Kotlin/Native resolves the current time zone without
  reading `/usr/share/zoneinfo`, and both the `scratch` and distroless images behaved this way when
  it was checked by reading a rendered value.
