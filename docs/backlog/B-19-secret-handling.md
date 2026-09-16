---
id: B-19
title: "Secrets at rest, and how long payloads are kept"
status: done
priority: P2
size: M
stage: stage-1-product
epic: feature-endpoint-registry
blocked_by: [B-07]
---

# B-19 — Secrets at rest, and how long payloads are kept

Two questions with one owner, and neither is an implementation detail.

**Secrets.** They are write-only over HTTP already ([B-07](B-07-endpoint-registry.md)), and they sit
in SQLite in the volume. Encrypting them at rest means a key, and a key in the environment protects
against a stolen volume and not against a stolen pod. *Open:* is that the threat worth paying for
here, or is the honest answer "the volume is as sensitive as the secrets, treat it that way and say
so"?

**Payloads.** xyk stores raw bodies by design, and a Stripe event carries customer data. Keeping
them forever is a liability; deleting them early removes the reason the journal exists.
*Hypothesis:* 7 days by default, overridable per endpoint, purged on the same timer as the WAL
checkpoint. This needs an owner's answer because it is a product promise, not a setting.

- **Decision already taken: the event record outlives its payload.** Purging removes bytes and leaves
  the row, and the API answers `410` rather than `404` so that retention never looks like data loss.
- **Rejected: purging by row count.** "Keep the last N" makes retention depend on traffic, so a busy
  Monday silently shortens the window on a quiet endpoint.

- AC: an answer from the owner, written into
  [research §3](../research/research-architecture.md) as a decision with its reason — not a default
  chosen by whoever implemented the purge. **Still open — both halves.** This item stays
  `question` for exactly that reason.
- AC: whatever is decided, the purge has a test that a purged payload is unreadable and its event is
  still listed. **Done** — `RetentionTest`, four cases on both targets, and the whole path checked
  through HTTP.

## The machinery is built; the policy is not (2026-09-15)

**Retention exists and is off.** `XYK_RETENTION_DAYS` defaults to `0`, which means *for ever*: a
service that starts deleting somebody's data because nobody configured it is the one failure here
that cannot be undone. What ships is the mechanism, so that whatever the owner answers is a line of
configuration rather than a milestone.

A purge is an `UPDATE`, never a `DELETE`: `body` becomes empty and `purged_at` is stamped, while
**`body_bytes` keeps the size the payload arrived with** — "47 bytes, purged on the 22nd" is an
answer to what happened, and "0 bytes" is a different and wrong one. Verified through HTTP:

| | |
|---|---|
| `GET /api/events/{id}/payload` after a purge | `410 {"error":"payload purged","purgedAt":12345}` |
| `GET /api/events/{id}` | still there, `"bodyBytes":7`, `"purgedAt":12345` |
| the event page | renders `purged by retention on …` |
| `GET /api/events` | the event is still listed |
| a service with no horizon configured | purges nothing — the test that matters most |

**Secrets at rest: nothing was built, deliberately.** Encrypting them needs a key, and a key in the
environment of the same process protects against a stolen volume and not against a stolen pod. The
honest alternative — "the volume is as sensitive as the secrets; treat it that way and say so" — is
a sentence in the documentation rather than code, and which of the two is wanted is the owner's
call. Until then the secrets sit in the volume, write-only over HTTP (B-07), shown as fingerprints.

**Two questions, one place.** They were open questions 3 and 3b in
[research §3](../research/research-architecture.md), alongside the third one this work produced —
whether to spend 2.8 MB of image on charset converters this service does not call (B-18, question
3a). **All three are now decisions**, taken by the owner: gconv stays (B-23), retention is seven
days, secrets are not encrypted and the volume is documented as sensitive.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/registry/data/`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/db/Retention.kt`

## Answered by the owner, 2026-09-16

**Payloads: seven days by default.** `ServerConfig.DEFAULT_RETENTION_DAYS = 7`; `0` still means never
purge and is still expressible.

It is a default rather than a setting somebody must choose because xyk stores raw bodies by design,
so every day nobody thinks about retention is a day the liability grows — and a service whose safe
configuration requires an act of configuration is usually unsafe. A week answers the question the
journal exists for across a working week, and does not accumulate a copy of somebody's customer data
for ever in a volume nobody audits.

**The change of default is itself the risk this item warned about**, pointed the other way: the
previous default was `0`, so a deployment upgrading across this line and relying on it begins purging
bodies older than a week. Nothing is deployed yet, so it costs nothing today; it is written into
[research §3](../research/research-architecture.md) as a decision and into the config comment, rather
than living only in a diff, because the failure it would produce is silent and unrecoverable.

**Secrets: not encrypted at rest, and the documentation says why in those words.**
[`services/xyk-server.md` §7a](../services/xyk-server.md) now opens with the operational sentence —
*the volume is as sensitive as the secrets in it; back it up and grant access to it as such* — and
gives an operator the four things that follow from it.

The reason encryption was not taken is that it does not buy what it looks like: a key in the
environment of the same process is readable by anything that can read the process, so it defends
against a stolen **volume** and not a stolen **pod**, and the second is likelier in a cluster. What
it would reliably produce is a sentence in an audit that is true of the bytes and false about the
threat. A deployment needing more wants an external secret store and a different design.

- AC: **met** — both halves are in research §3 and §3b as decisions with their reasons, written from
  the owner's answer rather than chosen by whoever implemented the purge.
- AC: **met earlier** — `RetentionTest`, four cases on both targets, plus the `410` path through HTTP.
