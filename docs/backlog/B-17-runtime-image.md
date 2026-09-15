---
id: B-17
title: "The runtime image on distroless/cc, measured in pull bytes"
status: done
priority: P1
size: S
stage: stage-2-image
blocked_by: [B-01]
---

# B-17 — The runtime image on distroless/cc

The first image, and the one to beat. `gcr.io/distroless/cc-*` instead of `debian:*-slim` is one
line and about 65 MB, and its certificates are already there — verified by unpacking the image
rather than assumed.

- **Decision: the binary is linked on the runner and copied in.** Building Kotlin/Native inside
  `docker build` costs tens of minutes because the caches cannot persist between runs of a fresh
  builder.
- **Decision: the debian version is chosen by the glibc of whatever links the binary**, not by eye.
  A runner on 2.39 with `distroless/cc-debian12` (2.36) builds an image that dies at startup with
  `GLIBC_2.38 not found`. One command settles it, and it is re-run on every tag change:
  `docker run --rm <build-image> ldd --version | head -1`.
- **Decision: `readelf -d` on the real binary is recorded in the build log.** A new shared dependency
  — `libz` arriving with the curl engine is the likely one — must be visible in the build that
  introduced it, not in a container that fails to start.
- **Decision: every image number is "pull bytes", measured with `docker save | wc -c` on a named
  host.** `docker image inspect .Size` means the compressed size on one snapshotter and the
  uncompressed size on another: 9.5 MB and 27.9 MB have been reported for one image.
- Not covered: `scratch`, which is [B-18](B-18-scratch-image.md) and is only taken if the criterion
  still needs it.

**Half done at B-01 (2026-09-15):** `docker/native.Dockerfile` exists on `distroless/cc-debian13`,
the image runs, and the probes answer from inside it — 14 163 968 pull bytes. What is left is the
part that needs an HTTP client to exist: the https delivery check, `readelf -d` in the build log
(`libz` will arrive with the curl engine), and the `MALLOC_ARENA_MAX` assertion once
[B-21](B-21-criterion-memory.md) says whether to set it.

- AC: the image runs, answers the probes, and delivers over https from inside itself. **Done**, and
  with controls — see below.
- AC: `MALLOC_ARENA_MAX` — if the measurement in [B-21](B-21-criterion-memory.md) says to set it —
  is asserted by a CI step, because a measured setting that nothing asserts gets deleted. **Open,
  deliberately**: there is no measurement yet, and a line added now would be a setting nobody
  measured, asserted by a check nobody can justify. It moves with B-21.

## Closed 2026-09-15

**https works from inside the image, and the check was shown to fail before it was believed.** The
probe is `XYK_TLS_PROBE=<url>` on the `with-curl` build — environment-driven, never request-driven —
and it makes one real `GET` from inside the container:

| | |
|---|---|
| `distroless/cc-debian13` as it ships | `GET https://repo1.maven.org/maven2/ -> 200` |
| the same image with the CA bundle masked (`-v /dev/null:/etc/ssl/certs/ca-certificates.crt`) | `failed: IllegalStateException: … Reason: Problem with the SSL CA cert` |
| a hostname that does not resolve | `failed: … Reason: Could not resolve hostname` |

So the certificates really are in `distroless/cc` — verified by using them rather than by unpacking
the image — and the check would have caught their absence. **Both failures arrive as
`IllegalStateException`**, which is why the probe prints the message and not only the type: the type
does not distinguish a missing CA bundle from a missing DNS answer, and those are different
incidents.

**`readelf -d` now runs on every image build** (`dev/binary-report.sh`, wired into `make image`), and
it earned itself immediately: `libz.so.1` appears in the NEEDED list **exactly** when the curl engine
is linked and not before — the prediction from [B-05](B-05-static-link-probe.md), confirmed by the
tool that was put there to catch it rather than by rereading a manifest.

**The glibc pairing is checked by the thing it is about.** The builder is glibc 2.39; `distroless`
has no shell to ask, so what was verified is stronger than a version string — a binary linked here
starts and serves there.

**Sizes, and they are bad news for [B-23](B-23-criterion-image-size.md).** The shipping build today
(no HTTP client): binary 10 145 072 bytes, image **14 288 384 pull bytes**. The build that delivery
will need: binary **19 628 456**, image **17 918 976 pull bytes** — nearly twice the 10 MB line
before a single delivery is made.
- Anchors: `docker/native.Dockerfile`, `.github/workflows/build.yaml`
