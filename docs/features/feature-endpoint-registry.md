---
id: feature-endpoint-registry
title: Endpoints, secrets and subscribers
type: feature
status: draft
owner: unassigned
involved_services:
  - xyk-server
client_entries: []
api:
  - endpoint-admin
tags: [configuration, security]
---

# Endpoints, secrets and subscribers

> **Built at B-07 (2026-09-15).** Every route below exists and answers what this document says, in
> the code and through HTTP. Two things are narrower than written: the accepted scheme set is what is
> implemented rather than the documented five (see [B-07](../backlog/B-07-endpoint-registry.md)), and
> secret *retirement* — the end of the rotation window — is a column nothing writes yet.

## 1. Overview

Before anything can be received there has to be somewhere to receive it: an endpoint, with a scheme
and a secret, and a list of subscribers to forward to. This feature is that configuration — created
through an HTTP route rather than a configuration file, because a secret that lives in a file lives
in a git history.

It is small on purpose. There are no projects, no users, no teams, no environments. One xyk holds a
handful of endpoints, and running a second one costs a container.

## 2. Business rules

* An endpoint has an opaque unguessable id, a scheme, one or more active secrets, an enabled flag,
  and a description an operator writes for themselves.
* **A secret is written and never read back.** The list shows a fingerprint so two can be told
  apart.
* **Rotation adds rather than replaces**, keeping the previous secret valid for a window. Stripe
  signs with every active secret for up to 24 hours while a secret is being rolled; an endpoint that
  dropped the old one immediately would reject genuine traffic.
* **Disabling is not deleting.** A disabled endpoint stops accepting and keeps its events; there is
  no route that destroys an event's history.
* A subscriber is an absolute `http`/`https` URL and an enabled flag. Nothing else — no
  per-subscriber transformation, no filters.
* `scheme: none` requires `XYK_ALLOW_UNVERIFIED=true` at startup.
* Changing configuration never touches timers already scheduled.

## 3. Flow

1. `POST /api/endpoints` with a scheme and a secret → `201` with the id and the full hook URL.
2. `POST /api/endpoints/{id}/subscribers` with a URL → the subscriber is enabled immediately, which
   means the **next** event, not the ones already stored.
3. `PATCH` to rotate, disable, or change the Stripe tolerance.
4. `DELETE /api/subscribers/{id}` stops future timers; the ones in flight still fire and fail.

## 4. Code anchors

| Service | Code |
|---|---|
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/registry/RegistryRouting.kt` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/registry/domain/Rules.kt` — the URL and scheme checks |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/registry/data/Sqlx4kRegistryRepository.kt` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/contract/AdminResource.kt` |
| xyk-server | `server/src/commonMain/kotlin/io/github/youndie/xyk/db/Migrate.kt` — `endpoints`, `endpoint_secrets`, `subscribers` |

## 5. Scenarios (BDD / test cases)

**Every scenario below is a *target*.**

### Scenario: creating an endpoint returns a usable URL and never the secret again

* **Given:** nothing
* **When:** an endpoint is created with scheme `github` and a secret
* **Then:** the response is `201` with an id and `https://<host>/hooks/<id>`
* **And:** no subsequent request to any route returns that secret
* **Automated:** `RegistryTest`

### Scenario: rotation keeps both secrets valid for the window

* **Given:** an endpoint with secret `old`
* **When:** it is rotated to `new`
* **Then:** requests signed with either are accepted until the window closes
* **And:** the journal shows a different fingerprint for each
* **Automated:** `RegistryTest`

### Scenario: an unverified endpoint cannot be created without the flag

* **Given:** `XYK_ALLOW_UNVERIFIED` unset
* **When:** an endpoint is created with scheme `none`
* **Then:** the response is `400` with `{"error":"scheme none is disabled"}`
* **Automated:** `RegistryTest`

### Scenario: a relative subscriber URL is refused

* **Given:** an endpoint
* **When:** a subscriber is added with `/hook`
* **Then:** the response is `400` with
  `{"error":"subscriber url must be absolute http or https"}`
* **Automated:** `RegistryTest`

### Scenario: disabling an endpoint keeps its history

* **Given:** an endpoint with events
* **When:** it is disabled
* **Then:** new requests get `404`
* **And:** its events are still in the journal, and their pending deliveries still fire
* **Automated:** `RegistryTest` (the endpoint and its subscribers survive; that the hook then
  answers `404` was checked through HTTP)

### Scenario: removing a subscriber does not erase its attempts

* **Given:** a subscriber with delivery attempts recorded
* **When:** it is removed
* **Then:** the attempts remain visible on each event's page, attributed to the removed subscriber

## 6. Out of scope

* **Users, roles, teams, projects.** One install, a handful of endpoints.
* **An import/export format.** Useful; not first.
* **Per-subscriber delivery settings** (its own timeout, its own attempt limit). The knobs are
  per-install for now, and that is a limitation worth revisiting once the defaults have been
  measured rather than guessed.

## 7. Quirks

* **A secret can be lost.** It is write-only by design, so an operator who did not record it rotates
  rather than recovers. That is the intended trade and it should be said out loud in the UI.
* **`DELETE` on an endpoint disables it.** The verb is a lie the HTTP vocabulary forces; the
  documentation is where it gets corrected.
* **Subscribers are enabled the moment they are created** — for the next event. There is no
  backfill, and an operator who expected one will find nothing in the journal to explain it.
