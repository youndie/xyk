---
id: chronik-sqlite
title: chronik-sqlite — a chronik store over sqlx4k/SQLite
type: service
repo_url: https://github.com/youndie/xyk
module: chronik-sqlite
tech_stack: [Kotlin/Native, sqlx4k/SQLite, chronik-core]
owner: unassigned
depends_on:
  - chronik-core (io.github.youndie.chronik, upstream)
publishes:
  - nothing yet — a module of this repository, and a candidate to move upstream
---

# chronik-sqlite

> **Status: nothing here is built yet, and it cannot be until chronik publishes native targets**
> ([research §1.1](../research/research-architecture.md)).

## 1. Responsibility

One thing: implement chronik's `TransactionalTimerStore` on top of the same SQLite database
[xyk-server](xyk-server.md) keeps its events in, so that the event row and the timer that will
deliver it commit **together**.

It deliberately does **not**:

* **own the schema migration** — the table it reads lives in xyk's migration list, next to the
  tables it has to commit with. A module that shipped its own DDL could not be inside somebody
  else's transaction, which is the entire point of it;
* **own a connection or a pool** — it is handed the transaction it must write in;
* **know what a webhook is.** It stores `Timer` rows. If it ever mentions a delivery, it has drifted
  into the application and should be moved back.

## 2. API contracts

* **Contracts:** chronik's, not ours — `TimerStore` and `TransactionalTimerStore` in
  `chronik/chronik-core/src/commonMain/kotlin/TimerStore.kt`
* **Acceptance:** `chronik-conformance`, the corpus that decides whether a store is correct. It
  collects findings and returns them rather than throwing, so a new backend is not fixed one finding
  per run — which is why running it once is worth more than the unit tests around it.

## 2a. Code anchors

| File | What is there |
|---|---|
| `chronik-sqlite/src/commonMain/kotlin/io/github/youndie/xyk/chronik/Sqlx4kTimerStore.kt` | the store |
| `chronik-sqlite/src/commonMain/kotlin/io/github/youndie/xyk/chronik/SqliteTimerTransaction.kt` | the `TimerTransaction` wrapper over a sqlx4k transaction |
| `chronik-sqlite/src/commonTest/kotlin/io/github/youndie/xyk/chronik/ConformanceTest.kt` | the corpus, run against this store |
| `server/src/commonMain/kotlin/io/github/youndie/xyk/db/Migrate.kt` | where the `timers` table is actually created |

## 3. How it is built

**The claim is one statement, and it has to be.** `claimDue(now, leaseUntil, owner, limit)` selects
and locks in a single `UPDATE ... WHERE id IN (SELECT ... LIMIT ?) RETURNING *`, because SQLite's
writer lock is per-database: a select-then-update pair would be correct here by luck (one writer)
and wrong the moment anything else writes. Writing it as one statement means the correctness does
not depend on how many workers there are.

**The lease is what makes a crash recoverable.** A worker killed mid-batch leaves rows with
`lockedUntil` in the future; they become claimable again when it passes, which is the only reason
`leaseSeconds` exists in a single-process service.

**Everything is seconds.** chronik's `EpochSeconds` is the unit of the contract; storing
milliseconds "because it is more precise" would put a conversion between the contract and the
`WHERE` clause, and a conversion in a comparison is where off-by-one-second bugs live.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Library | `io.github.youndie.chronik:chronik-core` | the interfaces it implements |
| Library | `io.github.smyrgeorge:sqlx4k-sqlite` | the driver |
| Test | `io.github.youndie.chronik:chronik-conformance` | the acceptance corpus |

## 5. Infrastructure and deploy

None of its own: it is linked into [xyk-server](xyk-server.md)'s binary.

## 6. Local setup

```bash
./gradlew :chronik-sqlite:macosArm64Test
```

The tests use a **file** database in a temporary directory, not `:memory:` — sqlx4k is two drivers,
and the JVM half cannot pool an in-memory database at all
([research §1.11](../research/research-architecture.md)).

## 7. Configuration

None. It is handed a connection and a clock.

## 8. Quirks

* **It has no `main`, no schema and no tests of its own worth trusting** — the conformance kit is
  the test. A green suite of hand-written unit tests here would mostly prove that the author agrees
  with the author.
* **If it ever needs its own table definition, it has stopped being this module** and has become a
  library, at which point it belongs upstream in chronik rather than here.
