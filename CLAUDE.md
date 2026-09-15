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

`code_anchors.py` currently reports nearly every path as missing, and that is the honest state: the
anchors name files the backlog is about to create. It does not block, for the same reason.

## Where code runs

Builds, tests and `docker build` go to the Linux box, not to this Mac — see the global instructions.
Kotlin/Native links are minutes of LLVM and the Mac is a text editor here. `macos*` targets and
anything touching Xcode stay local.

The project is in `mutagen sync list` (added 2026-09-15), so `~/.claude/bin/wsl-run ./gradlew …`
works from this directory. Two consequences of the replica, both already seen: it carries no `.git`,
so `/version` stamps `commit: unknown` there, and work done on the Linux side reaches neither git nor
the Mac.

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
