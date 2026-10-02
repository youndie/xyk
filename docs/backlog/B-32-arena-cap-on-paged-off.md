---
id: B-32
title: "What MALLOC_ARENA_MAX=2 does on the pagedAllocator=false build"
status: wip
priority: P1
size: S
stage: stage-3-verdict
epic: feature-ingest
blocked_by: []
---

# B-32 — `MALLOC_ARENA_MAX=2` on the `pagedAllocator=false` build

The image ships two memory settings that were never measured together. `MALLOC_ARENA_MAX=2` was
taken in B-21 on the `fixedBlockPageSize=16` build, where Kotlin keeps its own pages and malloc sees
little; B-28 then moved the binary to `-Xbinary=pagedAllocator=false`, which sends every Kotlin
allocation to malloc. The one measurement of the cap over malloc-backed Kotlin — `-Xallocator=std`
on another service, research §1.8 — was a tenfold peak and three kills in ten. Both Dockerfiles say
"this image ships `pagedAllocator=false`, not that"; nobody has shown that the difference matters.

The pre-registration — arms, series, units, thresholds, controls, the decision rule — is
[arena-cap-brief.md](../research/measurements-2026-10-02/arena-cap-brief.md), frozen before the
first run:

<!-- frozen: path=docs/research/measurements-2026-10-02/arena-cap-brief.md bytes=8244 sha256=d127548aff7011e5d06e3b39c16d8e58a0e8290d7822c99c071e3aadced94e5e scope=after-marker -->

- AC: `bench/memory.sh` runs the three series of the brief with the 6 MiB control killed in each,
  the arms interleaved, and the lever (the subject's own environment and heap count) read from the
  subject in every round.
- AC: `bench/paired.py --selftest` passes, and every comparison in the results is its output.
- AC: a verdict word per question (green, grey, red, not measured) in
  `docs/research/measurements-2026-10-02/arena-cap.md` and research §1.8, and the
  `MALLOC_ARENA_MAX` comment in `docker/scratch.Dockerfile` and `docker/native.Dockerfile` states
  it; if the decision rule says remove, the line goes and the image smoke runs green.
- Anchors: `bench/memory.sh`, `bench/paired.py`, `docker/scratch.Dockerfile`, `docker/native.Dockerfile`
