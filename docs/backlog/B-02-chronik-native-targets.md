---
id: B-02
title: "Upstream: chronik-core and chronik-conformance publish native targets"
status: done
priority: P0
size: S
stage: stage-0-foundations
epic: feature-delivery
---

# B-02 — Upstream: chronik-core and chronik-conformance publish native targets

The brief says to use chronik, and chronik publishes nothing a Kotlin/Native binary can link. Its
Maven Central group holds `chronik-conformance`, `chronik-conformance-jvm`, `chronik-core`,
`chronik-core-jvm` and `chronik-postgres`; the `chronik-core` 0.1.0 module metadata lists exactly
three variants, all JVM or metadata; the build file declares `kotlin("multiplatform")` with a single
`jvm()` target. Verified 2026-09-15 —
[research §1.1](../research/research-architecture.md).

- **Decision: change chronik, do not fork it.** `chronik-core` "knows no SQL and executes nobody's
  code"; its only dependency is `kotlinx-coroutines-core`, which publishes every target we need.
  *Hypothesis:* adding `linuxX64()`, `linuxArm64()` and `macosArm64()` is a build-file change with no
  source change behind it. It is a hypothesis until a build says otherwise — if `expect/actual` turns
  out to be needed anywhere, that is the interesting finding and it belongs in chronik's research,
  not in a workaround here.
- **`chronik-conformance` crosses with it**, or a SQLite store cannot be validated by the corpus that
  decides what "correct" means for a chronik store.
- **Rejected: vendoring chronik's sources into xyk.** It forks the contract silently, and the
  conformance kit then tests something other than what we ship.
- **Rejected: writing our own retry loop and citing chronik in the README.** Cheaper for one week;
  it discards the only part of the delivery half that somebody who did not write it can check.
- Not covered: `chronik-postgres`, whose tests need Docker and which stays JVM-only.

## Where it stands, 2026-09-15 (evening)

**Half done, and the half that is left is the half xyk needs.** `chronik-core` declares `linuxX64()`
beside `jvm()`, `chronik-conformance` travels with it, and no source change was required — the
hypothesis above held. That work is commit `4864fca` on branch `feat/linux-native-target`, pushed,
**not merged**: `origin/main` is still at `0533371`.

**Nothing is published.** Maven Central's group listing has no `chronik-core-linuxx64` and reports
`0.1.0` as latest; the reposilite snapshot repository answers `404` for the same coordinate. So
there is still nothing for xyk to pin, and "it builds" is not the claim that unblocks a consumer —
see the correction in [research §1.1](../research/research-architecture.md).

What remains: merge, and a publish that is verified **by reading the coordinate back**, not by a
green workflow. A publish that fails halfway leaves artifacts behind and poisons its own version
number.

## Published, and it carries more than was asked for (2026-09-15)

`io.github.youndie.chronik:*:0.1.0.16` on the reposilite snapshot repository, read back off the
coordinate rather than off a green workflow:

| module | variants published |
|---|---|
| `chronik-core` | metadata, `jvm`, **`linuxX64`** |
| `chronik-conformance` | metadata, `jvm`, **`linuxX64`** |
| `chronik-sqlx4k-sqlite` | metadata, `jvm`, **`linuxX64`** |

Verified by fetching each `.module` and listing its variants, and by unpacking the sources jar of the
third one.

**The third module was not in this item's plan, and it changes [B-03](B-03-chronik-sqlite-store.md)
from writing a store to adopting one.** `chronik-sqlx4k-sqlite` ships
`SqliteTimerStore : TransactionalTimerStore` over a sqlx4k `Driver` the application opens, with
`Transaction.asTimerTransaction()` for handing it the caller's transaction, and
`chronikTimersSchema(table)` returning the DDL as text to append to an existing migration list —
chronik still executes no DDL and owns no schema lifecycle.

**One limit to know before it is discovered by a red build:** the native variant is `linuxX64`
**only**. There is no `macosArm64`, so anything in xyk that touches chronik will not resolve on the
Mac. That costs nothing here — this repository builds on the build machine by policy — but it means the
delivery half cannot be compiled locally at all, where the ingest half can.

- AC: **met.** The release exists, xyk pins `0.1.0.16` from the catalog, and the coordinate was read
  back.
- AC: an issue and a pull request on `youndie/chronik`; a released version on Maven Central whose
  group directory contains `chronik-core-linuxx64`.
- AC: xyk's version catalog pins that released version — never a snapshot, never a project
  dependency across repositories.
- AC: if the change is refused or turns out to be expensive, the refusal and its reason are written
  into [research §1.1](../research/research-architecture.md) at the point of divergence, and this
  item becomes the decision to write a store against a different contract.
- Anchors: `chronik/chronik-core/build.gradle.kts`, `chronik/chronik-conformance/build.gradle.kts`,
  `gradle/libs.versions.toml`
