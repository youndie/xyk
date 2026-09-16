# The delivery half grows without bound — B-30

**Date:** 2026-09-16. Host: the build machine, subject pinned to CPUs 0–3, generator to 12–19.
Subject: `xyk-soak:delivery` — the **shipping** image, `docker/scratch.Dockerfile` with
`XYK_HTTP_CLIENT=true`, so the static binary published to ghcr, default allocator
(`-Xbinary=pagedAllocator=false`, B-28). Load: 60 rps of signed ingest, four concurrent
`GET /journal` readers unless a row says otherwise, a subscriber answering immediately on the
generator's cores.

60 rps rather than the 200 the earlier soaks used, because the outbound half delivers sequentially
per worker: above its ceiling the run would measure a growing backlog instead of a steady state
([B-14](../../backlog/B-14-delivery-worker-count.md) puts four workers at ~97/s against an instant
subscriber). At 60 rps the backlog stays flat — 213–223 pending throughout — so what follows is not
a queue.

## The measurement

| arm | memory over the run | database file | outcome |
|---|---|---|---|
| **delivery off**, 4 readers, 64 MiB | 32 104 → 46 224 kB in 290 s | +13 MB | **survived** |
| delivery **on**, 4 readers, 64 MiB | 40 056 → the limit | — | **died at 91 s** (87 s, 91 s in two runs) |
| delivery **on**, 0 readers, 64 MiB | 32 888 → the limit | — | **died at 121 s** |
| delivery **on**, 4 readers, 128 MiB | 42 572 → 131 032 kB | — | **died at 256 s** |
| delivery **on**, 4 readers, 256 MiB | 43 184 → **173 888 kB** in 299 s | +13 MB | survived the run, **still climbing** |

Every death is `OOMKilled=true`, exit 137.

## It is growth, not footprint, and it is not the file

The 256 MiB arm is the one that says so, because it is the only one that was not cut short:

| | at 0 s | at 266 s | change |
|---|---:|---:|---:|
| `-wal` | 4 240 kB | 4 328 kB | **flat** |
| database | 1 624 kB | 14 580 kB | +12 956 kB |
| memory | 43 184 kB | 152 856 kB | **+109 672 kB** |
| threads | 36 | 37 | **flat** |

Memory rises **8.5× faster than every byte the process writes**, so page cache — which
`memory.current` does include — cannot account for it: even if every byte written were cached it
would be 13 MB of the 110. The thread count does not move, so this is also **not** the mechanism
recorded elsewhere in this portfolio, where resident memory follows the thread count.

The control settles the attribution. With delivery off, on the same host at the same rate with the
same four readers, memory grew 14 MB while the database grew 13 MB — growth equal to the file, which
is what page cache looks like. Turning delivery on adds ~96 MB over five minutes on top of that, at
roughly **6 kB per delivery** (about 18 000 deliveries in the 256 MiB run).

**No limit fixes it.** 64 MiB buys 90 seconds, 128 MiB buys 256, and 256 MiB was still rising at
174 MB when the run ended. The slope is ~440 kB/s at 60 rps.

## What is not established

**The mechanism.** The composition root builds one `HttpClient(Curl)` for the life of the process
and the sink reads each response to a bounded prefix, so nothing in
`OutboundEngine.native.kt` obviously accumulates. Whether this is the curl engine, the
Kotlin/Native GC declining to return pages, or something in the attempt path is **not measured**,
and this document does not guess. The discriminating run — 256 MiB for long enough to see whether
the curve plateaus or reaches the limit — has not been done.

**Whether it is new.** These are the first numbers ever taken on this service with the outbound half
running for longer than thirty seconds.

## Why nothing had seen it

Two harnesses look at memory and neither could:

* **`bench/memory-declared.sh`**, which answered the 64 MiB criterion
  ([B-21](../../backlog/B-21-criterion-memory.md), [B-29](../../backlog/B-29-memory-with-delivery-on.md)),
  runs rounds of **`DURATION=30s`**. The subject here dies at 87–121 seconds. A round that ends at
  thirty seconds cannot see a slope that kills at ninety, and ten of them cannot either — they
  restart the process each time.
* **`bench/soak.sh`**, which runs for twenty minutes, ran `xyk-mem:fixed16` — an allocator arm of
  the memory bench that **links no outbound engine at all**. `XYK_BOOTSTRAP_SUBSCRIBERS` was set and
  nothing could ever deliver. Both soaks of 2026-09-15 and 2026-09-16 measured a service with half
  its work missing, which is the same defect B-29 found in the memory criterion and fixed there.

The harness now takes `--delivery on`, and it **asks the subject** whether it can deliver rather
than trusting the tag: a build that answers `delivery is OFF` ends the run. It also asks the
subscriber, thirty seconds in, whether anything has arrived — the first wiring pointed the subject
at `127.0.0.1`, which inside a bridge container is the container, and an unreachable subscriber
produced the same OOM at 64 MiB **and at 96 MiB**. That is worth its own look and it is not this
finding; it is noted on the item.

## Both memory recipes applied, same stand — and the growth is outside the heap

Two portfolio recipes were missing from this service. `MALLOC_ARENA_MAX=2` was held back by a comment
naming B-21 as the measurement that would settle it; B-21 measured it (42 120 – 56 188 kB against
45 112 – 65 536) and closed without the setting being taken. A heap ceiling was absent entirely: a
Kotlin/Native process cannot read its own cgroup limit, so unless something sets `GC.maxHeapBytes`
the runtime grows on a schedule that ends in the kernel.

Both are now applied — the arena limit in both Dockerfiles, the ceiling as `XYK_HEAP_BYTES`, set
here to 32 MiB and **confirmed at start-up** (`GC.maxHeapBytes is now 33554432 B`) rather than
assumed. Same host, same 60 rps, four readers, delivery on:

| configuration | at 64 MiB | slope |
|---|---|---|
| neither | died at **87 / 91 s** | ~440 kB/s |
| `MALLOC_ARENA_MAX=2` | died at **123 s** | idle footprint 40 MB → 29 MB |
| both | died at **223 s** | |
| both, at 256 MiB | survived 5 min, 27 632 → **102 184 kB** | **~256 kB/s** |

Both recipes earn their place: the arena limit takes about 11 MB off the resting footprint, and the
ceiling roughly doubles the time to death again. Together they nearly halve the slope.

**And neither stops it, which is the finding.** With the managed heap hard-capped at 32 MiB the
process still reaches 102 MB — so **the part that grows is not the Kotlin heap**. That removes the
most comfortable explanation, which was that the GC simply did not know the limit. What is left is
native allocation on the delivery path: the curl engine and the OpenSSL it carries, the Rust half of
sqlx4k, or thread stacks. Which of those is not measured, and this document still does not guess.

The ceiling ships **off** by default for the same reason: nothing here has measured what fraction of
a limit should be heap, and a service that guessed one would trade a kernel kill for an
`OutOfMemory` and call it an improvement. The chart knows the limit it declares and is the right
place to derive the number from, once there is a number.

