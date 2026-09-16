---
id: B-18
title: "The scratch variant: five paths, and a smoke test that renders a page"
status: done
priority: P1
size: M
stage: stage-2-image
blocked_by: [B-05, B-17]
---

# B-18 — The scratch variant

**No longer conditional (2026-09-15).** It was written as "only if the criterion still needs the last
few megabytes"; B-01 measured the base image at 14.2 MB and B-05 measured the shipping binary at
19.4 MB static, so `scratch` is the only route to criterion 3 and its first job is to produce a real
number rather than to shave one. The static link itself is settled — four builds, all of which
linked ([link-probe](../research/measurements-2026-09-15/link-probe.md)) — and
`-Pxyk.staticLink=true` is the switch. "Static" does not mean self-contained, and the gap is specific: glibc
has no built-in converters, Ktor's charset layer on native **is** glibc `iconv`, and `iconv_open`
loads its converters with `dlopen`. An image with the binary alone starts, serves static files, and
returns `500` on the first rendered page
([research §1.7](../research/research-architecture.md)).

- **Decision: five paths beside the binary, copied out of the build stage** — `ld.so.cache`, the
  loader, `libc.so.6`, the **whole** gconv directory, and `zoneinfo`. Out of the build stage because
  `dlopen` from a static binary needs the same glibc build the `libc.a` came from; copies from the
  host gave the same error.
- **Decision: the whole gconv directory, not one module.** glibc picked `UTF-16.so` to convert UTF-8;
  the set of reachable modules is not something a `COPY` line should predict. The price is known:
  curating it down saved 2 811 555 bytes elsewhere, at the cost of a `500` on an unusual `charset=`.
- **Condition: the binary must be linked inside the image.** The `libc.so.6` copied beside it has to
  be the same build the `libc.a` was linked against, and a runner that updates itself silently breaks
  that agreement. This trades build minutes for image bytes, and the trade is the decision.
- **`-Xoverride-konan-properties` is not a stable interface** — five pinned keys, which JetBrains may
  change in a patch release. It breaks loudly, but only where the image is built, so the image build
  runs on every pull request and not only on a release.
- Not covered: `linuxArm64`, which has never been attempted with this recipe.

- AC: `dev/image-smoke.sh` signs a request, sends it, and requires the journal page to render a
  timestamp. A smoke test that stops at a status code passes on an image that renders nothing.
  **Done**, and it passes on every `scratch` variant — **including the one built to fail**, which is
  the finding below.
- AC: pull bytes for both variants in one table, measured on one host, with the method named.
  **Done** — six variants, `docker save | wc -c`, the build machine.

## Closed 2026-09-15

`docker/scratch.Dockerfile` links the binary **inside** the image, as it must: the `libc.so.6`
copied beside it has to be the same glibc build the `libc.a` came from. Two minutes of build against
a second of assembly, which is the trade this file exists to make.

**Every measurement, one host, `docker save | wc -c`:**

| image | pull bytes |
|---|---|
| `distroless/cc`, no HTTP client (ships today) | 14 324 224 |
| `distroless/cc` + curl (what delivery needs) | 17 918 976 |
| **`scratch`**, no HTTP client | **8 369 664** |
| `scratch`, no client, **no gconv** | 5 537 792 |
| **`scratch` + curl** | **11 822 592** |
| `scratch` + curl, **no gconv** | **8 990 720** |

The `scratch + curl` image renders pages, stops through the full kore transcript, and reaches
`https://repo1.maven.org` with a `200` from inside itself. Its binary is 20 163 520 bytes,
statically linked, with 47 curl strings in it — checked, because the variant is invisible in the
artefact and the build now prints `xyk build: httpClient=… staticLink=…` for the same reason.

**The gconv control did not fire, and that is the result rather than a footnote.** An image built
deliberately without the charset converters renders every page of this service — with Cyrillic and
Japanese in the data, with percent-encoded non-ASCII in the query and the path, with
`charset=utf-8` on the body. The mechanism is in the binary (`strings` finds `iconv_open`); the call
site is not. Full table and consequences in
[research §1.14](../research/research-architecture.md).

**So the image smoke test still has no working negative control**, which B-12 expected this item to
supply. Said plainly rather than left implied.

**And the criterion is now a decision rather than a distance.** 11 822 592 with gconv misses the
10 MB line by 1.82 MB; 8 990 720 without it meets the line with a megabyte to spare, on an image
that renders everything this service serves. Whether to spend 2 831 872 bytes on insurance against a
call site this service does not have is an owner's call, not an implementer's — it is
[B-23](B-23-criterion-image-size.md)'s open question now, and the default stays **with** gconv until
somebody answers it.

**Not covered:** `linuxArm64`, which has never been attempted with this recipe.
- Anchors: `docker/scratch.Dockerfile`, `dev/image-smoke.sh`, `server/build.gradle.kts`
