---
id: B-27
title: "A port already in use is reported as a cancelled coroutine, after the start was announced"
status: done
priority: P0
size: S
stage: stage-1-product
epic: feature-ingest
---

# B-27 — A port already in use is reported as a cancelled coroutine

Found while restarting the benchmark arms, not by anything that was being measured
([three columns](../research/measurements-2026-09-16/throughput-three-columns.md)). The binary died
on start **6 times out of 12**, strictly alternating — every failure was a run that began while the
previous instance still held the port.

What the operator gets:

```
xyk: migrated to schema version 7
xyk: /api/** has no authentication of its own — the deployment is responsible
[INFO] (io.ktor.server.Application): Application started in 0.003 seconds.
Uncaught Kotlin exception: kotlinx.coroutines.JobCancellationException: LazyStandaloneCoroutine is cancelling; job=…
Caused by: io.ktor.utils.io.errors.PosixException.AddressAlreadyInUseException: EADDRINUSE (98): Address already in use
```

exit code **134**, core dumped.

Three separate problems, and the port conflict is the least of them:

- **The service announces a successful start and then dies.** `Application started in 0.003 seconds`
  is logged before the connector is bound, so the log's last cheerful line is a lie. On a
  crash-looping pod that line is what a reader anchors on.
- **The cause is on the fifth line.** The exception that gets read first says
  `LazyStandaloneCoroutine is cancelling`, which names nothing an operator can act on. `EADDRINUSE`
  is in a `Caused by` underneath it.
- **It aborts rather than exits.** A core dump for a misconfiguration is noise in the place where
  real crashes are looked for.

The mechanism is `start(wait = false)`, which is itself correct and is there for a reason — the
shutdown sequence needs the main thread ([research §1.10](../research/research-architecture.md)). The
consequence is that binding happens asynchronously, so a bind failure surfaces as a cancellation of
whatever scope noticed it rather than as a start-up failure.

- **Decision: the start is not announced until the connector is resolved.** Ktor's engine can be
  asked; `markStarted()` and the log line move behind that answer.
- **Decision: a bind failure exits with a sentence and a code, not a stack.** "port 8080 is already
  in use" is the whole message an operator needs, and the stack belongs to failures nobody predicted.
- **Rejected: retrying the bind.** Two instances on one port is a deployment mistake, and a service
  that waits for the other one to go away hides it until something else breaks.
- Not covered: every other start-up failure that arrives asynchronously. This item fixes the one that
  was measured; a general answer needs its own item and a list of what else can fail that way.

- AC: with the port held, the process prints one line naming the port and exits non-zero **without**
  a stack trace and **without** having claimed to start.
- AC: the alternating-restart reproduction runs 12 times out of 12 alive once the port is free, and
  12 times out of 12 with a clean message when it is not.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/xyk/Main.kt`

## Fixed by asking before starting, 2026-09-16

**The obvious fix does not work, and finding that out is half the item.** The first attempt awaited
`server.engine.resolvedConnectors()` after `start(wait = false)` and reported the failure from there.
It changed nothing: on Kotlin/Native an unhandled exception in a coroutine aborts the process, and by
the time `resolvedConnectors()` could answer, the process is already dying. A failure that kills the
runtime cannot be caught downstream of itself.

So the question is asked **before the engine exists**: `preflightBind()` binds the address, closes
it, and the engine starts afterwards.

| | before | after |
|---|---|---|
| exit code | 134, core dumped | **78** (`EX_CONFIG`) |
| stack lines | 15+ | **0** |
| first line about the failure | `JobCancellationException: LazyStandaloneCoroutine is cancelling` | `xyk: cannot bind 0.0.0.0:8071 — EADDRINUSE (98): Address already in use` |
| claimed to start first | yes — `Application started in 0.003 seconds` | **no**; Ktor never reaches that line |
| 12 alternating restarts | 6 alive, **6 core dumps** | 6 alive, **6 clean refusals, 0 crashes** |

**The race is real and is the right trade.** Between releasing the preflight socket and Ktor's own
bind, another process could take the port, and then the old crash returns. What this converts is the
case that actually happens — an instance already running, or one still shutting down — from an
unreadable abort into a line naming the address. A fix that handles the common case and says so beats
one that waits for an answer with no race in it.

**Exit 78 rather than 1**, because a supervisor restarting on any non-zero code would restart this
one for ever against a port that is not coming back.

**The `catch (Exception)` rethrows `CancellationException` first even though nothing can cancel it
here** — it runs on the main thread before any scope, signal handler or job exists. The rethrow is
one line, and the claim it would protect is about the code as it is today rather than as somebody
leaves it.

- AC: **met** — one line, the port in it, no stack, non-zero, and no claim of having started.
- AC: **met** — 12 restarts: 6 alive, 6 clean refusals, 0 crashes.
- Verified unchanged: the stop order still reads `SIGNAL ANNOUNCE DRAIN RELEASE_CONSUMERS
  RELEASE_POOLS EXIT`, all `COMPLETED`, against the image — the preflight's `SelectorManager` is
  closed before the engine starts and leaves nothing behind.
