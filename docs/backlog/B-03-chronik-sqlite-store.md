---
id: B-03
title: "A chronik TransactionalTimerStore over sqlx4k/SQLite, green against the conformance kit"
status: open
priority: P0
size: L
stage: stage-0-foundations
epic: feature-delivery
blocked_by: [B-02]
---

# B-03 — A chronik TransactionalTimerStore over sqlx4k/SQLite

chronik ships one store, for Postgres, on the JVM. xyk needs one that writes timers into the same
SQLite transaction as the event row — which is the reason chronik is in this design at all
([research §1.2](../research/research-architecture.md)).

- **Decision: `claimDue` is a single statement.** `UPDATE ... WHERE id IN (SELECT ... LIMIT ?)
  RETURNING *`, not a select followed by an update. With one writer the pair would be correct by
  luck; correctness that depends on there being one worker is correctness that disappears the day
  somebody adds a second.
- **Decision: the table lives in xyk's migration list, not in this module.** A module shipping its
  own DDL could not be inside somebody else's transaction, and being inside it is the whole point.
- **Decision: the conformance kit is the acceptance criterion.** It collects findings and returns
  them rather than throwing, so one run reports everything wrong instead of the first thing.
- **Rejected: hand-written unit tests as the primary check.** They mostly prove that the author
  agrees with the author.
- Not covered: publishing this module. It may move upstream later; that is a separate decision taken
  once it has run in production.

**Unblocked for the writing, not for merging (2026-09-15).** chronik's native targets exist and
build ([B-02](B-02-chronik-native-targets.md)), so the contract this store implements is final and
the work can start against `publishToMavenLocal` or an included build. What must not happen is that
resolution reaching `main`: a build green only where somebody ran `publishToMavenLocal` is red for
everybody else and says nothing about why. The pin on a released version is an acceptance criterion
of this item, not a follow-up to it.

The conformance corpus now publishes `linuxX64` too, so the acceptance below is runnable — it was
not when this item was written.

- AC: `chronik-conformance` runs against this store on `linuxX64` and reports no findings.
  (`macosArm64` is deliberately **not** a chronik target — the corpus stops at `jvm` and `linuxX64`,
  so a store tested only on a Mac is a store nobody has checked.)
- AC: a test kills a worker mid-batch and shows the claimed timers becoming claimable again exactly
  at the lease boundary — not before, not never.
- AC: the tests use a **file** database in a temporary directory; `:memory:` is not used anywhere.
- Anchors: `chronik-sqlite/src/commonMain/kotlin/io/github/youndie/xyk/chronik/Sqlx4kTimerStore.kt`,
  `chronik-sqlite/src/commonTest/kotlin/io/github/youndie/xyk/chronik/ConformanceTest.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/db/Migrate.kt`
