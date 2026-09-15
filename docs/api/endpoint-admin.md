---
id: endpoint-admin
title: Admin — endpoints, secrets and subscribers
type: api_endpoints
status: draft
services:
  - xyk-server
contract_source:
  - xyk:server AdminResource
parent_feature: feature-endpoint-registry
---

# API: admin

> The **complete** route reference for configuring what xyk accepts and where it forwards.
>
> **Status `draft`, but every route exists as of B-07 (2026-09-15)** and answers the statuses below,
> checked through HTTP. It stays `draft` while the feature set around it is incomplete — see the
> note on schemes under the error table.

## Routes — all of them, no exceptions

| Method and path | Service | Auth tier | In a generated schema? | Purpose |
|---|---|---|---|---|
| `GET /api/endpoints` | xyk-server | the deployment's front door | n/a | list endpoints; **never** returns a secret. Each carries `rejections`: how many requests it turned away, by reason |
| `POST /api/endpoints` | xyk-server | same | n/a | create an endpoint: scheme, secret, description |
| `PATCH /api/endpoints/{id}` | xyk-server | same | n/a | enable, disable, rotate the secret, change the description. **Not the tolerance yet** — that is set at creation through `schemeConfig` (B-09) |
| `DELETE /api/endpoints/{id}` | xyk-server | same | n/a | disable and stop accepting; events are kept |
| `GET /api/endpoints/{id}/subscribers` | xyk-server | same | n/a | list subscribers |
| `POST /api/endpoints/{id}/subscribers` | xyk-server | same | n/a | add a subscriber URL |
| `DELETE /api/subscribers/{id}` | xyk-server | same | n/a | remove a subscriber; in-flight timers still fire |

`DELETE /api/endpoints/{id}` **disables rather than deletes**, and that is the contract, not an
implementation detail: deleting the row would orphan every event that references it, and the journal
is the reason the product exists.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| all of the above | `server/src/commonMain/kotlin/io/github/youndie/xyk/registry/RegistryRouting.kt` |
| the rules (URL shape, scheme validity) | `server/src/commonMain/kotlin/io/github/youndie/xyk/registry/domain/Rules.kt` |
| storage | `server/src/commonMain/kotlin/io/github/youndie/xyk/registry/data/Sqlx4kRegistryRepository.kt` |
| the resources | `server/src/commonMain/kotlin/io/github/youndie/xyk/contract/AdminResource.kt` |

## Secrets

**A secret is written and never read back.** `POST` and `PATCH` accept one; no route returns one;
the list shows a fingerprint (first eight hex characters of the HMAC of the secret under a
per-install key) so that an operator can tell two secrets apart without seeing either.

**Rotation keeps both secrets for a window**, because Stripe does exactly that — for up to 24 hours
it signs with every active secret, and an endpoint that dropped the old one the instant a new one
was created would reject genuine traffic. `PATCH` therefore adds a secret and schedules the
retirement of the previous one; it does not replace.

## Responses

| Condition | Status | Body |
|---|---|---|
| created | `201` | `{"id":"...","url":"https://<host>/hooks/<id>"}` |
| updated / disabled | `200` | the endpoint, without the secret |
| unknown id | `404` | `{"error":"unknown endpoint"}` |
| scheme not one the code implements | `400` | `{"error":"unknown scheme: <value>"}` |
| subscriber URL is not absolute http(s) | `400` | `{"error":"subscriber url must be absolute http or https"}` |
| `none` requested without `XYK_ALLOW_UNVERIFIED=true` | `400` | `{"error":"scheme none is disabled"}` |

The last row is a guard rather than a feature: an endpoint that verifies nothing is a public write
endpoint on somebody's database, and it should take a deliberate act to create one.

**The accepted scheme set is what is implemented, which today is `github` and `none`** — narrower
than the five this document names, on purpose: an endpoint whose scheme nothing verifies would
answer `404` to every request it ever received, so it is refused at creation instead. The set is
read from the verifier list at call time, so B-09 widens it without a change here.
