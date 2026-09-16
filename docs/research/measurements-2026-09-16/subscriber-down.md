# A subscriber that is down — B-31

**Date:** 2026-09-16, same stand as [delivery-memory-growth.md](delivery-memory-growth.md): the
shipping image with both memory recipes (`MALLOC_ARENA_MAX=2`, `XYK_HEAP_BYTES=33554432`), 60 rps of
signed ingest, four `GET /journal` readers, subject pinned to CPUs 0–3.

The only thing that changes is where deliveries go. Three ways for a subscriber to be down, because
they are not the same failure:

* **refused** — `http://127.0.0.1:9/hook`, which inside the container is the container and answers
  `ECONNREFUSED` at once;
* **a black hole** — `http://10.255.255.1:8080/hook`, routed and silent, so each attempt runs to the
  2-second delivery timeout;
* (DNS failure was the accidental arm that started this and is not repeated here.)

## What happened

| arm | runs | outcome |
|---|---:|---|
| refused, 64 MiB | 2 | **bimodal** — one rode the limit for the full five minutes at 65 480 kB with 3 442 deliveries pending; one was **OOM-killed inside 30 s** |
| refused, 256 MiB | 2 | **died both times**, at 285 s and at 91 s |
| black hole, 256 MiB | 2 | survived both, memory +25.8 and +32.0 MB, **15 020 pending** at the end |

## A failed attempt costs several times what a delivered one costs

The per-attempt figure is the whole finding, and it is the same mechanism as
[B-30](../../backlog/B-30-delivery-memory-growth.md) rather than a second one — `ktor-client-curl`
grows per request, and a failure is still a request:

| outcome of the attempt | memory per attempt |
|---|---:|
| `200` from a working subscriber | **~2 kB** |
| connection refused | **~14 kB** |
| timed out at 2 s | **~60 kB** |

Read off the runs: refused at 256 MiB grew 89 728 → 240 320 kB while attempts went 4 762 → 15 453
(150 MB over 10 691 attempts); the black hole grew 23 676 → 49 456 kB over 54 → 492 attempts (26 MB
over 438). Two runs an arm, and the black-hole ratio reproduced closely; the refused one is the
weaker of the two.

**So a subscriber being down is not a quiet state.** The intuition that a gateway with nowhere to
deliver simply idles is wrong twice over: it still makes the request, and the request costs more
when it fails than when it succeeds.

## The limit throttles the thing that consumes it, which is why 64 MiB is bimodal

At 64 MiB the refused arm that survived made **3 611 attempts in five minutes**; at 256 MiB the same
arm made **18 561**. The smaller limit does not make the service more careful — it starves it, the
workers get less done, and fewer attempts are made, which is exactly what keeps memory under the
limit. Surviving that way is not a healthy state: five minutes in, 3 442 events had been accepted
with a `200` and not delivered, and the database had grown 1.6 MB against 10.3 MB in the arm that
was allowed to work.

Which of the two modes a run takes is not determined by anything the harness sets, and both were
seen at the same limit with the same image — so "it survives at 64 MiB with a dead subscriber" is
not a claim this supports.

## The backlog is unbounded independently of memory

The black-hole arm is the clearest: each attempt occupies a worker for the full two seconds, so four
workers make about two attempts a second while ingest brings in sixty events a second. Five minutes
in, 15 020 deliveries are pending and the number is still climbing linearly. No memory limit is
involved in that one — it is arithmetic, and it applies to any subscriber slow enough.

## Not covered

* **Recovery.** Every arm here keeps the subscriber down for the whole run. What happens when it
  comes back — whether the backlog drains, and at what cost — is not measured.
* **DNS failure as its own arm.** It behaved like the others when it appeared by accident, and it
  was not re-run deliberately.
* **Whether the per-attempt figures hold at other rates.** One rate, 60 rps, throughout.
