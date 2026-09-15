---
id: B-05
title: "The static-link probe: does the curl engine make a static link impossible?"
status: done
priority: P0
size: S
stage: stage-0-foundations
---

# B-05 — The static-link probe: does the curl engine make a static link impossible?

The brief carries a kill condition: *if the curl dependency makes even the incoming half impossible
to link statically, that is a result and not a failure.* This item is the experiment that answers
it, and it runs **before** any feature code exists to be thrown away.

The incoming half needs no curl at all — Ktor CIO server plus kotlincrypto, all pure Kotlin. The
outgoing half has exactly one option for HTTPS on native, `ktor-client-curl`, whose cinterop klib
carries `libcurl.a`, `libnghttp2.a`, `libssl.a` and `libcrypto.a` inside it and needs only `-lz`
from the system ([research §1.6](../research/research-architecture.md)).

- **The experiment: one trivial binary, four builds.** Server only and server+client engine, each
  linked dynamically and with `-static --no-dynamic-linker` plus the five pinned
  `konan.properties` overrides. What is recorded per build: does it link, the stripped size, and
  `readelf -d` on the result.
- **Decision: the overrides are *read* out of `konan.properties`, not rewritten from meaning.**
  `linkerKonanFlags` continues onto a second line, and a version rewritten from scratch loses
  `--gc-sections` and costs 316 488 bytes for nothing. That mistake has been made once already.
- **Decision: the answer goes into the research document whichever way it comes out.** A kill is a
  result: it names the platform's boundary, which is the article this work is for.
- Not covered: the image. Sizes here are binaries; images are [B-17](B-17-runtime-image.md) and
  [B-18](B-18-scratch-image.md).

- AC: a table in `docs/research/` with four rows, the build host named, and the exact link command.
  **Done** — [measurements-2026-09-15/link-probe.md](../research/measurements-2026-09-15/link-probe.md).
- AC: the `g++` prerequisite is verified rather than assumed — `gradle:*-noble` carries no `libc.a`,
  no `crt1.o` and no gcc directory, and the failure reads as a linker-flag problem. **Done**: the
  Linux box has gcc/g++ 13.3.0, `libc.a`, `crt1.o`, `libstdc++.a` and `libz.a`, checked before the
  first link rather than after its error. The builder **image** still has to be checked separately
  when B-18 moves the link inside it — that is where the `-lc` failure lives.
- AC: if the static link of the **incoming half alone** fails, this item closes as the kill and
  [research §1.6](../research/research-architecture.md) records what refused and why. **It did not
  fail.**

## Closed 2026-09-15 — the kill condition did not fire, and the real finding is elsewhere

| Variant | Link | Bytes |
|---|---|---:|
| server only | dynamic | 9 662 448 |
| server only | **static** | **10 608 480** |
| server + `ktor-client-curl` | dynamic | 18 945 736 |
| server + `ktor-client-curl` | **static** | **19 425 640** |

All four linked; the three native binaries that were run answered `/health/ready` and stopped
through the full kore transcript, static and with the engine included.

**The engine costs 8 817 160 bytes in a static link — it nearly doubles the binary — and that cost
cannot be moved into the image**, because libcurl, libnghttp2, libssl and libcrypto are static
archives inside the engine's own klib. `libz` is the only thing the system supplies.

So the boundary is a **size** boundary and it lands on [B-23](B-23-criterion-image-size.md), not on
the kill condition. What this run deliberately does not settle: whether a `scratch` image of this
binary renders a page (it does not touch gconv), and what the engine costs at run time.

Left behind for the work that follows: `dev/static-probe.sh`, and the two build properties
`-Pxyk.httpClient` / `-Pxyk.staticLink` — the second is what B-18 turns on for the image.
- Anchors: `dev/static-probe.sh`, `server/build.gradle.kts`,
  `docs/research/research-architecture.md`
