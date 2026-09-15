---
id: B-06
title: "One endpoint, end to end: verified, stored with its timers, answered 200"
status: done
priority: P0
size: L
stage: stage-0-foundations
epic: feature-ingest
blocked_by: [B-01, B-04]
---

# B-06 — One endpoint, end to end

The whole ingest path for a single hard-coded endpoint: read the body as bytes, verify one scheme,
write the event and one timer per subscriber in one transaction, answer `200`. It comes this early
because the declared throughput and memory criteria need something real to run against long before
the product is finished — and because a path that turns out to be too slow is cheaper to redesign in
week one.

- **Decision: `200` is written after the transaction commits.** Answering earlier would make the
  throughput number measure how fast the process can accept bytes it may lose.
- **Decision: the body is read once, as bytes, and stored unchanged.** Nothing on this path parses
  it; any reserialisation destroys the signature.
- **Decision: the endpoint lookup is deliberately outside the write transaction.** It is a read of
  nearly-static data, and holding SQLite's writer lock across it would serialise every ingest behind
  one read.
- **Rejected: an in-memory accept queue drained by a writer.** It would raise the number and remove
  the promise; the `200` is the product.
- Not covered: the registry ([B-07](B-07-endpoint-registry.md)), all five schemes
  ([B-09](B-09-signature-verifiers.md)), delivery ([B-10](B-10-delivery-sink.md)).

- AC: the scenarios of [feature-ingest](../features/feature-ingest.md) that do not depend on the
  registry pass, including the one where the second timer insert fails and **nothing** is stored.
  **Mostly done** — seven tests on both targets. The failure case is *not* automated: rigging the
  store to fail mid-transaction needs a seam the repository does not have, and saying so is better
  than a test that proves something else.
- AC: an event's stored bytes are byte-identical to what was sent, checked over a body containing
  CRLF, a BOM and non-ASCII — not over a tidy JSON fixture. **Done**, and with a NUL, `0xFF`, `0x7F`
  and an apostrophe as well — the four that a text-shaped storage path would change quietly.

## Closed 2026-09-15

`POST /hooks/{endpointId}` works end to end: a typed `@Resource`, the body read once as bytes and
never parsed, GitHub's HMAC-SHA256 checked in constant time against every active secret, and one
transaction writing the event with one delivery row per enabled subscriber. Verified through **real
HTTP** with the signature computed by `openssl` — an independent implementation, rather than our own
HMAC agreeing with itself:

| Request | Answer |
|---|---|
| genuine | `200 {"event":"1448a601…"}` — 1 event, 2 deliveries, body 47 bytes stored and read back identical |
| one byte appended to the body | `401 {"error":"signature invalid"}` |
| no signature header | `401 {"error":"signature missing"}` |
| unknown endpoint id | `404 {"error":"unknown endpoint"}` |
| 2 MB body against a 1 MiB limit | `413 {"error":"body too large"}` |

**The timer is the piece that is missing**, and it is missing upstream: the transaction writes the
event and its deliveries, and `chronik.schedule(tx, …)` joins them in that same transaction at
[B-03](B-03-chronik-sqlite-store.md). Nothing else in the design changes when it does.

**Three things this cost that were not in the plan.**

1. **`db.migrate()` called sqlx4k's own `migrate()`, not ours.** `ISQLite` has a member of that name
   — a member always wins over an extension — so the schema was never created, `user_version` stayed
   `0`, no error was raised anywhere, and the service started on an empty database. Renamed to
   `migrateSchema()`. tracy's is called `migrateDb`, which now reads as deliberate rather than
   arbitrary.
2. **A body cannot be bound as a parameter.** sqlx4k's `Statement` renders values into SQL text
   rather than preparing them, so the body is written as a hex blob literal (`X'…'`) and read back
   through `hex()`. Sixteen possible characters means nothing in a webhook body can end the literal
   early; the price is that the SQL text is twice the body while the insert runs, which is why the
   size limit is checked *before* the read.
3. **Kotlin/Native forbids commas in backticked test names**, which is in the gotcha table of the
   skill and was met anyway. The suite compiles on the JVM and fails on native, so a JVM-only run
   would not have found it.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/ingest/`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/contract/IngestResource.kt`
