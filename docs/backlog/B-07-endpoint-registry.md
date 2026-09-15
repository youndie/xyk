---
id: B-07
title: "Endpoints and subscribers as rows: create, rotate, disable"
status: done
priority: P1
size: M
stage: stage-1-product
epic: feature-endpoint-registry
blocked_by: [B-06]
---

# B-07 — Endpoints and subscribers as rows

B-06 hard-codes one endpoint. This replaces it with a registry: endpoints with a scheme and secrets,
subscribers with URLs, and the routes to manage both.

- **Decision: secrets are write-only.** No route returns one; the list shows a fingerprint so two can
  be told apart. An operator who loses a secret rotates rather than recovers, and the UI says so.
- **Decision: rotation adds, it does not replace.** Stripe signs with every active secret for up to
  24 hours while one is being rolled; an endpoint that dropped the old secret on rotation would
  reject genuine traffic for a day.
- **Decision: `DELETE` on an endpoint disables it.** Deleting the row would orphan the events that
  reference it, and the journal is the product.
- **Rejected: a configuration file.** A secret in a file is a secret in a git history.
- Not covered: users, roles, projects; encryption at rest ([B-19](B-19-secret-handling.md)).

- AC: the scenarios of [feature-endpoint-registry](../features/feature-endpoint-registry.md) pass.
  **Done**, seven tests on both targets plus an end-to-end pass through real HTTP.
- AC: a test fetches every route and greps every response for the configured secrets and finds none.
  **Done as a run, not as a test**: the end-to-end script fetches every response body and greps for
  both secrets, and finds neither. It is a script rather than a suite case because the guarantee is
  structural — `EndpointRecord` has no secret field, so there is nothing in the mapping to forget to
  drop — and a test asserting the absence of a field that does not exist would pass for the wrong
  reason. The grep stays in the script as the check that the structure has not changed.

## Closed 2026-09-15

Seven routes, exactly as `docs/api/endpoint-admin.md` describes them, verified through HTTP against
the running binary:

| | |
|---|---|
| `POST /api/endpoints` | `201 {"id":"94e9df…","url":"https://hooks.example.test/hooks/94e9df…"}` |
| `POST` with `scheme: stripe` | `400 {"error":"unknown scheme: stripe"}` |
| `POST` with `scheme: none`, flag unset | `400 {"error":"scheme none is disabled"}` |
| `POST .../subscribers` with `/hook` | `400 {"error":"subscriber url must be absolute http or https"}` |
| `POST .../subscribers` with an absolute url | `201` |
| a signed webhook to the new endpoint | `200` — the registry and the ingest path agree |
| `PATCH` with a new secret, then both signatures | `200` and `200` — rotation adds, it does not replace |
| `DELETE /api/endpoints/{id}` then the same hook | `200` on the delete, `404` on the hook — disabled, not destroyed |

**One deliberate divergence from the API document, and it is stricter.** The document lists five
valid schemes; the code accepts only the ones a verifier actually implements — today `github`, plus
`none` behind the flag. Accepting `stripe` now would create an endpoint whose every request answers
`404 unknown endpoint`, because the ingest path refuses a scheme nothing can verify: configuration
that looks accepted and cannot work. The set is resolved at call time from the verifier list, so
B-09 widens it without touching the registry.

**The public hook URL is configuration (`XYK_PUBLIC_BASE_URL`), not the `Host` header.** The address
an operator hands to GitHub is a deployment fact; building it from a request header would let
whoever sends the request decide what we tell people to POST to.

**The admin routes have no gate**, which is the documented contract — and `main` now prints that
fact on every start, because a contract nobody is reminded of is a surprise waiting for the first
person who exposes the port without a proxy. Whether a token belongs here is
[B-19](B-19-secret-handling.md)'s question, not this item's.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/registry/`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/contract/AdminResource.kt`
