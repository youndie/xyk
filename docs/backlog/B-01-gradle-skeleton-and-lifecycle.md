---
id: B-01
title: "A binary that starts, migrates, answers /health and stops in order"
status: done
priority: P0
size: M
stage: stage-0-foundations
---

# B-01 — A binary that starts, migrates, answers /health and stops in order

Nothing exists. This item is the skeleton up to the point where `/health/ready` answers **from the
image**, and not one line further: the Gradle module with `jvm()` plus `linuxX64`/`linuxArm64`/
`macosArm64`, the entry point, `ServerConfig` through `expect/actual`, the migration list run before
`embeddedServer`, Koin wired from the first repository, and kore's stop sequence.

- **Decision: kore rather than `ApplicationStopping`, from the first commit.** On Kotlin/Native the
  engine's stop steps run in the opposite order from the JVM, so the idiomatic place to close the
  pool runs *before* the engine drains. For xyk that is an accepted-and-undelivered webhook, which
  is the exact defect kore was adopted for elsewhere. Retrofitting it later means rewriting `main`
  and every probe.
- **Decision: `binaryOption("fixedBlockPageSize", "16")` now, not when things start dying.** And
  check the binary actually changed (md5 against a build without it): a flag that silently does
  nothing leaves a later measurement comparing one thing with itself.
- **Rejected: `sborka.native-service`.** The convention would give a stable binary path and a size
  budget, and its only consumer today is a stand inside sborka itself. Hand-rolled here, like
  katcher and metrik, with this item as the place the decision is recorded.
- Not covered: routes of any kind, the database schema beyond an empty migration list, DI bindings
  for features that do not exist.

- AC: `docker run` the image, `GET /health/startup`, `/health/ready`, `/health/live` and `/version`
  all answer; `docker stop` leaves a transcript with `SIGNAL / ANNOUNCE / DRAIN /
  RELEASE_CONSUMERS / RELEASE_POOLS / EXIT`, each `COMPLETED`. **Done**, from the image.
- AC: the same transcript is asserted by a CI job, not by a person reading logs once. **Done** —
  `dev/shutdown-check.sh`, run by `make build` and by `.github/workflows/build.yaml`.
- AC: `LICENSE` exists — one file, before the first image is published. **Done.**

## Closed 2026-09-15, and what it produced

Measured on the Linux box (Ubuntu 24.04, glibc 2.39), release build, `linuxX64`:

| | |
|---|---|
| binary, stripped by the release link, **no HTTP client yet** | **9 662 208 bytes** |
| image on `gcr.io/distroless/cc-debian13`, pull bytes (`docker save \| wc -c`) | **14 163 968 bytes** |
| probes from inside the image | `startup` `ready` `live` all `200` |
| `/version` | `0.1.0`, `commit: unknown` — no `.git` in the build context, as documented |
| `docker stop` transcript | `SIGNAL ANNOUNCE DRAIN RELEASE_CONSUMERS RELEASE_POOLS RELEASE_TELEMETRY EXIT`, each `COMPLETED`, in order |

**The image number is the interesting one and it is bad news for criterion 3**: 14.2 MB against a
10 MB line, with no HTTP client linked in yet. See
[B-23](B-23-criterion-image-size.md) and [research §1.12](../research/research-architecture.md).

**The stop-order check was shown to fail before it was believed.** `STOP_TIMEOUT=0` in
`dev/shutdown-check.sh` kills the process outright; the check then reports every stage missing and
exits 1. Without that control, a guard that always passes is indistinguishable from a guard that
cannot see.

Two things found while building that were not in the plan:

* `asLong()` on a result column is an **extension** (`io.github.smyrgeorge.sqlx4k.impl.extensions`),
  not a member. The compile error names the symbol and not the import, which is a minute either way
  but a minute per person.
* `RELEASE_TELEMETRY` is a seventh stage in the transcript, between `RELEASE_POOLS` and `EXIT`. The
  documents said six because six is what the skill's example shows; the check asserts the order of
  the six it names rather than the exact set, which is why it stayed green and honest.
- Anchors: `server/build.gradle.kts`, `server/src/commonMain/kotlin/io/github/youndie/xyk/Application.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/ServerConfig.kt`,
  `server/src/nativeMain/kotlin/io/github/youndie/xyk/Env.native.kt`,
  `.github/workflows/build.yaml`
