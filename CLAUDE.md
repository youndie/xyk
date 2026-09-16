# xyk — instructions for an agent working in this repository

## How to start a session

1. **[docs/research/research-architecture.md](docs/research/research-architecture.md)** — first,
   always. It says what was actually verified and where, which decisions were taken and what was
   rejected. A task read without it looks like "do the obvious thing", and here the obvious thing is
   frequently wrong: chronik has no native artifacts, a static glibc is not self-contained, and the
   memory criterion is expected to fail for a reason that is written down.
2. **[backlog.md](backlog.md)** — the stage the task belongs to, the item, and what it is blocked by.
3. **The layer document for the thing you are changing** — `docs/features/` for behaviour,
   `docs/api/` for a route, `docs/services/` for how a module is put together.
4. Grep the documentation for claims about what you are about to add, **including negative ones**
   ("there is no route for…"). A stale sentence fails the gate.

## The invariant

`main` describes what **exists**. An open pull request describes what **will be**. A document about
unbuilt behaviour is `status: draft` and lives in a branch — which is where all of them are today.
When code lands, the document that described it flips to `active` in the same pull request.

Do not write a number you have not measured, and do not write a status code you have not read out of
the code. Where something is not known, say the document does not cover it: an admitted gap costs a
reader nothing, an invented detail costs them the whole file.

## The gate

```bash
make check     # documents + ./gradlew check — needs the Linux box
make docs      # the documentation half only; runs anywhere
make build     # link, image, and the assertion that the process stops in order (needs docker)
```

`make check` is exactly what CI runs. `make report` is the two non-blocking reports; `make fix`
regenerates the backlog index and fills in missing coverage-map lines with placeholders you then
finish.

`code_anchors.py` resolves most paths now that the backlog is finished. What it still reports are
addresses **inside other repositories and artefacts** — a line in chronik's sources, a key in
`konan.properties` — which it cannot resolve and should not: those are verification addresses for a
research claim, not paths in this tree. It does not block, for that reason.

## Where code runs

**A release link is minutes of LLVM**, so builds, tests and `docker build` want a machine with cores
rather than whatever is under your hands. `macos*` targets are the exception: they have to be built
on macOS.

Two consequences worth knowing wherever the build happens. A build context without `.git` stamps
`/version` with `commit: unknown` — that is what a `.dockerignore` normally produces, and a file git
tracks but the ignore excludes reads as *deleted*, which makes the stamp `-dirty` permanently. And
work done only on the build machine reaches neither git nor the checkout you are editing.

**The benchmark harnesses in `bench/` need two machines**, and they refuse to take a number on one:
the generator must not run on the host under test, because it competes for exactly the cores being
measured. `SUBJECT` and `GENERATOR` are ssh destinations and have no useful defaults.

## Language

English, everywhere: documents, identifiers, comments, KDoc, test names, exception messages, commit
messages, pull request titles and bodies. Commits are Conventional Commits.

## Skills that apply

- **`native-service-bootstrap`** — the skeleton: targets, `expect/actual` config, migrations before
  the engine, kore's stop order, the allocator options, the image. Everything up to `/health`
  answering from the image.
- **`ktor-server-feature`** — everything after that: a route, a use case with typed errors, a
  repository behind an interface, a DI binding. **The second endpoint in this service is already a
  feature**, and the mistake to avoid is treating feature work as a continuation of standing the
  service up — elsewhere that produced a server with no layers and a document promising a parameter
  the code never had.
- **`kmp-testing`** for suites, **`docs-bootstrap`** for the documentation format.

## Two things specific to this repository

**The raw bytes of a request are the subject of a signature.** Nothing on the ingest path may parse,
re-encode or pretty-print a body before it has been verified and stored. This is the single most
common way a webhook receiver breaks, and it breaks silently in the direction of rejecting genuine
traffic.

**A measurement without a positive control is not a measurement.** If an arm of a run is supposed to
die and nothing dies, the harness has not been shown able to notice a failure — re-run it, do not
report it.
