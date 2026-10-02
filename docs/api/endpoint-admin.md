---
id: endpoint-admin
title: Admin — endpoints, secrets and subscribers
type: api_endpoints
status: active
services:
  - xyk-server
contract_source:
  - xyk:server AdminResource
parent_feature: feature-endpoint-registry
---

# API: admin

> The **complete** route reference for configuring what xyk accepts and where it forwards.
>
> **Every route exists** and answers the statuses below, checked through HTTP. One thing is narrower
> than this document: secret *retirement* is a column nothing writes, so a rotated secret stays valid
> until it is removed ([feature-endpoint-registry](../features/feature-endpoint-registry.md)).

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
the list shows a fingerprint — the first eight hex characters of an HMAC-SHA256 of the secret under
this installation's own key, `SecretFingerprints` in
`server/src/commonMain/kotlin/io/github/youndie/xyk/db/SecretFingerprints.kt` — so that an operator
can tell two secrets apart without seeing either. **It is keyed, so it is local to one install:** the
same secret gives a different fingerprint on every installation, and a fingerprint cannot be checked
against a guessed secret by anyone who does not hold the key. The key is drawn on the first start of
a database (schema version 8) and kept in it, in `install_key`, so fingerprints are stable across
restarts and a restored backup shows the ones it was taken with. Until 2026-10-02 the fingerprint was
a plain SHA-256 of the secret; the upgrade rewrites every stored one, the journal's included
([research D7](../research/research-architecture.md)).

**Rotation keeps both secrets for a window**, because Stripe does exactly that — for up to 24 hours
it signs with every active secret, and an endpoint that dropped the old one the instant a new one
was created would reject genuine traffic. `PATCH` therefore adds a secret and leaves the previous
one valid; it does not replace. **Nothing retires the previous one yet** — `retires_at` is a column
nothing writes, and verification tries every secret the endpoint holds.

## Request bodies

JSON, decoded in the charset the `Content-Type` declares, and UTF-8 when it declares none. Any other
charset is decoded by glibc's converters, which the image carries for this reason: without them a
body in `ISO-8859-1`, `windows-1251` or `KOI8-R` is accepted and stored with every non-ASCII
character replaced by U+FFFD, and one in `US-ASCII` is refused with `400`
([research §1.14](../research/research-architecture.md), the correction of 2026-10-02).
`dev/image-smoke.sh` checks a windows-1251 body on every image `make build` makes.

## Responses

| Condition | Status | Body |
|---|---|---|
| created | `201` | `{"id":"...","url":"https://<host>/hooks/<id>"}` |
| updated / disabled | `200` | the endpoint, without the secret |
| unknown id | `404` | `{"error":"unknown endpoint"}` |
| scheme not one the code implements | `400` | `{"error":"unknown scheme: <value>"}` |
| subscriber URL is not absolute http(s) | `400` | `{"error":"subscriber url must be absolute http or https"}` |
| `none` requested without `XYK_ALLOW_UNVERIFIED=true` | `400` | `{"error":"scheme none is disabled"}` |
| a blank secret for any scheme but `none` | `400` | `{"error":"a secret is required for scheme <value>"}` |
| `hmac-sha256` without `schemeConfig.header` | `400` | `{"error":"scheme hmac-sha256 needs schemeConfig.header"}` |
| `schemeConfig.encoding` other than `hex` or `base64` | `400` | `{"error":"schemeConfig.encoding must be hex or base64"}` |
| `PATCH` with a blank `secret` | `400` | `{"error":"secret must not be blank"}` |
| a body that cannot be decoded into the request | `400` | plain text, `Failed to convert request body to class …` — Ktor's, not this API's JSON shape |
| a text field holding NUL (U+0000) | `400` | `{"error":"<field> must not contain NUL"}`, e.g. `description`, `schemeConfig.header` |
| an id in the path holding NUL | `400` | `{"error":"text must not contain NUL"}` |
| anything the server did not expect | `500` | `{"error":"internal error"}` — the cause is logged, never put in the body |

The `none` row is a guard rather than a feature: an endpoint that verifies nothing is a public write
endpoint on somebody's database, and it should take a deliberate act to create one.

**NUL is refused, not stored, and before anything is written.** On Kotlin/Native a bound text value
ends at its NUL on the way to SQLite, so storing one would keep a shorter string than was sent and
say nothing about it. A `PATCH` refused for one field applies none of the others. Every other
character arrives as sent — quotes, backslashes, anything that looks like SQL — because every value
reaches the database as a bound parameter and never as part of the statement's text. Until
2026-10-02 values were written into the text, quoted; NUL then ended the statement early and the
answer was a `500` carrying the database's own error, which is also why no `500` from this API has a
body other than the one above.

**The accepted scheme set is the verifier list, read at call time:** `github`, `stripe`, `telegram`,
`hmac-sha256`, and `none` behind `XYK_ALLOW_UNVERIFIED=true`
(`server/src/commonMain/kotlin/io/github/youndie/xyk/ingest/IngestModule.kt`; the header each one
reads is in [endpoint-ingest](endpoint-ingest.md)). A scheme outside it is refused at
creation rather than stored, on purpose: an endpoint whose scheme nothing verifies would answer `404`
to every request it ever received. `dev/image-smoke.sh` creates one endpoint of each of the first
four on every image `make build` makes.
