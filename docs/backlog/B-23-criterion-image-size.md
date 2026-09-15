---
id: B-23
title: "Criterion: the image is at most 10 MB"
status: open
priority: P1
size: S
stage: stage-3-verdict
blocked_by: [B-17]
---

# B-23 — Criterion: the image is at most 10 MB

**Declared before the code, on 2026-09-15:** the published image is at most 10 MB.

**And declared with it: "10 MB" means pull bytes**, measured with `docker save | wc -c` on a named
host. `docker image inspect .Size` reports the compressed size on the containerd snapshotter and the
uncompressed size on overlay2 — 9 569 623 and 27 918 036 have been recorded for the same image on two
machines. A criterion stated in a field whose meaning depends on the host is not a criterion.

The comparable number: a service of this shape — Ktor on Kotlin/Native rendering pages out of SQLite
— fits in **9 570 311 bytes** on `scratch` with the whole gconv tree, against 15 542 820 on
`distroless/cc`. It does **not** link an HTTP client. xyk does, and the static libcurl + OpenSSL in
the curl klib is the largest unknown in this repository
([research, Risk 1](../research/research-architecture.md)).

## The criterion is now one decision away (B-18, 2026-09-15)

| image | pull bytes | against the 10 MB line |
|---|---|---|
| `scratch` + curl, with gconv | 11 822 592 | **over by 1 822 592** |
| `scratch` + curl, without gconv | 8 990 720 | **under by 1 009 280** |

Both render every page this service serves, including non-ASCII data
([research §1.14](../research/research-architecture.md)). The difference is 2 831 872 bytes of
charset converters that nothing here currently calls — and that the day something does, their
absence is a `500` in production no test would catch.

**This is the owner's decision, and it is the last one between the criterion and a verdict.** Meet
the declared number by dropping insurance, or miss it by 1.8 MB and say so. The default until
answered is with gconv, because the failure it prevents is silent.

**The number that will actually ship, measured at B-17 (2026-09-15): 17 918 976 pull bytes** — the
`with-curl` build on `distroless/cc-debian13`, binary 19 628 456 bytes. That is 1.8× the criterion
before delivery exists, and it is the honest figure to compare `scratch` against.

**Tracked as the product grows.** B-12 (the journal page): binary 10 145 072 bytes, image
**14 288 384 pull bytes** — the page cost 483 KB of binary and 124 KB of image.

**Two measurements, both 2026-09-15.** B-01: **14 163 968 pull bytes** on `distroless/cc-debian13`
with a 9 662 208-byte binary and no HTTP client. B-05: the binary that will actually ship — static,
with `ktor-client-curl` — is **19 425 640 bytes** before compression, because the engine carries
libcurl and OpenSSL inside its klib and nearly doubles the binary
([link-probe](../research/measurements-2026-09-15/link-probe.md)).

The criterion is compressed pull bytes, so the second number is not a verdict — but the comparable
service that fit in 9 570 311 pull bytes did it with a 16.5 MB binary, and this one is 2.9 MB larger
before a feature exists. Treat criterion 3 as **open and unlikely**, settled only by
`docker save` on a real `scratch` image.

Superseded text, kept because it was the reasoning at the time: The criterion is missed by 4.2 MB before the thing most
likely to cost megabytes is even linked in. That moves `scratch` from "if it is still wanted
afterwards" to "the only route", and it moves [B-05](B-05-static-link-probe.md) to the front.
(B-05 has since run; the engine's cost is now measured rather than expected.)

- **The order of attempts, and each with its price stated when taken:** distroless/cc
  ([B-17](B-17-runtime-image.md)); `scratch` ([B-18](B-18-scratch-image.md)); curating gconv down
  (−2.8 MB, and a `500` on an unusual `charset=`); accepting distroless and missing the criterion by
  about 6 MB, said out loud.
- **Decision: "shrink the binary" is priced honestly.** The only contributor large enough to matter
  elsewhere was a feature the service actually serves — removing the largest removable dependency
  took a binary 16 704 336 → 13 495 008 bytes. On a service this size, "make it smaller" and "drop
  something" are the same sentence.
- **Decision: a size report ranks candidates, it does not predict savings.** Package attribution is
  direct contribution only: it undercounted one removal by two and a half times, because transitive
  pull-in and the dead code eliminated afterwards are not in that row.
- **Rejected: chasing symbol tables.** A quarter of the file is debug info attributed to nobody — it
  costs a `pull` and not a second of link time, so stripping it moves this criterion and nothing
  else. Which is exactly why it is done, and why it is not called an optimisation.

- AC: pull bytes for every variant in one table, one host, the method in the caption.
- AC: a CI step fails the build when the published image crosses the line, so the criterion is a gate
  and not a memory.
- Anchors: `docker/native.Dockerfile`, `docker/scratch.Dockerfile`, `.github/workflows/build.yaml`
