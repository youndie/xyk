# docs — xyk

xyk receives webhooks from anyone, proves they are genuine, stores them before answering, delivers
them to subscribers with retries and timeouts, and shows an operator what happened to each one. The
documentation is layered; links run top to bottom.

```
[ Research (why the architecture is what it is) ]
                     │
[ Feature (business + BDD) ]
                     │
                     ▼
           [ API endpoint (contract, auth tier) ]
                     │
                     ▼
           [ Service (ownership, deploy) ]
```

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what is verified, what is a hypothesis | the artefacts each fact names |
| Feature | `features/` | *what* the system does and *why*; BDD scenarios | this repository |
| API | `api/` | URL, method, auth tier, error codes | the `@Resource` classes named in each document |
| Service | `services/` | who owns the data, dependencies, deploy, local setup | this repository |

**There is no `screens/` layer, on purpose.** The journal page is rendered by the same binary that
ingests; there is no client application anybody would change from a document. The page's states are
listed in [feature-journal](features/feature-journal.md) and the way it is assembled is in
[xyk-server](services/xyk-server.md). A missing layer is a valid answer; a renamed one is not.

**Nothing in this repository is built yet.** Every layer document is `status: draft` and every path
in a code-anchors table is a path the backlog is about to create — which is also why
`code_anchors.py` reports them missing and why that report does not block. The facts in the research
document are read out of *other people's* artefacts, which is what "verified" can mean before there
is a source tree.

**Backlog** — [backlog.md](../backlog.md): the index, the criteria declared before the code, and the
decisions; the items themselves are one file each in [`backlog/`](backlog/), cited as
`[B-12](backlog/B-12-journal-page.md)`.

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter and ordinary markdown links in the body.
- One document, one entity. A feature spanning two modules is **one** file with two entries in
  `involved_services`.
- BDD scenarios are written from the code — and while there is no code they are marked **target**,
  as they are today. An `**Automated:**` line appears on a scenario when a test covers it, and its
  absence means the check is manual.
- **The primary consumer is a coding agent.** Every document carries code anchors, so the reader
  reaches the code in one hop. Do not duplicate what lives in code (DTO fields, config keys); give
  the path. A copy rots, a path does not.
- Language: **English** — documents, code, comments, test names, commit messages and pull requests
  alike.

## Templates

`templates/` holds a copy of the document templates, so the format travels with the repository.
Sections marked `<!-- optional -->` can be deleted.

## Checks

```bash
pip install pyyaml
make check
```

`make check` is the gate and CI runs exactly that target; `make report` is the two non-blocking
reports and `make fix` regenerates the backlog index and fills in missing coverage-map lines.

## Coverage map

The list below is **checked** against the files on disk: a document missing here, or an entry with
no file behind it, fails `coverage_map.py`. The grouping and the descriptions are written by a
person — the machine only guards the membership.

### Research (1)

- [x] [research-architecture](research/research-architecture.md) — what was verified and against
  what, the decisions and their rejected alternatives, the risks with their machinery

### Services (2/2)

- [x] [xyk-server](services/xyk-server.md) — the binary: ingest, delivery, journal, one SQLite file
- [x] [xyk-twin-go](services/xyk-twin-go.md) — the Go twin of the ingest path; a fixture, never
  shipped

### Features (5)

The inbound half:
- [x] [feature-ingest](features/feature-ingest.md) — accept, verify, store with the timers, answer
  `200`
- [x] [feature-signature-verification](features/feature-signature-verification.md) — the schemes,
  their exact shapes, and what each of them actually proves

The outbound half:
- [x] [feature-delivery](features/feature-delivery.md) — POST with a timeout, retries, backoff, dead
  letters

For the operator:
- [x] [feature-journal](features/feature-journal.md) — what arrived and what happened to it
- [x] [feature-endpoint-registry](features/feature-endpoint-registry.md) — endpoints, write-only
  secrets, subscribers

### API (4)

- [x] [endpoint-ingest](api/endpoint-ingest.md) — the one inbound route and every status it answers
- [x] [endpoint-journal](api/endpoint-journal.md) — the pages and the JSON behind them
- [x] [endpoint-admin](api/endpoint-admin.md) — endpoints, secrets, subscribers
- [x] [endpoint-ops](api/endpoint-ops.md) — three probes and `/version`; no parent feature, they
  belong to the process
