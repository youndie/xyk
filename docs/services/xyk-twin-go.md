---
id: xyk-twin-go
title: xyk-twin-go — the Go twin of the ingest path
type: service
repo_url: https://github.com/youndie/xyk
module: twin-go
tech_stack: [Go, net/http, SQLite]
owner: unassigned
depends_on:
  - nothing at runtime
publishes:
  - a local image used only by the measurement harness
---

# xyk-twin-go

## 1. Responsibility

It exists to be **the second column**. It implements exactly the ingest path of
[feature-ingest](../features/feature-ingest.md) — read the body, verify a signature, insert a row,
answer `200` — on `net/http` and a SQLite driver, so that the numbers in
[backlog.md](../../backlog.md) say what the platform floor is under identical work.

What it deliberately does **not** do: delivery, retries, the journal, the admin routes, or anything
else that would make it a product. A fuller port would measure how well the author writes Go; this
one measures the floor.

**It is not shipped.** No chart, no registry, no versioning. It is a fixture.

## 2. API contracts

One route, deliberately identical in shape to xyk's: `POST /hooks/{endpointId}`, the same headers,
the same status codes, the same response body. When they differ the comparison is void — so the
harness asserts a handful of responses from both before it starts timing anything
([B-16](../backlog/B-16-twin-parity-gate.md)).

## 2a. Code anchors

| File | What is there |
|---|---|
| `twin-go/main.go` | the server, the route, the insert |
| `twin-go/verify.go` | the same three schemes, in Go's `crypto/hmac` |
| `twin-go/Dockerfile` | the runtime image, so the image column compares images and not build systems |
| `bench/run.sh` | the harness that runs both arms, interleaved, on one host |

## 3. How it is built

**Both arms run in one invocation of one script, interleaved, on one host.** That is the whole
design. A twin measured last Tuesday against a Kotlin binary measured today is two numbers and no
comparison — and the failure is invisible, because both numbers are real
([research, D4](../research/research-architecture.md)).

**The SQLite driver is named in the results table**, because `modernc.org/sqlite` (pure Go) and
`mattn/go-sqlite3` (cgo) differ enough that "Go" without the driver is not an answer. Which one is
used is a decision recorded in [B-15](../backlog/B-15-go-twin.md), not a detail.

**`GOMEMLIMIT` is set to the same limit the container gets**, because the Go runtime honours it and
Kotlin/Native has no equivalent to honour ([research §1.8](../research/research-architecture.md)).
Not setting it would flatter Go in a way that hides the actual difference: it is not that Go is
thriftier, it is that Go's runtime can be *told*.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Database | SQLite (driver named in the results) | the same insert |
| Tool | k6 or an equivalent open-model generator | `constant-arrival-rate` with honest `dropped_iterations` |

## 5. Infrastructure and deploy

Never deployed. Built locally by `bench/run.sh`.

## 6. Local setup

```bash
bench/run.sh --arms kotlin,go --runs 10
```

## 7. Configuration

The same environment variables as xyk-server where they overlap, so the harness can set one
environment for both arms.

## 8. Quirks

* **The generator must not run on the machine under test.** A load generator on the subject's host
  does not merely add noise, it competes for exactly the cores the measurement is about.
* **The first run after a restart measures warm-up**, and is discarded explicitly rather than
  averaged in.
* **A run where every arm passes is suspect** and is re-run with a positive control — a limit small
  enough that something *must* die. A harness that cannot detect failure cannot report its absence.
