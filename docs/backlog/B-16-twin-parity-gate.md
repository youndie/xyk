---
id: B-16
title: "A parity gate: the twin and xyk answer the same things before anything is timed"
status: done
priority: P1
size: S
stage: stage-3-verdict
blocked_by: [B-15]
---

# B-16 — A parity gate before anything is timed

A three-column table is a claim that both columns did the same work. Nothing enforces that by
itself, and the failure is invisible: both numbers are real.

- **Decision: the harness refuses to time anything until a fixed corpus of requests produces
  identical statuses and identical stored bytes on both arms.** Genuine, forged, stale, oversized,
  unknown endpoint, each scheme.
- **Decision: the stored row is compared, not only the response.** An arm that answers `200` and
  stores nothing is fast for a reason that has nothing to do with the platform.
- **Decision: the gate runs in the same invocation as the measurement**, not once by hand at the
  start. Parity established on Monday says nothing about Thursday's binary.
- Not covered: comparing delivery, which the twin deliberately does not implement.

- AC: the harness exits non-zero, with the first difference printed, when the arms disagree.
  **Done** — `bench/parity.sh`, exit 1 with the diff.
- AC: a deliberately broken twin (dropping the insert) is caught by the gate — a check that has never
  failed on purpose has never been shown to work. **Done, and it is the most useful line in this
  item**: `TWIN_BREAK=drop-insert` makes the twin answer `200` and store nothing. **Every status
  still matches** — a status-only gate passes it — and the stored-state comparison catches it:

  ```
  parity: the arms stored different things —
  < github|53|ee3bb883|7B227A656E223A2248616C66206D65…
  < 2
  ---
  > 0
  ```

## Closed 2026-09-15

Eighteen cases across the four schemes, both arms, every one agreeing on the status **and** on what
reached the database:

| scheme | cases |
|---|---|
| github | genuine, forged body, wrong prefix, no header, unknown endpoint, oversized |
| stripe | fresh, stale timestamp, `v0` only, no header |
| telegram | right token, wrong token, no header |
| hmac-sha256 | base64 digest, hex where base64 is expected, wrong header |

**One scheme per run.** Neither arm grew an admin API for the sake of the gate: each scheme starts
both containers with the same `XYK_BOOTSTRAP_*` environment — which now carries `SCHEME_CONFIG` too,
so the generic scheme is covered without a product surface invented for a test.

**The stored comparison copies the journal with the database.** In WAL mode the main file is only the
checkpointed part; a gate that copied one file would compare two empty databases and agree
([research §1.15](../research/research-architecture.md)).

**And the trap this gate walked into on its first run: it compared stale images.** Both arms had been
rebuilt in source and not in docker, so the generic scheme answered `401` on both sides — *agreeing*,
and agreeing on being wrong. The gate passed. `make parity` now rebuilds both images before running
it, because a gate that compares snapshots must be told when to take them.
- Anchors: `bench/parity.sh`, `bench/corpus/`
