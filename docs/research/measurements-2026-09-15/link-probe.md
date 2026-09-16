# B-05 — the static link, with and without the outbound engine

**Date:** 2026-09-15. **Host:** the build machine — a 20-core x86-64 Linux workstation,
glibc 2.39 (`2.39-0ubuntu8.9`), gcc 13.3.0, Kotlin 2.4.10, Ktor 3.5.2.
**Subject:** `:server` at B-01 — Ktor CIO server, sqlx4k/SQLite, kore, Koin — release `linuxX64`.

## What was run

```bash
dev/static-probe.sh
# which is, four times:
./gradlew :server:linkReleaseExecutableNative -Pxyk.httpClient=<false|true> -Pxyk.staticLink=<false|true>
```

The static arm adds `-static --no-dynamic-linker -L/usr/lib/x86_64-linux-gnu` (plus `-lz` when the
engine is present) and pins five `konan.properties` keys; the exact values are in
`server/build.gradle.kts` and are the stock ones with `-Bdynamic` removed.

The engine variant is a source directory, not a flag in the code: `httpEngineMarker()` has one
`actual` per variant, and the `with-curl` one **constructs and closes a real `HttpClient(Curl)`**
which `main` calls on every start. Without a reachable call site `--gc-sections` would remove
libcurl and the arm would measure a binary that contains no curl.

## Results

| Variant | Link | Bytes | Dynamic section |
|---|---|---:|---|
| server only | dynamic | 9 662 448 | `libm libpthread librt libdl libgcc_s libc ld-linux` |
| server only | **static** | **10 608 480** | none |
| server + `ktor-client-curl` | dynamic | 18 945 736 | `libm libpthread librt` **`libz`** `libdl libgcc_s libc ld-linux` |
| server + `ktor-client-curl` | **static** | **19 425 640** | none |

All four linked. All three native binaries that were run — static without the engine, static with it,
dynamic with it — started, answered `/health/ready` with `200`, printed their engine marker, and
stopped through the full kore transcript ending in `EXIT COMPLETED`.

## What this says

**The kill condition did not fire.** The incoming half links statically on its own, and so does the
whole binary with the engine in it. The brief's boundary — "curl makes even the incoming half
impossible to link statically" — is not where this platform's limit is.

**Static costs 946 032 bytes** on the engine-less binary (+9.8%), which is the same direction and
nearly the same magnitude as the 893 312 bytes measured on another service. The saving from static
linking is never the binary; it is the base image that stops being needed.

**The engine costs 9 283 288 bytes dynamically and 8 817 160 statically** — it very nearly *doubles*
the binary. That is libcurl, libnghttp2, libssl and libcrypto, which the engine's cinterop klib
carries inside itself as static archives: the price is paid whether the rest of the binary is linked
statically or not, and it cannot be paid in the image instead, because there is no shared libcurl
involved at any point.

**`libz` is the one thing the system supplies**, and it shows up exactly where the earlier reading of
the klib's manifest said it would: in the dynamic section of the engine arm and nowhere else.

## What this does **not** say

* **Nothing about `scratch`.** These binaries were run on a host that has gconv modules, a loader and
  certificates. A static Kotlin/Native binary still loads charset converters with `dlopen`, so an
  image without them serves `/health/ready` — as these runs did — and fails on the first rendered
  page. The check that settles it is an image smoke test that reaches rendered output (B-18).
* **Nothing about certificates.** The engine was constructed and closed, not used. The first real
  outbound request is B-10, and on a minimal image it needs a CA bundle put there deliberately.
* **Nothing about the runtime cost of the engine** — its single-threaded dispatcher, its concurrency
  ceiling. Those are B-14.
