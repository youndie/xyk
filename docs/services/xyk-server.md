---
id: xyk-server
title: xyk-server — the ingest, delivery and journal binary
type: service
repo_url: https://github.com/youndie/xyk
module: server
tech_stack: [Kotlin/Native, Ktor CIO, sqlx4k/SQLite, chronik, kore, Koin]
owner: unassigned
depends_on:
  - chronik-sqlite
  - subscriber endpoints (arbitrary HTTP, outside this repository)
publishes:
  - ghcr.io/youndie/xyk (image)
coordinates: none — this is an application, not a library
---

# xyk-server

> **Status: the skeleton exists (B-01, 2026-09-15); the features do not.** Files marked *(B-nn)*
> below are paths the backlog creates; everything else is in the tree and builds. The layout follows
> the portfolio's other native services (katcher, metrik), so that a reader who knows one knows this
> one.

## 1. Responsibility

It owns **the event** — the raw bytes of an inbound webhook, the headers needed to prove they are
genuine, and the verdict of that proof — and **the delivery attempt**: who it was sent to, when,
with what result, and how many times. Everything it promises follows from those two rows being
written in one transaction with the timer that will deliver them
([research §1.2](../research/research-architecture.md)).

What it deliberately does **not** do:

* **It does not transform payloads.** No mapping, no templating, no filtering on body content. A
  subscriber gets the bytes that arrived, and the subscriber's own signature checks — if it has any —
  still have something to check.
* **It does not fan out to queues.** No Kafka, no AMQP, no cloud bus. The subscriber list is HTTP
  endpoints in SQLite, because the smallest useful shape of this product is one container and one
  file.
* **It does not authenticate the operator.** The journal is protected the way the cluster protects
  it (forward-auth, an ingress rule, a network policy) — stated here so nobody assumes a login page
  exists.
* **It does not guarantee ordering.** Retries and backoff reorder deliveries by construction, and a
  promise of order would be a lie the storage cannot keep.
* **It does not deduplicate for the subscriber.** At-least-once is literal
  ([research §1.3](../research/research-architecture.md)); the event id is in the delivery headers
  so the subscriber can.

## 2. API contracts

* **Contracts:** typed `@Resource` classes in `server/src/commonMain/kotlin/io/github/youndie/xyk/contract/`
* **Route reference:** [endpoint-ingest](../api/endpoint-ingest.md),
  [endpoint-journal](../api/endpoint-journal.md), [endpoint-ops](../api/endpoint-ops.md)
* **Generated schema:** none, and not planned. Three route groups with fixed shapes do not pay for a
  generator; the endpoint documents are the reference, which is the reason they list every route
  including the ones an operator never sees.
* **Tiers:** decided at the mount, not inside a handler — `/hooks/**` is unauthenticated by
  definition (the signature *is* the authentication), `/health/**` and `/version` are open to the
  cluster, everything else is behind whatever the deployment puts in front of it.

## 2a. Code anchors

| File | What is there |
|---|---|
| `server/src/commonMain/kotlin/io/github/youndie/xyk/Main.kt` | `main`: the deadlines, the start order, and kore's stop sequence |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/Application.kt` | the Ktor module — the three installs, content negotiation, and every route mount with its tier |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/ServerConfig.kt` | typed configuration, `fromEnv()` and the `require` calls that refuse to start |
| `server/src/nativeMain/kotlin/io/github/youndie/xyk/Env.native.kt` | `actual fun readEnv` — Kotlin/Native has no `System.getenv` |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/db/Migrate.kt` | the statement list and `PRAGMA user_version`, run before the engine starts |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/health/XykProbes.kt` | the three gates and the checks behind readiness |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/ingest/IngestRouting.kt` | *(B-06)* `POST /hooks/{endpointId}` |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/verify/` | one verifier per scheme + the constant-time compare |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/DeliverySink.kt` | the `TimerSink`: one POST, one timeout, one attempt row |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/delivery/DeliveryWorkers.kt` | N `TimerWorker`s and their owner names |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/journal/JournalRouting.kt` | the page and its JSON companions |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/Modules.kt` | Koin modules; the storage module is the only place a driver is named |
| `chronik-sqlite/src/commonMain/kotlin/io/github/youndie/xyk/chronik/Sqlx4kTimerStore.kt` | the `TransactionalTimerStore` — see [chronik-sqlite](chronik-sqlite.md) |
| `docker/native.Dockerfile` | the runtime image on `distroless/cc-debian13`; the glibc pairing is written there |
| `dev/shutdown-check.sh` | asserts the stop order against the real image, and fails on purpose under `STOP_TIMEOUT=0` |
| `charts/xyk/values.yaml` | limits, probes, the volume |

## 3. How it is built

**One binary, two halves, one SQLite file.** The inbound half is a Ktor CIO server; the outbound
half is a set of chronik `TimerWorker`s polling the same database through the same pool. They are in
one process because the guarantee that makes the product — the event row and its timer committed
together — is a transaction, and a transaction does not survive being split across two processes.

**The order in `main` is load-bearing, and it is not the order the Ktor examples use.** The database
is opened and migrated *before* `embeddedServer(...)`, the server is started with `wait = false`,
and the stop sequence is kore's rather than `ApplicationStopping` — because on Kotlin/Native the
engine's stop steps run in the opposite order from the JVM, so the idiomatic place to close the pool
runs *before* the engine has drained ([research §1.10](../research/research-architecture.md)). The
stop order is: announce not-ready → drain the engine → stop the delivery workers → close the pool.
A webhook that was answered `200` and not yet delivered is exactly what that order protects.

**Verification happens on the raw bytes, before anything parses them.** The body is read once, as
bytes; it is verified; it is stored; and it is parsed only if some feature needs a field out of it.
Any framework convenience that reserialises the body destroys the signature
([research §1.4](../research/research-architecture.md)).

**The pool is two connections.** Not a throughput setting: each connection is a `sqlx-sqlite-worker`
thread with its own arena, and — more expensively — one more reader, and SQLite's automatic
checkpoint is PASSIVE, so it never truncates the journal while a reader is alive. With the journal
page reading while ingest writes, that window would never open
([research §1.8](../research/research-architecture.md)).

**Delivery concurrency is several workers, not a concurrent sink.** The sink must block until the
POST finishes: `deliver` throwing is the only retry signal chronik has
([research §1.3](../research/research-architecture.md)). So the sink enforces the per-attempt
timeout itself, and parallelism comes from workers with distinct `owner` values claiming separate
batches.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Database | SQLite via `io.github.smyrgeorge:sqlx4k-sqlite` | events, subscribers, deliveries, timers — one file |
| Library | `io.github.youndie.chronik:chronik-core` | the timer contract and the worker; **not yet published for native**, see [research §1.1](../research/research-architecture.md) |
| Library | `io.github.youndie:kore-core`, `kore-ktor` + `io.github.youndie.kore.build` | ordered shutdown, three probes, `/version`; resolves from reposilite, not Central |
| Library | `org.kotlincrypto.macs:hmac-sha2`, `org.kotlincrypto.hash:sha2` | HMAC-SHA256 for GitHub and Stripe |
| Library | `io.ktor:ktor-client-curl` | the only native engine that speaks HTTPS; carries its own static libcurl/OpenSSL |
| Library | Koin | DI, wired from the first repository rather than the third |
| External | subscriber endpoints | arbitrary HTTP servers; their behaviour is the main source of retries |

## 5. Infrastructure and deploy

* **Image:** `ghcr.io/youndie/xyk`, built from `docker/native.Dockerfile` on
  `gcr.io/distroless/cc-debian13`. The binary is linked on the runner and copied in — except in the
  `scratch` variant, which must link inside the image
  ([research §1.7](../research/research-architecture.md)).
* **Certificates:** present in `distroless/cc` and **verified by use**, not by unpacking:
  `XYK_TLS_PROBE=<url>` on a `with-curl` build makes one `GET` from inside the container and got
  `200`; with the bundle masked it fails with `Problem with the SSL CA cert` (B-17).
* **Chart:** `charts/xyk/`
* **Health:** `GET /health/startup`, `/health/ready`, `/health/live` — three probes, not one
* **Version:** `GET /version` (generated by the kore plugin; `commit` reads `unknown` wherever the
  build context has no `.git`)
* **State:** one volume at `/data`; the database is the only state, and a pod without a volume loses
  every undelivered event when it moves.

## 6. Local setup

```bash
./gradlew :server:linkDebugExecutableNative && XYK_DB_PATH=/tmp/xyk.db ./server/build/bin/native/debugExecutable/server.kexe
```

**The target is called `native` on every machine** — `macosArm64("native")` on the Mac,
`linuxX64("native")` on the Linux box — so the source set is `nativeMain` and this path does not
change when the build moves. `XYK_DB_PATH` is required and the process refuses to start without it.

Nothing else has to be running: the subscribers are whatever is in the database, and `dev/sink.sh`
*(B-10)* starts a local one that logs what it receives and can be told to be slow.

Builds and test runs go to the Linux box, not the Mac — `make build` there, or `wsl-run` from here.
The project is in `mutagen sync list` as of 2026-09-15.

## 7. Configuration

Every key is read in `server/src/commonMain/kotlin/io/github/youndie/xyk/ServerConfig.kt`, and the
required ones are checked in `fromEnv()` so the process dies at startup rather than serving traffic
it cannot verify. The list below is the shape, not a copy — the file is the truth.

| Key | Description | Required |
|---|---|---|
| `XYK_DB_PATH` | path to the SQLite file inside the volume | yes |
| `XYK_PORT` | listen port | no (8080) |
| `XYK_DELIVERY_TIMEOUT_MS` | per-attempt timeout, enforced inside the sink | no |
| `XYK_DELIVERY_WORKERS` | number of `TimerWorker`s | no |
| `XYK_STRIPE_TOLERANCE_SECONDS` | Stripe timestamp tolerance; `0` disables the check rather than tightening it | no (300) |
| `XYK_RETENTION_DAYS` | payload purge horizon in days; **`0` (the default) keeps payloads for ever** | no |

Endpoint secrets are **not** environment variables: they are rows, created through the journal's
admin routes, and the environment carries only the key that encrypts them at rest if that decision
is taken ([B-19](../backlog/B-19-secret-handling.md)).

## 8. Quirks

* **`GET /version` reports `commit = unknown` when `.git` is absent from the build context**, which
  is the usual case behind a `.dockerignore` — **and is what it reports today**, because the mutagen
  replica the build runs on carries no `.git` either. Worse: a file git tracks but `.dockerignore`
  excludes reads as deleted, and the stamp becomes `-dirty` permanently.
* **The stop transcript has seven stages, not six.** `RELEASE_TELEMETRY` sits between
  `RELEASE_POOLS` and `EXIT`. `dev/shutdown-check.sh` asserts the order of the six that matter
  rather than the exact set, so a new stage does not become a red build nobody caused.
* **The delivery worker swallows its own failures.** `TimerWorker.start` catches everything that is
  not a cancellation, reports it to `onWorkerFailure` and keeps polling. A broken store therefore
  looks like an idle service, not a crashing one — which is why the readiness check counts ticks
  rather than trusting the worker to die.
* **`PRAGMA synchronous` reaches one connection out of the pool.** Only `journal_mode` survives being
  sent through a pool; the rest have to be set per connection or they are true for one commit in N.
  Measured here: `{1, 2}` across three connections when sent the naive way.
  `pinSynchronousOnEveryConnection` runs at start-up and pins the pool **as it is then** — a
  connection closed and reopened later comes back on FULL.
* **The JVM driver can answer `SQLITE_BUSY` while opening a pooled connection**, because it runs
  `PRAGMA journal_mode` in its own connection factory. Native never did. The acquire in
  `db/Connections.kt` waits it out; `busy_timeout` cannot help, the failure is inside the driver.
* **A tick can be as long as `batchSize × timeout`.** Nothing in chronik bounds it; the sink's
  timeout does.
* **There is no `Last-Modified` for stored payloads** — `FileMetadata` on native has no modification
  time — so the journal computes an ETag from content.
