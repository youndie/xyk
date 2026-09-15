---
id: endpoint-ingest
title: Ingest — receiving a webhook
type: api_endpoints
status: draft
services:
  - xyk-server
contract_source:
  - xyk:server IngestResource
parent_feature: feature-ingest
---

# API: ingest

> The **complete** route reference for the inbound half. There is no generated schema in this
> repository and none planned, so this document is the reference — which is why it lists the shapes
> of the error bodies as well as the happy path.
>
> **Status `draft`, but the route exists (B-06, 2026-09-15).** Every status in the table below was
> observed through real HTTP against the running binary — `200`, `401 signature invalid`,
> `401 signature missing`, `404 unknown endpoint`, `413 body too large`. It stays `draft` because the
> feature is not finished: one scheme of five, and the endpoint is configuration until the registry
> arrives.

## Routes — all of them, no exceptions

| Method and path | Service | Auth tier | In a generated schema? | Purpose |
|---|---|---|---|---|
| `POST /hooks/{endpointId}` | xyk-server | **the signature is the authentication** — no token, no session, no forward-auth | n/a (no generator) | accept one webhook |

There is exactly one inbound route, and that is deliberate: every sender gets the same URL shape, and
which scheme verifies it is a property of the endpoint row, not of the path. A per-vendor path
(`/hooks/github/...`) would put the same fact in two places and let them disagree.

`{endpointId}` is an opaque, unguessable id generated when the endpoint is created
([endpoint-admin](endpoint-admin.md)). It is not a secret — the signature is — but it is not
enumerable either.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| `POST /hooks/{endpointId}` | `server/src/commonMain/kotlin/io/github/youndie/xyk/ingest/IngestRouting.kt` |
| verification | `server/src/commonMain/kotlin/io/github/youndie/xyk/verify/` |
| the transaction | `server/src/commonMain/kotlin/io/github/youndie/xyk/ingest/domain/AcceptEventUseCase.kt` |
| the resource | `server/src/commonMain/kotlin/io/github/youndie/xyk/contract/IngestResource.kt` |

## Request

**The body is bytes.** It is read once, in full, verified as received, and stored unchanged. Nothing
parses it on this path — a framework that reserialises a JSON body changes whitespace or key order
and destroys the signature ([research §1.4](../research/research-architecture.md)).

Headers that matter are the ones the endpoint's scheme names, and nothing else is inspected:

| Scheme | Header read |
|---|---|
| `github` | `X-Hub-Signature-256` |
| `stripe` | `Stripe-Signature` |
| `telegram` | `X-Telegram-Bot-Api-Secret-Token` |
| `hmac-sha256` (generic) | the header named in the endpoint row |
| `none` | — |

## Responses

| Condition | Status | Body |
|---|---|---|
| accepted and committed | `200` | `{"event":"<event id>"}` |
| endpoint unknown, or disabled | `404` | `{"error":"unknown endpoint"}` |
| the scheme's header is absent | `401` | `{"error":"signature missing"}` |
| the signature does not match | `401` | `{"error":"signature invalid"}` |
| Stripe timestamp outside the tolerance | `401` | `{"error":"signature stale"}` |
| body above `XYK_MAX_BODY_BYTES` | `413` | `{"error":"body too large"}` |
| the write failed | `500` | `{"error":"not stored"}` |

Three of these are decisions rather than obvious choices, so they are recorded here:

* **`404` for a disabled endpoint, the same as for one that never existed.** A distinct status would
  turn the route into an oracle for which ids are real. The operator sees the difference in the
  journal; the caller does not.
* **`401` and not `403`.** The request failed to prove who it is; it was not refused permission.
  The distinction matters to the sender's own retry logic — GitHub and Stripe both retry `5xx` and
  treat `4xx` as final, which is the behaviour we want for a bad signature.
* **`signature stale` is its own code and not `signature invalid`.** A skewed clock and a wrong
  secret are different incidents, and an operator who cannot tell them apart debugs the wrong one
  ([research, Risk 5](../research/research-architecture.md)).

**`200` means committed**, not "queued". The response is written after the transaction holding the
event row and its timer commits. Answering earlier would make the number in the throughput criterion
meaningless: it would measure how fast the process can accept bytes it may then lose.

## Errors the sender sees on the other side

For completeness, because it decides what our statuses cost: Stripe retries for up to three days
with exponential backoff and treats any `3xx` as a failure; GitHub does not retry automatically at
all. So a `500` from us is recoverable with Stripe and a silently lost event with GitHub — which is
the argument for the ingest path having as little in it as possible.
