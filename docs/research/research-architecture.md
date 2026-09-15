---
id: research-architecture
title: xyk — architecture research
type: research
status: active
date: 2026-09-15
---

# Research: the architecture of xyk

xyk takes a webhook from anyone who sends one — GitHub, Telegram, Stripe, a payment provider nobody
has heard of — checks that it is genuine, writes it down before answering, and then delivers it to
whoever subscribed, with retries, per-attempt timeouts and a page that shows what happened to every
delivery. The niche is not "a webhook gateway": it is a **durable** one that fits in a container so
small that running one per project is not a decision anybody has to defend, and whose behaviour on
failure is a documented contract rather than a log line. The platform is Kotlin/Native, so the
comparison that decides whether the platform is fit for this is built in from day one: the same
scenario against a Go twin, in three columns.

This document records **verified facts** (read in code, in published artefact metadata, or in the
vendor's own documentation), **decisions taken**, and **risks**. Anything unverified is marked as a
hypothesis and says where it will be checked.

Nothing in this repository is built yet. Every code path named below is a path that the backlog is
about to create; the facts are read out of **other** people's artefacts, which is what "verified"
can mean on a greenfield.

---

## 1. Verified facts

### 1.1 chronik publishes nothing a Kotlin/Native binary can link against

The brief says to use [chronik](https://github.com/youndie/chronik). Today that is not a dependency
one can take.

| Fact | Where verified |
|---|---|
| The group holds five artifacts: `chronik-conformance`, `chronik-conformance-jvm`, `chronik-core`, `chronik-core-jvm`, `chronik-postgres` — no `-linuxx64`, no `-linuxarm64` | listing of `https://repo1.maven.org/maven2/io/github/youndie/chronik/`, fetched 2026-09-15 |
| `chronik-core` 0.1.0 publishes exactly three variants: `jvmApiElements-published`, `jvmRuntimeElements-published`, `metadataApiElements` | `chronik-core-0.1.0.module` at the same host |
| Only one version exists: `0.1.0` | the directory listing of `chronik-core/` |
| The source declares `kotlin("multiplatform")` with a single target, `jvm()`, and one dependency, `kotlinx-coroutines-core` | `chronik/chronik-core/build.gradle.kts` |
| The only store implementation shipped is Postgres over Exposed, and it is `kotlin("jvm")` | `chronik/chronik-postgres/build.gradle.kts` |
| The conformance kit — the thing that decides whether a new store is correct — is also `jvm()` only | `chronik/chronik-conformance/build.gradle.kts` |

**Consequence 1 — "use chronik" is an upstream change plus a store, not a line in a version
catalog.** Two things have to happen before xyk can compile against it: native targets on
`chronik-core` (and on `chronik-conformance`, or the store cannot be accepted), and a
`TransactionalTimerStore` over SQLite, which does not exist in any form.

**Consequence 2 — `chronik-core` is the cheapest KMP module in the portfolio to make native.** It
"knows no SQL and executes nobody's code" (its own `settings.gradle.kts` says so), its single
dependency publishes every target we need, and its tests are `kotlin("test")` + coroutines-test.
*Hypothesis:* declaring `linuxX64()`, `linuxArm64()` and `macosArm64()` is a build-file change with
no source change behind it. Checked at [B-02](../backlog/B-02-chronik-native-targets.md), by running
the build, not by reading it.

**Consequence 3 — the conformance kit is our acceptance criterion, so it crosses with the core.**
A SQLite store whose correctness is argued rather than run against the corpus is a store nobody
should trust with "never lost, never early".

**Consequence 4 — release order is fixed and one-directional.** chronik publishes first; xyk pins a
released version. The same rule already binds petich to chronik, and for the same reason: a
consumer that pins a coordinate which is not on Central is a green build and an uncompilable
dependant.

**Correction found while implementing B-02, 2026-09-15 (evening).** The first half of this is
answered and the second is not, and the difference decides what xyk can do this week.

| Fact | Where verified |
|---|---|
| `chronik-core` now declares `linuxX64()` beside `jvm()`, and `chronik-conformance` with it — no source change was needed, every line of both is in `commonMain` | `chronik/chronik-core/build.gradle.kts`, `chronik/chronik-conformance/build.gradle.kts` on branch `feat/linux-native-target`, commit `4864fca` |
| That branch is pushed and **not merged**: `origin/main` is still at `0533371` | `git log --oneline origin/main -1` |
| **Nothing is published.** Maven Central's group listing holds the same five directories and no `chronik-core-linuxx64`; `maven-metadata.xml` still reports `0.1.0` as latest and release | listing of `repo1.maven.org/maven2/io/github/youndie/chronik/`, re-fetched 2026-09-15 |
| The reposilite snapshot repository holds the same five and answers `404` for `chronik-core-linuxx64` | `reposilite.kotlin.website/snapshots/io/github/youndie/chronik/` |

So the hypothesis in Consequence 2 **held** — the targets were a build-file change with no source
change behind it — and Consequence 4 still stands in full: there is no coordinate for xyk to pin.
"It builds" and "it resolves" are different claims, and only the second one unblocks a consumer.

**What this changes in the plan:** [B-03](../backlog/B-03-chronik-sqlite-store.md) can start against
a locally published build (`publishToMavenLocal`, or an included build while the store is being
written), because the contract it implements is final either way. What it may **not** do is let that
local resolution reach `main`: a build that is green only on a machine where somebody ran
`publishToMavenLocal` is a build that is red for everyone else and nowhere says why. The pin on a
released version is therefore its own acceptance criterion rather than a follow-up.

And a chronik SQLite store is **not** coming from upstream: the released module set is still
`core`, `conformance` and `postgres`. Writing one here was the plan in D1 and remains it.

**Second correction, later the same day: published, and the last sentence above is wrong.**
`io.github.youndie.chronik:*:0.1.0.16` resolves from the reposilite snapshot repository, and the
module set grew by one.

| Fact | Where verified |
|---|---|
| `chronik-core` publishes metadata, `jvm` and **`linuxX64`** | its `.module`, variants listed |
| `chronik-conformance` the same three | its `.module` |
| **`chronik-sqlx4k-sqlite` exists** and publishes the same three | its `.module` |
| That module ships `SqliteTimerStore : TransactionalTimerStore` over a sqlx4k `Driver`, with `Transaction.asTimerTransaction()` to pass the caller's transaction in | its sources jar, unpacked: `commonMain/SqliteTimerStore.kt` |
| It ships **no DDL and opens no connection** — `chronikTimersSchema(table)` returns the statements as text for the application's own migration list | `commonMain/Schema.kt` |
| The native variant is `linuxX64` **only** — there is no `macosArm64` | the same `.module` files |

**Consequence 1 — D1's "write the store here" is withdrawn.** The store xyk was going to write now
ships upstream, implementing the contract §1.2 describes and carrying the transactional handle §1.3's
design depends on. [B-03](../backlog/B-03-chronik-sqlite-store.md) becomes adoption plus a
conformance run, which is a different size of task and a different risk: what has to be checked is
that *our* driver, pool and migration order satisfy it, not that the SQL is right.

**Consequence 2 — the schema stays ours, deliberately.** `chronikTimersSchema()` hands back text;
the migration list, its version number and its ordering remain xyk's. So the timers table becomes
migration v5 next to the existing four, and chronik never executes DDL — which is also what keeps
"migrations before the engine" (§ step 4 of the bootstrap skill) true.

**Consequence 3 — the delivery half cannot be compiled on the Mac at all.** With no `macosArm64`
variant, any module that touches chronik resolves only on Linux. This repository builds on the Linux
box by policy, so the cost is zero in CI and in the loop; it is not zero for anyone reading this who
expects `./gradlew build` to work locally, which is why it is written here rather than discovered.

**Consequence 4 — Consequence 4 above still binds, with one word changed.** The coordinate is on a
snapshot repository rather than Central. That is the repository this portfolio's other consumers
already use, and the pin is an exact version (`0.1.0.16`), not a moving `+`.

### 1.2 What chronik's contract actually demands of a store

Read in `chronik/chronik-core/src/commonMain/kotlin/TimerStore.kt` and `Chronik.kt`.

| Fact | Where verified |
|---|---|
| `TimerStore`: `findById`, `claimDue(now, leaseUntil, owner, limit)`, `hasDue`, `markFired`, `markFailed(id, retryAfter)`, `markDeadLettered` | `chronik-core/src/commonMain/kotlin/TimerStore.kt` |
| `TransactionalTimerStore` adds `insert(tx, timer)`, `reschedule(tx, id, dueAt)`, `cancel(tx, id)` — all taking a `TimerTransaction` | same file |
| `chronik(store, clock)` **refuses** a store that is not transactional, with a message explaining that a timer written outside the caller's transaction can go missing while the state change it belongs to commits | `chronik-core/src/commonMain/kotlin/Chronik.kt` |
| A timer is claimable when `state == PENDING && dueAt <= now && (lockedUntil == null || lockedUntil < now)` | `chronik-core/src/commonMain/kotlin/Timer.kt` |

**Consequence 1 — this is exactly the guarantee xyk needs, and it is the reason to take chronik at
all.** "The event row was committed but the delivery was never scheduled" and "the delivery was
scheduled but the event row rolled back" are the two ways a webhook gateway silently drops traffic.
A store that writes the timer inside the same SQLite transaction as the event row makes both
impossible by construction rather than by care.

**Consequence 2 — the lease matters even in a single-process service.** It is not about competing
instances; it is about a process that died holding claimed work. Whatever `claimDue` writes has to
survive a `SIGKILL` and become claimable again at `lockedUntil`.

### 1.3 `TimerWorker` delivers a batch **sequentially**, and that decides our concurrency design

Read in `chronik/chronik-core/src/commonMain/kotlin/TimerWorker.kt`.

| Fact | Where verified |
|---|---|
| `tick()` claims a batch and then loops it, `await`ing `sink.deliver(...)` one timer at a time | `TimerWorker.tick` |
| Defaults: `leaseSeconds = 30`, `pollInterval = 1.seconds`, `batchSize = 50`, `maxAttempts = 5`, `baseBackoffSeconds = 1`, `maxBackoffSeconds = 300` | `TimerWorker` constructor |
| Backoff is `base * 2^(attempt-1)`, clamped to `maxBackoffSeconds`, with the exponent coerced into `0..40` | `TimerWorker.backoffSeconds` |
| `store.markFired(id)` runs **after** `sink.deliver` returns | `TimerWorker.tick` |
| A delivery that throws is counted as an attempt; at `maxAttempts` the timer is dead-lettered instead of retried | `TimerWorker.recordFailure` |
| `start(scope)` swallows non-cancellation exceptions from `tick` into `onWorkerFailure` and keeps polling | `TimerWorker.start` |

**Consequence 1 — one slow subscriber blocks every delivery behind it in the same tick.** With the
default batch of 50, a tick's worst case is `batchSize × per-attempt timeout`. The per-attempt
timeout is therefore not a nicety; it is the only thing bounding head-of-line blocking, and it has
to be enforced **inside** the sink, because the worker has no timeout of its own.

**Consequence 2 — fan-out comes from several workers, not from a concurrent sink.** A sink that
returned before the POST completed would break the retry contract: `deliver` throwing is the only
signal the worker has. So parallelism means N `TimerWorker`s with distinct `owner` values, each
claiming its own batch — which the lease makes safe. How many is a measurement, not a guess
([B-14](../backlog/B-14-delivery-worker-count.md)).

**Consequence 3 — delivery latency has a floor of about `pollInterval`.** One second, by default,
between a webhook being accepted and the first delivery attempt starting. That is a property of the
design and belongs in the product's promise, not in a bug report.

**Consequence 4 — at-least-once is literal.** A crash between `deliver` returning and `markFired`
committing re-delivers on the next claim. The journal must show attempts rather than a single
"delivered" flag, and the subscriber contract has to say the words "be idempotent, here is the
event id to dedupe on".

### 1.4 The three signature schemes named in the brief, read from the vendors' own documentation

All three fetched 2026-09-15.

| Sender | Header | Value | What is signed | Where verified |
|---|---|---|---|---|
| GitHub | `X-Hub-Signature-256` | `sha256=<hex>` | the raw body, HMAC-SHA256 with the webhook secret | docs.github.com — *Validating webhook deliveries* |
| Stripe | `Stripe-Signature` | `t=<unix>,v1=<hex>[,v0=<hex>]` | `"<t>" + "." + raw body`, HMAC-SHA256 with the endpoint's `whsec_` secret | docs.stripe.com — *Receive Stripe events*, "Verify webhook signatures manually" |
| Telegram | `X-Telegram-Bot-Api-Secret-Token` | the secret itself, 1–256 chars of `A-Z a-z 0-9 _ -` | nothing — it is a shared secret echoed back | core.telegram.org — Bot API, `setWebhook` |

Stripe's page adds three rules that are part of the contract rather than advice: ignore every scheme
that is not `v1` (a downgrade defence), compare in constant time, and reject a timestamp outside a
tolerance — the official libraries use **5 minutes**, and a tolerance of `0` disables the check
rather than tightening it. Several `v1` values can be present at once while a secret is being
rolled, for up to 24 hours.

**Consequence 1 — the raw bytes are the subject, and nothing may touch them before verification.**
Any parse-then-reserialise changes whitespace or key order and destroys the signature; Stripe's
troubleshooting page says this is the most common failure, with a list of frameworks that do it by
default. So ingest reads the body as bytes, verifies those bytes, stores those bytes, and parses
only afterwards and only if a feature needs it.

**Consequence 2 — a verifier takes a whole request, not `(body, secret)`.** The three shapes differ
in kind: a prefixed digest, a timestamped payload with a tolerance and a set of candidate
signatures, and a plain secret comparison. An interface narrower than "headers + raw body + now"
cannot express Stripe.

**Consequence 3 — Telegram's check is not a signature and the journal must not call it one.** It
proves the sender knows a secret; it says nothing about the body. The stored record names the scheme
that passed, so a later reader can tell how much the "verified" badge is worth.

**Consequence 4 — the clock is part of the contract.** A skewed host rejects good Stripe deliveries,
and the rejection has to be distinguishable from a bad signature in both the response and the
journal, or the operator debugs the wrong thing.

### 1.5 HMAC-SHA256 on Kotlin/Native needs no cinterop — this portfolio already ships it

| Fact | Where verified |
|---|---|
| `org.kotlincrypto.macs:hmac-sha2` and `org.kotlincrypto.hash:sha2` (0.8.0) are consumed from `commonMain` by a module that targets `jvm, linuxX64, macosArm64, iosArm64, iosSimulatorArm64, iosX64` | `s3kn/s3-sigv4/build.gradle.kts` |
| The same group is used by the SMTP client's SASL module for HMAC-MD5/SHA1 | `kmp-smtp-client/smtp-sasl/build.gradle.kts` |
| katcher takes the group through a published version catalog rather than a version pin | `katcher/settings.gradle.kts` |

**Consequence — the whole verification path is pure Kotlin.** No OpenSSL cinterop, no `dlopen`, and
therefore nothing that a `FROM scratch` image would have to be taught about (unlike `iconv`, §1.7).
The ingest half — which is where the declared throughput criterion lives — has no native library
dependency at all.

*Hypothesis:* the library ships no constant-time comparison helper, so we write and test one. It is
a fixed-time byte compare over equal-length arrays; the test that matters is that it **is** called
on every path, which is a review item rather than a timing test. Address:
[B-09](../backlog/B-09-signature-verifiers.md).

### 1.6 HTTPS out of a Kotlin/Native binary means `ktor-client-curl`, and the klib carries its own OpenSSL

Verified on Ktor 3.5.2 by unpacking the artifact from Maven Central (2026-08-17; the finding is
reused here rather than re-derived, and the address is the artefact, not a memory).

| Fact | Where verified |
|---|---|
| `ktor-client-cio` on `linuxX64` cannot do TLS: `ktor-network-tls` on native is a stub, `error("TLS sessions are not supported on Native platform.")` | the `ktor-network-tls` native source in the published sources jar |
| The curl engine's cinterop klib declares `staticLibraries.linux=libcurl.a libnghttp2.a libssl.a libcrypto.a` and physically contains them (~16 MB unpacked) under `default/targets/linux_x64/included/` | `ktor-client-curl-linuxx64-3.5.2-cinterop-libcurl.klib` |
| Inside 3.5.2: libcurl 8.20.0, OpenSSL 3.6.3. From the system only `-lz` is needed | `strings` over `libcrypto.a` from that klib |
| The engine runs all its I/O on one `newSingleThreadContext("curl-dispatcher")` | the engine's native source |
| Root certificates come from the system path `/etc/ssl/certs/ca-certificates.crt`; the klib carries none | the engine's config defaults (`caInfo` / `caPath`) |

**Consequence 1 — the kill criterion has a cheap, early experiment.** The brief's kill condition is
"if the curl dependency makes even the incoming half impossible to link statically". The incoming
half (Ktor CIO **server** + kotlincrypto) does not involve curl at all, so the question is whether
adding the client engine breaks a static link of the same binary. That is one build, twice, before
any feature work: [B-05](../backlog/B-05-static-link-probe.md).

**Answered 2026-09-15, and the answer is no — the kill condition did not fire**
([measurements-2026-09-15/link-probe.md](measurements-2026-09-15/link-probe.md)). All four builds
linked, and the three native ones that were run served `/health/ready` and stopped through the full
transcript. The engine-less binary is 9 662 448 bytes dynamic and **10 608 480 static**; with the
engine it is 18 945 736 and **19 425 640**.

The interesting number is not the one the brief asked about. **The engine costs 8 817 160 bytes in a
static link — it nearly doubles the binary** — and the cost is unavoidable by construction: libcurl,
libnghttp2, libssl and libcrypto live inside the engine's own cinterop klib as static archives, so
there is no shared library to move into the image instead. `libz` is the only thing the system
supplies, and it appears in the dynamic section of exactly that arm.

**So the boundary this experiment found is a size boundary, not a linkability one**, and it lands on
criterion 3 rather than on the kill condition — see §1.12 and
[B-23](../backlog/B-23-criterion-image-size.md).

**Consequence 2 — CA certificates are a deployment fact, not a library one.** On `scratch` there is
no `/etc/ssl/certs`, and the failure looks like a connection error rather than a missing file. Either
copy the bundle in or set `caInfo` explicitly, and prove it with an outbound request from inside the
image.

**Consequence 3 — delivery concurrency has a ceiling that belongs to the engine.** One dispatcher
thread for all curl I/O means that above some number of in-flight deliveries, adding workers buys
nothing. Measure it; do not assume a number.

**Consequence 4 — an OpenSSL CVE is closed by bumping Ktor**, not by patching the image. Nothing in
the image reveals the version; it is inside the klib.

### 1.7 A statically linked Kotlin/Native binary is not self-contained: `iconv` is loaded with `dlopen`

Measured on katcher (2026-09-13/14), a service close in shape to xyk: Ktor on Kotlin/Native
rendering HTML pages out of SQLite.

| Fact | Where verified |
|---|---|
| Ktor's charset layer on native **is** glibc `iconv`; `encodeURLParameter` goes through it, so every rendered page does. glibc has no built-in converters — even UTF-8 arrives from a gconv module loaded by `dlopen` | `nm` / `strings` over the linked binary; controls: the same binary works in `ubuntu:24.04` and fails there after `rm -rf .../gconv` |
| An image with the binary alone starts, serves static files, answers `401`, and returns `500` on the first rendered page: `Failed to open iconv for charset UTF-8 with error code 22` | the katcher image smoke run |
| The minimum set beside the binary, taken with `strace -e trace=openat` rather than reasoned: `/etc/ld.so.cache`, the loader, `libc.so.6`, the **whole** gconv directory (glibc picked `UTF-16.so` to convert UTF-8), and — as insurance nothing currently reads — `/usr/share/zoneinfo` | the same run |
| Result on katcher: **9 570 311 bytes to pull** on `scratch` against 15 542 820 on `distroless/cc` | `docker save`, one host |
| Curating a single gconv module instead of the directory would have saved 2 811 555 bytes and risks a `500` on an unusual `charset=` | the same comparison |
| Static linking makes the **binary** about 0.9 MB larger; the saving is the base image disappearing | same source, same compiler, two link modes |
| `docker image inspect .Size` means the compressed size on the containerd snapshotter and the uncompressed one on overlay2 — 9 569 623 against 27 918 036 for one image | the same image on WSL and on a GitHub runner |

**Consequence 1 — the 10 MB image criterion is attainable and has almost no margin.** katcher's
working `scratch` image is 9.57 MB to pull, and katcher does **not** link an HTTP client. xyk does,
and the static libcurl + OpenSSL it links are the largest unknown in this document.

**Consequence 2 — every image number in this repository says "to pull", says which host measured it,
and is produced by `docker save | wc -c`** rather than by a field whose meaning changes with the
storage driver.

### 1.12 The first numbers from this repository (B-01, 2026-09-15)

The skeleton exists and runs, so the figures above about *other* services now have a local
counterpart. Measured on the Linux box (Ubuntu 24.04, glibc 2.39), release `linuxX64`:

| Fact | Where verified |
|---|---|
| The binary is **9 662 208 bytes** — Ktor CIO server, sqlx4k, kore, Koin, **no HTTP client** | `./gradlew :server:linkReleaseExecutableNative` |
| The image on `gcr.io/distroless/cc-debian13` is **14 163 968 pull bytes** | `docker save xyk:dev \| wc -c`, one host |
| Three probes and `/version` answer from inside that image; `docker stop` produces the full ordered transcript | `dev/shutdown-check.sh`, with `STOP_TIMEOUT=0` as its positive control |
| The transcript has **seven** stages, not six: `RELEASE_TELEMETRY` sits between `RELEASE_POOLS` and `EXIT` | the same run |

**Consequence 1 — criterion 3 is already missed by 4.2 MB, before the HTTP client.** The 10 MB line
was set against a comparable service that fits in 9.57 MB on `scratch`; this is the same shape of
service on a base image, and the base is most of the difference. So `scratch`
([B-18](../backlog/B-18-scratch-image.md)) stops being the optional last few megabytes it was
written as and becomes the only route to the criterion — which makes
[B-05](../backlog/B-05-static-link-probe.md), the static-link probe, the item everything else waits
on rather than a curiosity.

**Consequence 2 — the binary is the budget now, not the base image.** 9.66 MB of binary against a
10 MB image ceiling leaves nothing for a base, which is the arithmetic behind Consequence 1. What
the curl engine adds to that number is the single measurement that decides whether the criterion is
reachable at all.

**Consequence 3, measured the same day (B-05): the engine takes the binary to 19 425 640 bytes
static.** The criterion is 10 MB of *pull* bytes, which is compressed, so this is not yet a verdict —
a comparable service compressed a 16.5 MB static binary plus the gconv tree into 9 570 311 pull
bytes. But the headroom that comparison had is gone: this binary is 2.9 MB larger than that one
before a single feature is written, and every feature adds to it. The honest statement of the
position is that criterion 3 is now **open and unlikely**, it can only be settled by building the
`scratch` image and running `docker save` ([B-18](../backlog/B-18-scratch-image.md)), and the
fallbacks in Risk 1 — curating gconv, or missing the line and saying so — are now the probable
outcome rather than contingencies.

### 1.13 Two traps in the storage layer, found by building on it (B-06, 2026-09-15)

| Fact | Where verified |
|---|---|
| `ISQLite` has a **member** called `migrate()` — sqlx4k's own, which runs `.sql` files from a directory. An extension of the same name never runs: the member wins, silently | the schema was absent and `user_version` was `0` after a clean start, with no error on either target |
| sqlx4k's `Statement` **renders** values into SQL text (`bind` collects, `renderNativeQuery` writes); there is no byte-array encoder in that path | `javap` over `sqlx4k-jvm-1.13.0.jar`: `Statement.bind(String, Object)` + `renderNativeQuery(Dialect, ValueEncoderRegistry)` |
| `asString()` is a member of `ResultSet.Row.Column`; `asLong()`, `asInt()` and `asByteArray()` are extensions in `impl.extensions` | the same jar |
| `transaction { }` takes a lambda with the `Transaction` as its **receiver**, not as a parameter | the compiler: "actual type is `suspend Transaction.(…) -> Unit`, but `suspend Transaction.() -> Unit` was expected" |

**Consequence 1 — a name collision with a library member is a silent no-op, and nothing in the
toolchain reports it.** Not a compile error, not a warning, not a runtime failure: the service
starts, answers its probes and has no tables. The only reason it was caught within the hour is that
the next thing built on top of it failed with "no such table". The lesson generalises past this
library: an extension function on a third-party type is a name that can be taken away later by an
upgrade, and the failure mode is behaviour, not a build break.

**Consequence 2 — the raw body is written as a hex blob literal and read back through `hex()`.**
There is no parameter to bind it to, and inventing an encoder through `ValueEncoderRegistry` would
put a hand-written escape on the one field an attacker controls completely. A hex literal is sixteen
possible characters, so nothing in a body can end it early, and SQLite stores a real BLOB. The price
is that the SQL text is twice the body while the insert runs — which is why the size limit is checked
before the body is read rather than after.

### 1.14 The gconv gotcha does not reproduce on this service (B-18, 2026-09-15)

§1.7 is inherited from another service in this portfolio: a static Kotlin/Native binary loads its
charset converters with `dlopen`, so a `scratch` image without the gconv tree starts, answers
`/health/ready` with `200`, and returns `500` on the first rendered page. It was taken here as a
fact. **It was then used as a negative control, and the control did not fire.**

| What was tried against a `scratch` image **without** gconv | Result |
|---|---|
| `GET /journal`, `GET /journal/{id}` | `200`, rendered |
| the full image smoke: create an endpoint, sign a webhook, read a timestamp out of the HTML | passed |
| query and path carrying percent-encoded non-ASCII (`%D1%84`) | `200` / `404`, same as the full image |
| an endpoint description of Cyrillic and Japanese, rendered back into the page and into JSON | `200`, the characters arrive intact |
| a JSON body with non-ASCII keys and `Content-Type: application/json; charset=utf-8` | `200` |
| the log, grepped for `iconv` and for exceptions | nothing |

The binary is genuinely static (`file`: "statically linked", `readelf -d`: "no dynamic section") and
it genuinely contains the machinery — `strings` finds `iconv_open` and
`/usr/lib/x86_64-linux-gnu/gconv` in it. **What is absent is a call site.** The other service reached
`iconv` through `encodeURLParameter`; nothing here percent-encodes anything into a page.

**Consequence 1 — the mechanism is real and its applicability is not inherited.** A gotcha copied from
a neighbouring repository is a hypothesis about this one ([research method](../README.md)), and this
one came back negative on every route this service has.

**Consequence 2 — the gconv tree stays anyway, and that is a decision rather than inertia.** It costs
**2 831 872 bytes** of the pull (8 369 664 with it, 5 537 792 without). What it buys is insurance
against a call site this service does not have *yet*: the day a redirect carries an encoded query or
a link is built with `encodeURLParameter`, the failure is a `500` in production that no test in the
suite would catch, and the image would have to be rebuilt to fix it. Two point eight megabytes
against a silent production failure is a trade worth making while the criterion is out of reach for
other reasons (§1.12, B-23).

**Consequence 3 — and this is the uncomfortable half: the image smoke test still has no working
negative control.** B-12 said the control for its render branch would arrive with B-18. It did not:
the image that was supposed to fail rendered fine. What the smoke test proves today is that the
pages render; what it has never been shown able to catch is an image that cannot render them. That
is written here rather than quietly forgotten, and the next candidate for such a control is a base
image genuinely missing something the binary needs — not this one.

### 1.8 What a 64 MiB limit means for Ktor on Kotlin/Native — and why the declared criterion is the one most likely to fail

Measured 2026-09-15 on a Ktor service with **no database at all**: one route, one JSON object,
2 000 rps, 200 connections, 512 MiB limit, ten runs per arm.

| Arm | Survived | Peak RSS |
|---|---|---|
| `fixedBlockPageSize=16` | 10/10 | 65.3 MB |
| `fixedBlockPageSize=16` + `MALLOC_ARENA_MAX=2` | 10/10 | 62.8 MB |
| `-Xallocator=std` | 10/10 | **39.3 MB** |
| `-Xallocator=std` + `MALLOC_ARENA_MAX=2` | **7/10** | **413.7 MB** |

Supporting facts, from other services and needed to read the table:

| Fact | Where verified |
|---|---|
| The Kotlin/Native allocator keeps a page per size class **per thread**; RSS follows the thread count, not the live heap, and no GC setting bounds it | katcher, `--memory=192m`: default 0/8 survivals at 56–68 MB at rest and 252–329 MB peak, against 8/8 and 22–26/47–62 with `fixedBlockPageSize=16` |
| `GC.targetHeapBytes` is a collection threshold, not a process limit: 4 MiB target still left 110 MB resident. Kotlin/Native does not read its cgroup limit; the JVM does, Go does | the memory-probe stand, three repeats, shuffled order |
| `-Xallocator=std` measured **worse** than 16 KiB pages on a service with SQLite on the request path — higher peak, lower throughput | katcher |
| glibc grants an arena per thread counting **host** cores, so `--cpus=1` on a 20-core runner still allows 160 | `smaps`: anonymous mappings of 6–12 MB on 64 MB boundaries |

**Consequence 1 — say it now rather than after the run: 64 MiB with 10/10 is below anything measured
so far for this stack.** The recommended arm peaks at 65.3 MB on a service that touches no database;
xyk has SQLite on the ingest path. The arm that would fit (39.3 MB) is the one that regressed on a
service with a database, and the combination that looks like "both optimisations" is a tenfold
regression with three kernel kills out of ten.

**Consequence 2 — the criterion stays as declared, and its measurement is designed to be
informative when it fails.** Four arms on xyk's own binary, ten runs each, interleaved, with a
positive control: the same image under a deliberately small limit **must** be killed, or the harness
cannot detect a failure and "10/10" means nothing. What comes out is either "the platform does this"
or a number that says how far off it is — and the second is the more interesting article.

**A first run on 2026-09-15 reported all four arms surviving 10/10 at 18 threads. It was retracted
the same day: its load generator never started** (§1.20), so it measured an idle process. The tell
was the thread count it printed and explained away — on this platform resident memory follows the
thread count, and 18 threads under a nominal 200 rps is not a weak stand, it is no requests.

**Re-measured with the generator running, and the prediction is neither confirmed nor refuted — but
it is close to being decided.** At **200 rps over 50 connections**, a tenth of the declared load:

| arm | survived | cgroup peak, kB | threads |
|---|---|---:|---:|
| `default` | **0/10 — killed every round** | — | — |
| `fixedBlockPageSize=16` (ships) | 10/10 | 45 112 – **65 536** | 85–146 |
| `fixed16` + `MALLOC_ARENA_MAX=2` | 10/10 | 42 120 – 56 188 | 49–117 |
| `-Xallocator=std` | 10/10 | **25 428 – 34 056** | 71–101 |

Control: 2 of 2 killed at 6 MiB. Threads reached 146 where the retracted run had 18, so the mechanism
this section is about is engaged for the first time.

**This reverses the table above on one point.** `-Xallocator=std` measured *worse* on a service with
SQLite on the request path elsewhere in this portfolio, which is the row directly above; on xyk it is
the best arm by a factor of two and the steadiest. The inherited fact is not overturned — it was
measured on a different service — but it no longer transfers, and shipping it here is now an open
question rather than a settled no.

**And the shipping arm survives with no margin:** one round peaked at exactly 65 536 kB, the limit
itself. Ten survivals at the edge of the limit, at a tenth of the load, is a result that points at
failure without demonstrating it. Full table and caveats in
[memory-64mib.md](measurements-2026-09-15/memory-64mib.md).

**Consequence 3 — the SQLite settings are not tuning, they are survival.** Pool of 2 connections,
`PRAGMA wal_checkpoint(TRUNCATE)` on a timer **and** on file size, and `walBytes` reported separately
because `page_count * page_size` does not include the journal. The failure mode is a cliff, not a
slope: in tracy the journal reached 931 MB beside a 187 MB database, reads got more expensive, the
in-flight request count grew, threads and arenas followed, and the process was killed on memory with
a small heap — at minute 33 with the checkpoint alone, at minute 65 without.

### 1.9 Ktor pieces that do not exist on the native target

| Fact | Where verified |
|---|---|
| `ktor-server-compression` is JVM-only — it does not resolve for native | metrik research §1.8 |
| `ktor-server-call-logging` is not published for native; `ktor-server-call-id` is | the same |
| `respondSource` holds the whole body in memory: 20 parallel downloads → 232 MB against a 256 MB pod | the same, fixed with a 64 KB `respondBytesWriter` loop |
| `SelectorManager` occupies a `Dispatchers.Default` worker forever; on a 2-core pod `delay` stops firing process-wide | metrik research §1.5 |
| `Dispatchers.IO` is `internal` on Kotlin/Native | the same |

**Consequence** — the journal page is compressed ahead of time or not at all; the request log is
written by hand on top of `call-id`; serving a stored payload back uses an explicit chunked loop;
and the selector gets its own thread, or the delivery worker's `delay` stops firing on a
single-core pod and the whole outbound half silently stalls.

### 1.10 Stopping in the right order is not free on Kotlin/Native

| Fact | Where verified |
|---|---|
| `EmbeddedServer.stop` runs its steps in the **opposite order** on Kotlin/Native and on the JVM, from the same source, and nothing reports it | kore's premise, reproduced: `ApplicationStopping` cut 48 in-flight requests on native and none on the JVM |
| In katcher this was literal: `SIGTERM` cancelled processing of reports already accepted with `202` while the engine kept accepting new ones | the katcher defect kore was adopted for |
| kore ships `io.github.youndie:kore-core`, `kore-ktor` and the plugin `io.github.youndie.kore.build`; katcher runs 0.1.4 | `katcher/gradle/libs.versions.toml` |
| They resolve from `reposilite.kotlin.website/snapshots` under the `io.github.youndie` group, **not** from Central | kore's own release notes; katcher's settings |
| `HealthRegistry.start(scope)` is called by nobody automatically — without it readiness answers `UNKNOWN` forever | kore B-41 |

**Consequence — xyk's accepted-but-undelivered webhook is precisely katcher's report queue.** An
ordered stop is a product requirement here, not hygiene: announce not-ready → drain the engine →
stop the delivery workers → close the pool. The transcript
(`SIGNAL / ANNOUNCE / DRAIN / RELEASE_CONSUMERS / RELEASE_POOLS / EXIT`, each `COMPLETED`) is what
gets asserted in CI, because "it compiled" says nothing about order.

### 1.11 The SQLite driver is two drivers

| Fact | Where verified |
|---|---|
| katcher pins `io.github.smyrgeorge:sqlx4k-sqlite` 1.13.0 | `katcher/gradle/libs.versions.toml` |
| sqlx4k is a Rust driver on Kotlin/Native and Xerial on the JVM; the JVM half refuses a pool larger than 1 against `:memory:`, and pinned to 1 it deadlocks when a transaction asks for a second connection | katcher's and metrik's test harnesses, the same eight tests moved between them |
| A `PRAGMA` sent through the pool reaches **one** connection; only `journal_mode` survives that, `synchronous` does not | the pragma probe |

**Consequence** — the test harness uses a file database in a temp directory from the start, the same
shape as production; and every pragma that matters is set per connection, not once through the pool.

**Confirmed here, 2026-09-15 (B-04), by our own probe rather than inherited.** Three concurrent
holders of a three-connection pool, each asked `PRAGMA synchronous;`: after
`pinSynchronousOnEveryConnection` every one answers `1` (NORMAL); with the pragma sent the naive way
through the pool the same probe answers `{1, 2}` — one connection changed, the rest left on SQLite's
default FULL. The second half is the control: without it the first proves only that a pool can hand
the same connection out three times.

**And a divergence between the two drivers that cost a flaky suite.** Holding several pooled
connections to one file at once makes the **JVM** driver fail with
`[SQLITE_BUSY] The database file is locked` *while creating* one of them: the error comes out of
sqlx4k's own `connectionFactory`, which runs `PRAGMA journal_mode` on every new connection, and that
pragma wants a write lock the siblings are holding. One `jvmTest` run in two; never once on
`linuxX64`. `busy_timeout` is no use — the failure is inside the driver, before a statement of ours
reaches the connection, and the URL takes only the four parameters SQLite defines for URI filenames.
The acquire waits it out instead (`db/Connections.kt`). Four consecutive JVM runs green afterwards,
which is evidence and not proof.

---

## 2. Decisions

### D1. chronik is adopted as a contract and an upstream change — *deviation from the brief*

Brief: "use chronik".
Decision: the same words, but the work order is (a) native targets on `chronik-core` and
`chronik-conformance`, published upstream; (b) a `TransactionalTimerStore` over sqlx4k/SQLite in
this repository, validated by the conformance kit; (c) xyk pins the released version.

Why:

- the artefact metadata in §1.1 says there is nothing to depend on today, and this was found by
  listing Maven Central rather than by assuming;
- vendoring chronik's sources was rejected: it forks the contract silently, and the conformance kit
  then tests something other than what we ship;
- writing our own retry loop and naming chronik in the README was rejected for the opposite reason —
  it is cheaper for a week and throws away the only thing that makes the delivery half checkable by
  someone who did not write it;
- the price is honest: **the delivery half is blocked on an upstream release.** Mitigated by the
  order of work — the ingest half, where the throughput criterion lives, does not depend on it, and
  the static-link and image questions are answered before either.

Escalating into chronik is open: it is our own library, and a refusal upstream would itself be a
result worth writing down.

### D2. Two halves, one binary, one database

In: Ktor CIO server → verify → **one transaction** writing the event row and the timer → `200`.
Out: N `TimerWorker`s → a curl-backed `HttpClient` → POST to the subscriber → an attempt row.

Rejected: two processes, one ingesting and one delivering. The transactional guarantee of §1.2 is
the product; splitting it across processes turns "committed together" into "queued and hoped for",
which is the defect the whole design exists to avoid.

### D3. The journal is server-rendered by the same binary — no client, and therefore no `screens/` layer

Rejected: a separate web client. It would double the image, add a build, and the only reader of the
page is an operator asking what happened to one delivery. How the page is put together lives in
[xyk-server](../services/xyk-server.md); the states it can be in live in
[feature-journal](../features/feature-journal.md), which is also where a future client would get its
contract from. This is a deliberate absence, not an omission: see the note in
[docs/README.md](../README.md).

### D4. The Go twin lives in this repository and is measured by the same harness, in the same run

Rejected: a twin in its own repository. A three-column table is only honest if both columns come out
of one script, on one host, with the arms interleaved; a twin that drifts turns the comparison into
two measurements taken on different days and compared by arithmetic.

The twin's scope is **exactly the ingest path** — read the body, verify a signature, insert, answer
`200` — on `net/http` and a SQLite driver. Rejected: a full port. The column that is worth having is
the platform floor under identical work; a full port would measure how well the author writes Go.

### D5. `FROM scratch` is a target, not a premise

Order of work: `distroless/cc` first, because it is one line and about 65 MB, and its certificates
are already there; `scratch` only if the image criterion still needs the remaining ~6 MB. And
`scratch` carries a condition that is easy to miss: the binary must be **linked inside the image**,
because the `libc.so.6` copied beside it has to be the same glibc build the `libc.a` was linked
against — which trades minutes of build time for those megabytes.

### D6. The criteria are recorded before the code, with the measurement that decides each

They are in [backlog.md](../../backlog.md) verbatim, with the arm, the host, the number of runs and
the positive control for each. One of them — 64 MiB — is named in §1.8 as the one the existing
evidence says will fail. Declaring that now is the point: a criterion that is quietly relaxed after
the run measures nothing.

---

## 3. Risks and open questions

**Risk 1. The static libcurl + OpenSSL blows the 10 MB image budget.** katcher fits in 9.57 MB
*without* an HTTP client; the curl klib carries 16 MB of static archives, of which an unknown
fraction survives `--gc-sections`. Mitigation, in order, and each with a price stated when it is
taken: (a) measure the linked binary with and without the engine at
[B-05](../backlog/B-05-static-link-probe.md), before anything is built on top; (b) curate the gconv
directory down — 2.8 MB on katcher, at the cost of a `500` on an unusual `charset=`; (c) accept
`distroless/cc` and miss the criterion by about 6 MB, saying so; (d) ship the curl engine behind a
build flag and deliver over plain HTTP to in-cluster subscribers only — which changes the product
and would be a different article.

**Risk 2. 64 MiB is below the measured floor of this stack (§1.8).** Mitigation is the shape of the
measurement, not a hope: four arms on xyk's own binary, ten runs each, interleaved, plus a positive
control that must be killed. Open: if the answer is "the platform does not do this", the number that
matters is *how far off*, and whether it is threads (the allocator) or the journal (SQLite).

**Risk 3. Head-of-line blocking in the delivery worker (§1.3).** Mitigation: a per-attempt timeout
enforced inside the sink, so a tick is bounded by `batchSize × timeout`; several workers with
distinct owners; and a subscriber in the harness that deliberately sleeps past the timeout, so the
bound is measured rather than asserted.

**Risk 4. WAL growth under a journal page that reads while ingest writes.** This is tracy's loop
exactly, and it does not appear in a short run — it appeared at minute 33 there. Mitigation: a pool
of 2, `wal_checkpoint(TRUNCATE)` on a timer and on size, `walBytes` in the health response, and a
soak long enough to cross the point where the cliff was found. The load generator must be
`constant-arrival-rate` with honest `dropped_iterations`: a closed loop physically cannot reproduce
"slower → more in flight", because a sagging server receives less.

**Risk 5. Clock skew silently rejects genuine Stripe deliveries.** Mitigation: the tolerance is
configuration, the rejection reason is a distinct code in the response and a distinct column in the
journal, and the health response reports the host's clock so an operator can see the skew rather
than infer it.

**Risk 6. At-least-once means subscribers see duplicates (§1.3).** Mitigation is documentation plus
data: the delivery carries the event id and the attempt number in headers, and the subscriber
contract says to dedupe on the event id. Any promise stronger than this would be a lie the code
cannot keep.

**Risk 7. The stored payload is the sensitive thing.** A Stripe event carries customer data, and xyk
stores raw bodies by design (§1.4). Mitigation: secrets come from the environment and are never
logged or rendered; the journal shows *which* secret verified a request, never its value; and
payloads are purged on a retention schedule. Open question below.

**Open question 1.** Will chronik take native targets, and at what cost to its own gate? The
Postgres module's tests need Docker and stay JVM-only either way. Settled by
[B-02](../backlog/B-02-chronik-native-targets.md), as an issue and a pull request on
`youndie/chronik` — not by a decision taken here.

**Open question 2.** How many delivery workers, and does the single curl dispatcher thread (§1.6)
make the answer small? Hypothesis: the ceiling is the dispatcher, not the worker count, and two
workers reach it. Settled at [B-14](../backlog/B-14-delivery-worker-count.md).

### 1.18 Throughput collapses at four visible cores (B-20, 2026-09-15)

Same host, same binary, same generator, four interleaved rounds against a route that touches no
database — full table and the five controls in
[cores-and-throughput.md](measurements-2026-09-15/cores-and-throughput.md):

| container CPU | what the runtime sees | rps |
|---|---|---|
| `--cpus=4` (quota) | 20 cores | 3 871 – 3 954, steady |
| `--cpuset-cpus=0-3` | 4 cores | **42 – 378**, unstable |
| `--cpuset-cpus=0-1` | 2 cores | 1 353 – 1 670, steady |

The Go twin, on the same host and route: 21 617 – 47 503 rps.

**Consequence 1 — a throughput number for this stack is meaningless without the core count the
runtime could see.** Not the quota, not the node: `nproc` as observed inside. The same binary with
the same four cores of CPU differs by an order of magnitude depending on which of the two it was
given.

**Consequence 2 — it lands on the product's premise.** "Small enough to run one per project" means
one or two cores; a Kubernetes limit is a quota, so the *good* configuration is the common one — but
a small dedicated node, or a `cpuset`-pinned deployment, is the bad one. Sizing this service from a
single measurement would be wrong by up to ninety times.

**Consequence 3 — the criterion in `backlog.md` needs a condition it did not have.** 2 000 rps at 200
connections is answerable only once "on a host that shows the runtime N cores" is part of the
question. That is a change to a declared criterion, so it is written here rather than quietly applied.

**The mechanism is not established, and the obvious suspect does not fit.** Ktor's `SelectorManager`
occupying a `Dispatchers.Default` worker would predict two cores being worse than four; two is
better. Next experiment named in the measurement file.

**Correction, same day — part of that table was my harness, and the rest still stands.** The
generator was not pinned: k6 was free to run on the very cores the subject was confined to, which is
the one thing `bench/run.sh` exists to refuse, gone around because these were "only diagnostics". With
the two pinned apart the catastrophic tail disappears and what remains is:

| | |
|---|---|
| Kotlin, 4 visible cores, generator pinned away | **355 – 816 rps**, four interleaved rounds, spread 2.3× |
| Kotlin, 12 visible cores | **1 812 – 2 268 rps**, spread 1.25× |
| Go, 4 visible cores, same rounds | **55 953 – 56 972 rps**, spread 1.02× |
| Kotlin on a separate four-core host, generator on another machine | 410 rps |

**That is 102× between the two arms on four cores**, on a route that touches no database — and the
Kotlin arm's own spread is 2.3× where the Go arm's is 1.02×, so at small core counts a single
measurement of this stack is a sample rather than a property.

**Consequence 4 — a rule that only lives in a script is a rule for the script.** The harness refuses
same-host runs; the person driving it ran a dozen of them by hand because each felt like a quick
check. Every one of those numbers had to be thrown away or re-qualified. The lesson is not "be
careful", it is that the pinning belongs in the thing that starts the subject, so that a diagnostic
cannot be taken without it.

**What the correction does not touch:** the Go column, flat at ≈50 000 rps across core counts, and
the two-orders-of-magnitude gap to the Kotlin arm on the same host and route.

### 1.19 A container's memory is the cgroup's number, not the process's (B-21, 2026-09-15)

| Fact | Where verified |
|---|---|
| Under an 8 MiB limit the process reported `VmHWM` of **9 600 kB** — more than the limit it was held under | `/proc/<pid>/status` against `docker inspect .HostConfig.Memory` |
| The kernel's own accounting for the same container: `memory.peak` **8 192 kB**, `memory.events` `oom_kill 0` | `/sys/fs/cgroup/.../memory.peak` |
| The limits are real: a container told to allocate 200 MB under 24 MiB is killed with `exit=137` | the synthetic control |

**Consequence 1 — `VmHWM` counts things the cgroup does not charge, and the tell is that it exceeds
the limit.** A ten-megabyte binary is mapped into the process; those pages show up in the process's
high-water mark and are reclaimed rather than charged when memory is tight. Measuring survival with
it would have mis-stated every arm of B-21.

**Consequence 2 — and the number it revealed is the interesting one.** Measured properly, the service
**starts and serves inside 8 MiB**: Ktor CIO, sqlx4k/SQLite, five verifiers, the registry and a
rendered journal page, all of it. At that limit the peak sits exactly on the limit as the kernel
reclaims mapped pages to fit; given room (64 MiB) it settles at about 15 MB. It does not come up at
6 MiB, which is what makes 6 the control.

**Consequence 3 — two control limits were guessed and both survived.** 24 MiB and then 8 MiB were
picked as "obviously too small"; the harness refused to proceed both times, which is exactly what a
positive control is for. The lesson is not about the numbers: **a control has to be found by
measurement like anything else**, and a harness that stops when its control survives is worth more
than one that produces a table.

### 1.22 A row count is a condition of a bug report, and this one was missing (B-25, 2026-09-16)

| Fact | Where verified |
|---|---|
| At **500** events, `/journal` under 50 concurrent readers answers **471 rps**, 0 failures | `bench/journal.sh`, first run |
| At **39 000** events, the same load completes **not one request** in 20 s | the same harness, k6: `No script iterations fully finished`, `data_received: 0 B` |
| `/api/events` — the same query, no HTML — collapses identically | the two arms of one run |
| One page over 28 781 events costs **1 568 ms**; SQLite plans two of the three subqueries as `SEARCH d USING INDEX deliveries_state (state=?)` | `EXPLAIN QUERY PLAN` and a timed execution against the seeded file |
| Adding `deliveries(event_id, state)` takes the same page to **1.7 ms** on the same data | the same session, before and after |
| A pool of **8** is 3.5× worse than 2 on that page (294 rps against 1 053) | the two pool arms after the fix |

**Consequence 1 — a symptom recorded without its conditions is a report that cannot be reproduced.**
B-25 held the latency, the failure rate, the host and the concurrency, and not the one number that
decides the behaviour: how many rows were in the table. The first attempt to reproduce it found a
healthy page and nearly closed the item as unreal. **Whatever makes the subject slow is part of the
subject** — for a query that is the data, and a bug report about a query says how much of it there
was.

**Consequence 2 — an index that exists is not an index that is used.** `deliveries(event_id)` was
there the whole time and the plan ignored it for the two subqueries that also filter on `state`,
choosing the `state` index instead — which in a healthy service selects most of the table. The tell
is only visible in `EXPLAIN QUERY PLAN`; from the outside it is indistinguishable from "the page is
slow". A composite covering both columns is what the query was always asking for.

**Consequence 3 — the second suspect was tested rather than assumed, and it would have made things
worse.** Raising the pool was the other candidate fix and it is 3.5× *slower* on this page. Had the
easier change been made first, the report would have been "addressed" and the page would have gotten
worse — which is the argument for a criterion that demands the fix name its suspect.

### 1.21 A build broken by an ignore file, hidden by the image it already built (B-03, 2026-09-15)

| Fact | Where verified |
|---|---|
| `.dockerignore` excludes `build` and `*/build` — added for the scratch image, which builds its binary **inside** itself and must not be handed a stale one | `.dockerignore`, and the comment that explains why |
| `docker/native.Dockerfile` copies the binary **out of** `server/build/bin/native/releaseExecutable/` | that file's single `COPY` |
| So `make build` fails with `"/server/build/bin/native/releaseExecutable/server.kexe": not found`, and had been failing since the exclusion was added | reproduced by running it |
| Nothing noticed, because the `xyk:dev` tag from **before** the exclusion was still in the local daemon and every script downstream takes a tag rather than building one | `docker images` |

**Consequence 1 — two files with opposite needs, and the second one was written without re-reading
the first.** The scratch image needs the tree *without* `build/`; the runtime image is made *of* one
file inside it. Docker evaluates ignore patterns in order and the last match wins, so a single
exception resolves it — `!server/build/bin/native/releaseExecutable/server.kexe` — and the reason now
sits next to the rule.

**Consequence 2 — a stale local tag is an alibi.** Every downstream check here (`bench/parity.sh`,
`dev/image-smoke.sh`, the soak) takes an image *tag*, and a tag that resolves proves nothing about
whether it can still be produced. The same shape as §1.16, where a gate compared two stale images
and passed: **a check that consumes an artefact does not test the pipeline that makes it.** The
cheap guard is that the one target which builds (`make build`) is run on a schedule rather than only
when somebody happens to need a fresh image.

### 1.20 A load generator that fails to start is indistinguishable from a subject that copes (B-21/B-24, 2026-09-15)

| Fact | Where verified |
|---|---|
| All **40** generator logs of the B-21 memory run contain one line: `stat /bench/ingest.js: permission denied` | `/tmp/xyk-memory-212808/*.log`, counted not sampled |
| The scenario was bind-mounted from the working tree (`-v "$PWD/bench":/bench`); on this box that tree is a mutagen replica at mode `0600` | `ls -l` on the replica, and `grafana/k6`'s non-root user |
| The subject was therefore idle for every one of the forty rounds, and scored **10/10 survived** at 64 MiB | `/tmp/xyk-memory-212808/results.csv` |
| The same defect silently produced twenty minutes of flat WAL in the first B-24 soak | `/tmp/xyk-soak-213521-unswept/samples.csv`, 1 stored event |
| Staged outside the replica (`mktemp -d`, `chmod 755` / `644`) the same image reads the same file and runs | the smoke run, `http_reqs 3` |

**Consequence 1 — the failure mode has no red anywhere.** k6 exits in milliseconds, the harness does
not read its status, and what the sampler then records is a process with flat memory, flat threads and
every probe answering `200`. That is exactly the shape of a pass. Both harnesses had a positive
control *for the subject dying*, and both controls behaved correctly; neither was a control for
whether any load was applied. **A control proves the stand can detect the failure it was built for,
and nothing else.**

**Consequence 2 — the tell was in the results table and was explained away.** Eighteen threads under a
nominal 200 rps over 50 connections was written down, noticed, and attributed to the stand being too
weak to generate concurrency. The correct reading — no requests at all — was available in the same
row. A reading that flatters the conclusion deserves the check the inconvenient one would have got.

**Consequence 3 — every harness now asserts its own load, from the other end.** `bench/memory.sh`
requires `http_reqs` in each round's generator output and voids the run otherwise; `bench/soak.sh`
asks the *subject* thirty seconds in whether anything has been stored and aborts if nothing has.
Asking the subject rather than the generator is deliberate: it is the one question a broken generator
cannot answer in the affirmative.

**What this costs:** [`measurements-2026-09-15/memory-64mib.md`](measurements-2026-09-15/memory-64mib.md)
is retracted in full and B-21 goes back to unmeasured.

### 1.17 Parity under load is a different claim from parity (B-20 pilot, 2026-09-15)

| Fact | Where verified |
|---|---|
| At 2 000 rps offered, the Go twin failed **48.7 %** of requests with `500 {"error":"not stored"}` | the first three-column run |
| The cause is `SQLITE_BUSY`: `modernc.org/sqlite` returns it as soon as two connections want the writer lock, and nothing waited | a single request taken during the load |
| `busy_timeout(5000)` in the DSN takes failures to zero | the run after |
| The parity gate passed **both before and after**, because it exercised one request at a time | `bench/parity.sh` as it was |

**Consequence — a gate that checks behaviour must check it under the conditions the claim is about.**
A twin that answers correctly one request at a time and sheds half its load is not doing the same
work; it is being fast by doing less, which is precisely the failure a three-column table is most
likely to be flattered by. The gate now ends with a concurrent burst and fails on any non-`200`.

**Consequence 2 — the same reasoning applies to the control column, and my first version of it was
wrong.** The control shares a binary with the Kotlin arm, so it bounds *that* arm; comparing it with
the Go column is a category error. What catches a shared ceiling is a shared ceiling: every arm
within a quarter of every other while none reaches the offered rate.

### 1.16 A gate that compares images compares whatever was built last (B-16, 2026-09-15)

The parity gate's first run reported every case agreeing, including the one scheme that could not
possibly have worked: both arms had been changed in source and neither image had been rebuilt, so
both answered `401` where `200` was right. **Agreement is not correctness, and two stale arms agree
perfectly.**

**Consequence — the measurement target has to be built inside the invocation that measures it.**
`make parity` rebuilds both images and then runs the gate; the script itself does not build, so that
a deliberate comparison of two *specific* images is still possible. This is the same failure as a
cached task replaying a verdict about a different artefact, in a shape that no cache was involved in.

### 1.15 A SQLite file copied without its journal is empty (B-15, 2026-09-15)

| Fact | Where verified |
|---|---|
| `docker cp <container>:/data/xyk.db` on a running service gives a database that answers `no such table: events` | the first parity run |
| Copying `xyk.db`, `xyk.db-wal` and `xyk.db-shm` gives the real contents — one event, two deliveries, the same hex body on both arms | the second |

**Consequence — every harness that reads a service's database from outside must take the journal
with it.** This is the same fact as §1.8 seen from the other side: in WAL mode the main file is not
the database, it is the checkpointed part of it. A comparison that copied one file would have found
both arms empty and agreed — a green result from a measurement that measured nothing, which is the
shape of error this project is trying hardest to avoid.

**Open question 3a (new, 2026-09-15).** The image criterion is 2 831 872 bytes away from being met,
and those bytes are the gconv tree: 11 822 592 pull bytes with it, 8 990 720 without, both rendering
everything this service serves (§1.14). Spending them buys insurance against a call site that does
not exist yet; not spending them meets a number that was declared before any of this was known.
**An owner answers this, not an implementer** — and the default until then is to keep them, because
the failure they prevent is silent.

**Open question 3.** Payload retention. **The machinery is built and switched off** (B-19):
`XYK_RETENTION_DAYS=0` means for ever, a purge empties the body and keeps the record, and the `410`
path is live. What is missing is the number — and a service that deleted data on a horizon nobody
chose would be the one mistake here that cannot be undone. The earlier hypothesis of 7 days is
**not** what shipped, precisely because it was a hypothesis.

**Open question 3b.** Secrets at rest. Encrypting them needs a key, and a key in the environment of
the same process defends against a stolen volume and not a stolen pod. The alternative is to say in
the documentation that the volume is as sensitive as the secrets in it. Nothing was built either
way (B-19).

**Open question 4.** What exactly does "cold start under a second on a k0s node" measure? Declared
here so the answer cannot drift afterwards: **from container start to the first `200`, with the
image already present on the node.** Pulling bytes over somebody's uplink is a property of the link,
not of the service — and it is reported separately, as katcher's numbers do (unpack 0.27 s static
against 1.29 s, `docker run` to first answer 0.41 against 0.56).

---

## 4. What happens next

The order of work and the acceptance criteria are in [backlog.md](../../backlog.md). Three things
are deliberately first, because everything else is built on top of their answers:

1. **[B-05](../backlog/B-05-static-link-probe.md) — the static link, with and without the curl
   engine.** It answers the brief's kill condition and Risk 1 in one build, before a line of feature
   code exists to be thrown away.
2. **[B-02](../backlog/B-02-chronik-native-targets.md) — native targets on chronik.** Everything in
   the delivery half waits on it, and it is upstream, so it starts first and finishes on someone
   else's clock.
3. **[B-06](../backlog/B-06-ingest-skeleton.md) — the ingest path end to end on one endpoint**, so
   that the throughput and memory criteria have something real to run against long before the
   product is finished.
