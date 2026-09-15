---
id: B-15
title: "The Go twin of the ingest path"
status: done
priority: P1
size: M
stage: stage-3-verdict
blocked_by: [B-06]
---

# B-15 — The Go twin of the ingest path

The second column: `net/http`, a SQLite driver, the same route, the same three schemes, the same
insert, the same `200`. It exists to say what the platform floor is under identical work.

- **Decision: it lives in this repository and is built by the same harness.** A twin in its own
  repository drifts, and the comparison quietly becomes two measurements taken on different days —
  both real, and no longer a comparison.
- **Decision: the driver is named in every results table.** `modernc.org/sqlite` (pure Go) and
  `mattn/go-sqlite3` (cgo) differ enough that "Go" is not an answer. Which one, and why, is decided
  here and written down.
- **Decision: `GOMEMLIMIT` is set to the container's limit.** The Go runtime honours it and
  Kotlin/Native has nothing to honour; leaving it unset would flatter Go for the wrong reason and
  hide the actual difference, which is that one runtime can be told its budget and the other cannot.
- **Rejected: a full port.** The column worth having is the floor under identical work; a full port
  measures how well the author writes Go.
- Not covered: delivery, the journal, the admin routes.

- AC: the twin answers the same statuses for the same requests, checked by
  [B-16](B-16-twin-parity-gate.md) before any timing is recorded. **Done by hand here; the gate that
  enforces it on every run is B-16.**
- AC: its image is built the same way and measured the same way as xyk's. **Done** — static, no base
  image, `docker save | wc -c` on the same host.

## Closed 2026-09-15

`twin-go/` is `net/http` plus `modernc.org/sqlite`: the same route, the same four schemes, the same
one-transaction insert of an event and one delivery row per enabled subscriber, the same statuses,
the same schema, and the same `XYK_BOOTSTRAP_*` variables so one script sets up both arms.

**The driver is `modernc.org/sqlite` — pure Go — and that is the decision the results table has to
name.** `mattn/go-sqlite3` is cgo, which means a dynamically linked binary and a base image, while
the arm it is being compared against is a static Kotlin/Native binary on `scratch`. The comparison
would have been measuring packaging.

**Side by side, same host, same requests:**

| case | Kotlin | Go |
|---|---|---|
| genuine | `200` | `200` |
| forged signature | `401` | `401` |
| no signature header | `401` | `401` |
| unknown endpoint | `404` | `404` |
| 2 MB body against a 1 MiB limit | `413` | `413` |

and the stored state is identical too: one event, two deliveries, `body_bytes` 29, and the same hex
body out of both databases.

**Image sizes, the same method as everywhere else:** the twin is **4 118 016 pull bytes** against
xyk's 8 369 664 on `scratch` and 11 822 592 with the HTTP client. Not a like-for-like product
comparison — the twin has no delivery, no journal, no registry — but it is the floor, which is what
this column is for.

**One thing the harness has to know, found here rather than in B-20:** copying `xyk.db` out of a
container **without `xyk.db-wal`** gives a database with no tables in it. Everything written since
the last checkpoint lives in the journal, so a comparison that copies one file compares an empty
database with an empty database and agrees. Both arms run `journal_mode = WAL`; the copy takes all
three files.
- Anchors: `twin-go/main.go`, `twin-go/verify.go`, `twin-go/Dockerfile`, `bench/run.sh`
