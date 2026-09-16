---
id: B-03
title: "chronik's sqlx4k/SQLite store adopted and green against the conformance kit"
status: done
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
## The store came from upstream instead (2026-09-15)

**This item planned to write a `TransactionalTimerStore`. chronik 0.1.0.16 ships one**, so what was
done is adoption, and the remaining risk moved: the SQL is no longer ours to get wrong, and what had
to be shown is that *our* driver, pool, migration order and table satisfy it.

What was added here:

* **`chronik = "0.1.0.16"` in the catalogue**, with `sqlx4k` moved 1.13.0 → 1.13.1 to match what
  `chronik-sqlx4k-sqlite` is built against — two halves of one driver on different versions is the
  kind of skew that surfaces as a link error rather than a resolution failure.
* **Migration v5: the timers table**, as xyk's own SQL. chronik executes no DDL; `chronikTimersSchema()`
  hands the statements back as text for the application's list, so the version number and the
  ordering stay here.
* **`ChronikSchemaParityTest` (jvm suite)** — the copy held against `chronikTimersSchema()`,
  statement for statement. It runs on the JVM because chronik's `jvm` variant resolves on every host
  including the Mac, where its `linuxX64` one does not. **Checked by mutation:** one `NOT NULL` added
  to the copy turns the suite red at that line.
* **`ChronikConformanceTest` (native suite)** — `ConformanceKit().run(subject)` against
  `SqliteTimerStore` on xyk's own driver and pool. **17 cases, 0 findings.** It is in the *native*
  suite deliberately: sqlx4k is two drivers, and a corpus green on Xerial says nothing about the Rust
  one in the image. It also asserts `kit.cases.isNotEmpty()`, because an empty findings list from a
  kit that ran nothing is the same green as one from a store that passed.

**Verified end to end rather than by compilation:** a container built from `docker/native.Dockerfile`
with `/data` bind-mounted reports `user_version = 5`, and the database holds `chronik_timers` with
`idx_chronik_timers_state_due_at`.

**Why the DDL is duplicated rather than called.** `chronik-sqlx4k-sqlite` has no `macosArm64`
variant. Calling `chronikTimersSchema()` from `commonMain` would make the migration list itself
Linux-only, and a list that is shorter on one host means `user_version = 5` names two different
schemas depending on where the binary was built. A copy with a test against its source is a different
thing from a copy.

- AC: **met** — the corpus runs, on the driver that ships, and reports nothing.
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
- Anchors: `server/src/variants/with-chronik/kotlin/io/github/youndie/xyk/delivery/`,
  `chronik-sqlite/src/commonTest/kotlin/io/github/youndie/xyk/chronik/ConformanceTest.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/xyk/db/Migrate.kt`
