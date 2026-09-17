---
id: xyk-server
title: xyk-server — the ingest, delivery and journal binary
type: service
repo_url: https://github.com/youndie/xyk
module: server
tech_stack: [Kotlin/Native, Ktor CIO, sqlx4k/SQLite, chronik, kore, Koin]
owner: unassigned
depends_on:
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
| `server/src/commonMain/kotlin/io/github/youndie/xyk/sink/EventSink.kt` | the second-destination port, the envelope, and why the body is not on it |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/sink/QueuedEventSink.kt` | the measurement arm: publish returns before the acknowledgement, close drains |
| `server/src/variants/with-kafka/kotlin/io/github/youndie/xyk/sink/EventSink.native.kt` | the kafkakn-backed sink, compiled only where kafkakn publishes a variant |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/Modules.kt` | Koin modules; the storage module is the only place a driver is named |
| `docker/native.Dockerfile` | the runtime image on `distroless/cc-debian13`; the glibc pairing is written there |
| `dev/shutdown-check.sh` | asserts the stop order against the real image, and that the journal was folded away; fails on purpose under `STOP_TIMEOUT=0` |

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
stop order is: announce not-ready → drain the engine → stop the delivery workers, the sweeps and the
health checks → close the pool → take the last checkpoint. A webhook that was answered `200` and not
yet delivered is exactly what that order protects.

**Three things about that order were wrong until 2026-09-16, and each was found by measuring rather
than by reading.** The symptom was one line on a GitHub runner — `RELEASE_CONSUMERS
DEADLINE_EXCEEDED in 3.000228711s`, with a 2.006354202s pool close behind it — which took `make
build` red on `main`. It reproduces on the build machine under `--cpus 0.5`, in 3 rounds of 30, and
the rounds that miss it spend 3–31 ms: a wait on a lock, not a slow machine.

1. **Participants registered in one stage run concurrently.** kore's `runStage` launches all of them
   and joins; only the *stages* are ordered. So `consumer(a)` before `consumer(b)` ordered nothing,
   and `wal_checkpoint(TRUNCATE)` ran alongside `RejectionFlush.stop()`'s final write and the
   probes' `SELECT 1`. An order needed inside a stage is written as composition — one participant
   calling two things in sequence.
2. **Cancelling a loop is not stopping it.** Every `stop()` here cancelled its job without joining,
   so the participant returned while a statement was still in flight inside SQLite and the work
   escaped into the *next* stage. They join now. `HealthRegistry.stop()` in kore cancels without
   joining too, and the check is inside an FFI call cancellation does not reach, so the probes get a
   scope of their own and that scope is joined.
3. **The truncating checkpoint cannot run while the pool is open at all.** It waits for every other
   connection to the database, and the pool's own second connection — idle, holding nothing of ours
   — is enough to hold it off. This is the one that actually cost the deadline, and it is settled by
   an experiment rather than an argument: same image, same host, `--cpus 0.5`, **0 stalls in 20
   rounds at `XYK_SQLITE_POOL=1` against 5 in 30 at the shipping pool of two.** So the last
   checkpoint happens *after* `close()`, on a connection of its own (`lastCheckpoint`), when this
   process holds no other.

After all three: **0 stalls in 30 rounds**, the pool stage a flat 5–7 ms. And it is checked for
doing its job rather than for being quick — a 107 152-byte `-wal` at the moment of `SIGTERM` is gone
after it, with the frames moved into a database file that grew to match.

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
| Library | `io.github.youndie.kafkakn:kafkakn-core` | the optional Kafka sink; **snapshots only**, `linuxX64` and `jvm`, resolves from reposilite |
| Library | Koin | DI, wired from the first repository rather than the third |
| External | subscriber endpoints | arbitrary HTTP servers; their behaviour is the main source of retries |

## 5. Infrastructure and deploy

* **Image:** `ghcr.io/youndie/xyk`, published by `.github/workflows/publish.yaml` from
  **`docker/scratch.Dockerfile` with the curl engine** — 11 840 625 bytes, which is the number
  [B-23](../backlog/B-23-criterion-image-size.md) quotes and the one
  [B-22](../backlog/B-22-criterion-cold-start.md) measured a cold start on. Tags: `main` moves,
  `sha-<commit>` does not, and a release tag adds `X.Y.Z` and `X.Y`.
  `docker/native.Dockerfile` on `gcr.io/distroless/cc-debian13` is the **local** variant, 56 MB, and
  is what `make build` produces: its binary is linked outside and copied in, while the `scratch` one
  must link inside the image ([research §1.7](../research/research-architecture.md)).
* **Certificates:** present in `distroless/cc` and **verified by use**, not by unpacking:
  `XYK_TLS_PROBE=<url>` on a `with-curl` build makes one `GET` from inside the container and got
  `200`; with the bundle masked it fails with `Problem with the SSL CA cert` (B-17).
* **Health:** `GET /health/startup`, `/health/ready`, `/health/live` — three probes, not one
* **Version:** `GET /version` (generated by the kore plugin; `commit` reads `unknown` wherever the
  build context has no `.git`)
* **State:** one volume at `/data`; the database is the only state, and a pod without a volume loses
  every undelivered event when it moves.
* **Chart:** `charts/xyk`. Three probes rather than one route answering three questions;
  `strategy: Recreate` rather than a rolling update, because one SQLite file has one writer and a
  rolling update would start the second one against a volume the first still holds; the PVC carries
  `helm.sh/resource-policy: keep`, since `helm uninstall` is not a sentence anybody means as "delete
  the payloads"; and the bootstrap endpoint comes from a `Secret` rather than from values, because a
  secret in `values.yaml` is a secret in `helm history` too.
  **Validated rather than deployed:** every manifest applied with `--dry-run=server` against a live
  k0s API server in both shapes, and the container run with exactly the environment the chart renders
  — all three probes `200`, a signed webhook accepted. A full install was not completed because the
  test node's CNI could not give pods an address.
* **The chart carries what the delivery measurements made operational** (B-30, B-31), because the
  numbers that decide those outcomes were not settable from it. `deliveryMaxAttempts` is the length
  of subscriber outage the service survives without losing events — five attempts span fifteen
  seconds, each further one doubles that — and `heapBytes` is the GC ceiling the runtime cannot
  derive for itself. The memory limit is commented where it is chosen, because with delivery on it
  decides how long a pod lives rather than whether it fits.
* **Every number in the template goes through `int64` before `quote`.** Helm renders a large
  unquoted YAML number in scientific notation — `33554432` becomes `3.3554432e+07` — and the
  environment is strings. It cost a silently ignored `maxBodyBytes` once; the config parser refuses
  to start on an unreadable number now, and the template renders the integer either way. The check
  that catches this class is rendering the chart and reading the environment, not reading the
  template.

## 6. Local setup

```bash
./gradlew :server:linkDebugExecutableNative && XYK_DB_PATH=/tmp/xyk.db ./server/build/bin/native/debugExecutable/server.kexe
```

**The target is called `native` on every machine** — `macosArm64("native")` on the Mac,
`linuxX64("native")` on the build machine — so the source set is `nativeMain` and this path does not
change when the build moves. `XYK_DB_PATH` is required and the process refuses to start without it.

Nothing else has to be running: the subscribers are whatever is in the database, and `bench/delivery-sink.py`
*(B-10)* starts a local one that logs what it receives and can be told to be slow.

**Builds are not cheap and do not belong on a laptop.** A release link is minutes of LLVM, so
`make build` wants a machine with cores; `macos*` targets are the exception and have to be built on
macOS.

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
| `XYK_RETENTION_DAYS` | payload purge horizon in days; `0` keeps payloads for ever | no (**7**) |
| `XYK_KAFKA_BOOTSTRAP_SERVERS` | broker list for the optional sink; **unset means no sink at all** | no |
| `XYK_KAFKA_TOPIC` | the topic accepted events are published to | no (`xyk.events`) |
| `XYK_KAFKA_QUEUE` | records allowed to wait in front of the producer; **`0` ships**, above zero is a measurement arm | no (0) |

Endpoint secrets are **not** environment variables: they are rows, created through the journal's
admin routes and never readable back over HTTP — the API answers with a fingerprint.

## 7a. The volume is as sensitive as the secrets in it

**Endpoint secrets are stored unencrypted in the SQLite file, and so are the webhook payloads. Back
up and grant access to that volume exactly as you would to the credentials themselves.** This is a
decision rather than an omission (2026-09-16, research §3b).

Encrypting the column would need a key, and a key in the environment of the same process is readable
by anything that can read the process — so it defends against a stolen **volume** and not a stolen
**pod**, and the second is the likelier of the two in a cluster. What it would reliably produce is a
sentence in an audit that is true of the bytes and false about the threat.

Concretely, for whoever operates this:

* the PVC holds live credentials — an attacker with a copy of it can sign requests that xyk will
  accept, and read every payload xyk has kept;
* restrict access to it as you would to a secret store, and treat its backups the same way;
* `XYK_RETENTION_DAYS` (7 by default) bounds how much payload is in there, which is the one lever
  that reduces this exposure without changing the design;
* a deployment that needs more than operational protection needs an external secret store and a
  different design — that is a decision with its own cost, not a column type.

Rotation is the other half and it already works: several secrets can be active at once, so a
compromised one is retired by adding its replacement and disabling it, without an outage for whatever
is still signing with the old one ([feature-endpoint-registry](../features/feature-endpoint-registry.md)).

## 7b. The optional Kafka sink

Off unless `XYK_KAFKA_BOOTSTRAP_SERVERS` names a broker, and absent from the binary altogether on a
target kafkakn publishes nothing for — `main` prints which of the two it is at start-up rather than
letting a configured deployment publish silently into nothing.

What it publishes, per accepted event: the key is the event id, the value is a small JSON envelope
(`event`, `endpoint`, `receivedAt`, `bodyBytes`, `contentType`), and the headers carry the endpoint
id and the declared content type. **The body is not on the topic** — it is in `events`, behind the
retention horizon of section 7a, and a copy on a topic would outlive that horizon somewhere nothing
here can reach.

Three properties are deliberate and each costs something:

* **The row is committed before the publish is attempted.** An event that never reached the topic is
  therefore visible from outside as a row with nothing behind it. What it costs is the reverse case:
  a process that stops between the two leaves a row that never becomes a record, which is an outbox
  question and not one this service answers today.
* **`send` returns when the broker has acknowledged**, so the publish is part of the request that is
  waiting for it, not something left behind afterwards. What it costs is ingest latency; what it
  buys is that there is nothing in flight when the process is asked to stop.
* **A refused publish does not fail the request.** The event is stored and the sender was promised
  nothing about Kafka; a `500` would ask for a second copy of the webhook, which is worse than a
  missing record that says so on stdout with its event id.

### The queued arm, and why it exists

`XYK_KAFKA_QUEUE` above zero puts a bounded queue in front of the producer: `publish` hands the
record over and returns, and a coroutine of the sink's own calls the producer. **It is a measurement
arm rather than a deployment choice**, in the same sense as `xyk.outbound=noop` — the three
properties above are what this service ships, and this arm exists because something had to have the
other shape before half of the producer's contract could be measured at all.

The half in question: with the shipping sink a record is either inside somebody's `send` or finished,
so `close` never has anything to flush. With a queue it does, and `close` drains it rather than
discarding it. Twenty rounds of `SIGTERM` through each shape are kafkakn's B-19 and B-23.

It also makes a second kind of loss possible, and the two wear the same shape from outside — a row in
`events` with nothing on the topic behind it. So the sink **announces every event id immediately
before it asks the producer**, which is what lets a record the producer was asked for be told apart
from one the process stopped before ever reaching. The second is an outbox question — whether a
service should record its intent and reconcile later — and this service does not answer it.

The producer is closed in the release stage **after** the engine has drained, next to the delivery
workers, for the same reason they are there: a publish belongs to a request that was already
accepted. `close` flushes, so against a broker that is not answering it can take up to
`message.timeout.ms` (ten seconds) against a `releaseGroup` deadline of three — a shutdown that loses
nothing and overruns is a timing defect, and it is meant to be read as one rather than rounded.

## 8. Quirks

* **The outbound half leaks about 2 kB per delivery, and it is not ours to fix.**
  `ktor-client-curl` grows roughly that much of anonymous memory per request — measured outside this
  service entirely, in [`bench/curl-leak`](../../bench/curl-leak), where one client in a loop with
  no database and no server does the same thing and the same loop on the CIO engine is flat. Nothing
  in xyk's code, its allocator or its GC is involved: the heap is capped and the growth is outside
  it ([B-30](../backlog/B-30-delivery-memory-growth.md)). **So a memory limit on this container is a
  time budget rather than headroom** — at 60 rps, about ninety seconds at 64Mi, four minutes at
  128Mi, eight at 256Mi, then an OOM kill and a restart. Nothing is lost in one: an event is
  committed with its timer before it is answered, and a delivery in flight is retried by whoever
  takes the lease next. There is nothing to switch to — CIO speaks plain HTTP on Kotlin/Native and
  subscribers are `https` ([research §1.6](../research/research-architecture.md)) — and the two
  memory recipes that are applied, `MALLOC_ARENA_MAX=2` in the images and the optional
  `XYK_HEAP_BYTES` ceiling, roughly halve the slope without removing it.
* **A subscriber that is down makes that worse, not better**
  ([B-31](../backlog/B-31-subscriber-down.md)). The request is still made, and a failure costs more
  than a success: about 2 kB per delivered attempt against **14 kB refused and 60 kB timed out**. So
  the intuition that a gateway with nowhere to deliver idles is wrong in both halves. **And the
  limit decides which way it then fails:** at 64 MiB the behaviour is bimodal, both modes seen on
  the same image — one run OOM-killed inside thirty seconds, the other riding the limit for five
  minutes and delivering nothing, 3 442 events accepted with a `200` and still pending, because the
  limit starves the workers so fewer attempts are made. A bigger limit lets them work and is spent
  faster. Neither setting makes this go away; they are two failures to choose between.

* **`GET /version` reports `commit = unknown` when `.git` is absent from the build context**, which
  is the usual case behind a `.dockerignore`, and is what it reports today — any build whose context
  has no `.git` stamps it. Worse: a file git tracks but `.dockerignore` excludes reads as *deleted*
  inside the build, and the stamp then becomes `-dirty` permanently.
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
