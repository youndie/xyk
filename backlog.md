# Backlog: a durable webhook gateway small enough to run one per project

> Role of this document: the product backlog. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `B-NN-<slug>.md`. What lives here is the index (generated) and
> everything that is not an item: the goal, the criteria declared before the code, the stages, and
> the decisions.
>
> New item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `B-NN`, and run `python3 scripts/backlog_index.py` after editing.

## Goal

xyk takes a webhook from anyone, proves it is genuine, writes it down before answering, and delivers
it with retries and timeouts — and shows an operator what happened to every one. The product is the
promise that nothing accepted is ever lost; the engineering question behind it is whether
Kotlin/Native can hold that promise inside a container small enough that nobody has to justify
running one per project. Both questions are answered by the same set of numbers, declared below
before a line of code exists.

## Criteria, declared 2026-09-15, before the code

Verbatim, with what will decide each and what each of them means exactly — because a criterion whose
terms are settled after the run is a description of the run.

| # | Criterion | Decided by | Stated precisely |
|---|---|---|---|
| 1 | 2 000 rps on ingest over 200 connections, no slow state | [B-20](docs/backlog/B-20-criterion-throughput.md) | open-model generator off-host, `dropped_iterations` = 0, a latency ceiling named before the run, RSS and `walBytes` flat over the second half |
| 2 | 64 MiB limit, 10 runs out of 10 survive | [B-21](docs/backlog/B-21-criterion-memory.md) | four allocator arms, interleaved, **with a positive control that must be killed** |
| 3 | The image is at most 10 MB | [B-23](docs/backlog/B-23-criterion-image-size.md) | **pull bytes**, `docker save \| wc -c`, on a named host — not `inspect .Size`, which means different things on different storage drivers |
| 4 | Cold start to the first `200` under a second on a k0s node | [B-22](docs/backlog/B-22-criterion-cold-start.md) | image already on the node; the first `200` is through the real route, not a probe; pull time reported separately |
| 5 | The same scenario on a Go twin, in three columns | [B-15](docs/backlog/B-15-go-twin.md), [B-16](docs/backlog/B-16-twin-parity-gate.md), [B-20](docs/backlog/B-20-criterion-throughput.md) | one script, one host, interleaved arms, and a parity gate that refuses to time anything until both arms answer identically |

**The kill condition, also declared in advance:** if the curl dependency makes it impossible to link
even the incoming half statically, that is a **result**, not a failure — it names a boundary of the
platform layer, and it goes into the article about it. The experiment that answers it is
[B-05](docs/backlog/B-05-static-link-probe.md), and it runs before any feature code exists to be
thrown away.

**And one prediction, recorded so that it can be wrong in public.** Criterion 2 is the one the
existing evidence says will fail: a Ktor service on this platform with *no database at all*, measured
at exactly this load on 2026-09-15, peaked at 65.3 MB — above the line, before SQLite is in the
picture. The reasoning is in
[research §1.8](docs/research/research-architecture.md). If it passes, something in that reasoning is
wrong and the interesting work is finding out what.

## Stages

A stage is a field on the item, not a directory. Items are cited by id from the layer documents, so
re-prioritising one must never move its file.

| Stage id | Stage | What it is |
|---|---|---|
| `stage-0-foundations` | Does this work at all | The questions whose answers can invalidate the plan: chronik has no native artifacts, a static link may refuse the HTTP client, and the storage layer has a known way to kill itself. |
| `stage-1-product` | The thing people use | Ingest, verification, delivery, the journal, the registry — everything that makes it a product rather than a probe. |
| `stage-2-image` | What actually ships | The runtime image, in the order distroless-then-scratch, with its size measured the same way every time. |
| `stage-3-verdict` | The numbers, in three columns | The declared criteria, the Go twin, the soak. This stage is the article. |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (0)

No open tasks.

## Closed (30)

**Does this work at all**

- [B-01](docs/backlog/B-01-gradle-skeleton-and-lifecycle.md) `[x]` - A binary that starts, migrates, answers /health and stops in order
- [B-02](docs/backlog/B-02-chronik-native-targets.md) `[x]` - Upstream: chronik-core and chronik-conformance publish native targets
- [B-03](docs/backlog/B-03-chronik-sqlite-store.md) `[x]` - chronik's sqlx4k/SQLite store adopted and green against the conformance kit
- [B-04](docs/backlog/B-04-database-pool-and-journal.md) `[x]` - Migrations, a pool of two, WAL truncation and walBytes in the health response
- [B-05](docs/backlog/B-05-static-link-probe.md) `[x]` - The static-link probe: does the curl engine make a static link impossible?
- [B-06](docs/backlog/B-06-ingest-skeleton.md) `[x]` - One endpoint, end to end: verified, stored with its timers, answered 200

**The thing people use**

- [B-07](docs/backlog/B-07-endpoint-registry.md) `[x]` - Endpoints and subscribers as rows: create, rotate, disable
- [B-08](docs/backlog/B-08-body-limit-and-rejection-counters.md) `[x]` - A body limit that does not buffer, and counters for what was refused
- [B-09](docs/backlog/B-09-signature-verifiers.md) `[x]` - Five schemes, recorded vendor vectors, and one constant-time compare
- [B-10](docs/backlog/B-10-delivery-sink.md) `[x]` - The delivery sink: one POST, one timeout, one attempt row
- [B-11](docs/backlog/B-11-delivery-workers.md) `[x]` - Delivery workers: owners, lifecycle, and a readiness check that counts ticks
- [B-12](docs/backlog/B-12-journal-page.md) `[x]` - The journal page and its five states
- [B-13](docs/backlog/B-13-journal-api.md) `[x]` - The journal API: keyset pages, streamed payloads, redelivery
- [B-19](docs/backlog/B-19-secret-handling.md) `[x]` - Secrets at rest, and how long payloads are kept
- [B-25](docs/backlog/B-25-journal-page-collapses-under-concurrency.md) `[x]` - The journal page collapses under fifty concurrent readers
- [B-26](docs/backlog/B-26-schedule-timers-on-ingest.md) `[x]` - Nothing schedules a timer yet, so nothing is ever delivered
- [B-27](docs/backlog/B-27-bind-failure-is-unreadable.md) `[x]` - A port already in use is reported as a cancelled coroutine, after the start was announced

**What actually ships**

- [B-17](docs/backlog/B-17-runtime-image.md) `[x]` - The runtime image on distroless/cc, measured in pull bytes
- [B-18](docs/backlog/B-18-scratch-image.md) `[x]` - The scratch variant: five paths, and a smoke test that renders a page

**The numbers, in three columns**

- [B-14](docs/backlog/B-14-delivery-worker-count.md) `[x]` - How many delivery workers, and is the curl dispatcher the real ceiling?
- [B-15](docs/backlog/B-15-go-twin.md) `[x]` - The Go twin of the ingest path
- [B-16](docs/backlog/B-16-twin-parity-gate.md) `[x]` - A parity gate: the twin and xyk answer the same things before anything is timed
- [B-20](docs/backlog/B-20-criterion-throughput.md) `[x]` - Criterion: 2 000 rps over 200 connections with no slow state, in three columns
- [B-21](docs/backlog/B-21-criterion-memory.md) `[x]` - Criterion: 64 MiB limit, ten runs out of ten survive
- [B-22](docs/backlog/B-22-criterion-cold-start.md) `[x]` - Criterion: cold start to the first 200 under a second on a k0s node
- [B-23](docs/backlog/B-23-criterion-image-size.md) `[x]` - Criterion: the image is at most 10 MB
- [B-24](docs/backlog/B-24-soak-wal.md) `[x]` - The soak: the journal page reading while ingest writes, long enough to find the cliff
- [B-28](docs/backlog/B-28-allocator-decision.md) `[x]` - Decide the allocator on both criteria, not on the one that was measured last
- [B-29](docs/backlog/B-29-memory-with-delivery-on.md) `[x]` - The memory criterion has not been measured on the configuration that ships
- [B-30](docs/backlog/B-30-delivery-memory-growth.md) `[-]` - The delivery half grows without bound, and the memory criterion cannot see it

<!-- END INDEX -->

## Decisions worth not re-litigating

**"Use chronik" became an upstream change, and that is a deviation from the brief, not a
substitution.** chronik publishes five artifacts and not one of them is native; `chronik-core` 0.1.0
declares a single `jvm()` target. Checked by listing Maven Central and reading the build file, not by
recollection — [research §1.1](docs/research/research-architecture.md). So
[B-02](docs/backlog/B-02-chronik-native-targets.md) is upstream work that starts first and finishes
on someone else's clock, and vendoring the sources was rejected: it forks the contract silently and
the conformance kit then tests something other than what we ship.

**The delivery half is blocked and the ingest half is not, and the order of work follows that.**
Every criterion about throughput, memory and image size can be measured on ingest alone. That is why
[B-06](docs/backlog/B-06-ingest-skeleton.md) is in stage 0 next to the questions, rather than in the
product stage where it belongs by subject matter.

**The Go twin lives in this repository.** A twin elsewhere drifts, and then the three-column table
is two measurements taken on different days and compared by arithmetic — both numbers real, the
comparison void. Its scope is exactly the ingest path: a fuller port would measure how well the
author writes Go.

**There is no `screens/` layer, and that is an answer.** The journal is rendered by the same binary;
there is no client anybody would change from a document. How the page is assembled lives in
[xyk-server](docs/services/xyk-server.md), and its states are listed in
[feature-journal](docs/features/feature-journal.md) where a future client would read them.

**A measurement without a positive control is not published.** Twice in this backlog —
[B-21](docs/backlog/B-21-criterion-memory.md) and [B-24](docs/backlog/B-24-soak-wal.md) — an arm
exists whose only job is to fail. A harness that has never failed on purpose has never been shown
able to notice a failure, and a run in which everything survives is re-run rather than reported.

**Every number says what it measured and on what.** Image sizes are pull bytes with the host named;
throughput is from an off-host open-model generator; the first run after a restart is discarded as
warm-up. These are not style rules — each of them has already been paid for by a wrong conclusion
somewhere in this portfolio.
