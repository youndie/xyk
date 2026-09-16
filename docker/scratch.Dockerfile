# The `scratch` image: no base image under the binary at all.
#
# WHY IT EXISTS. `distroless/cc` costs about 4 MB of the pull, and the criterion is 10 MB against a
# binary that is 19.6 MB linked with its HTTP client (B-05, B-17). This is the only route left to
# that line, and its first job is to produce a number rather than to shave one.
#
# **THE BINARY IS LINKED INSIDE THIS FILE, and that is the condition, not a convenience.** The
# `libc.so.6` copied beside it below comes out of the build stage and has to be the same glibc build
# the `libc.a` was linked against: `dlopen` from a static binary demands it, and copies from the host
# or from another image of the same version produce the same error as no copy at all. Everywhere else
# in this repository the binary is linked on the runner, for the minutes; here it cannot be.
#
#   docker build -f docker/scratch.Dockerfile -t xyk:scratch .
#
# STATIC DOES NOT MEAN SELF-CONTAINED. glibc has no built-in charset converters — even UTF-8 arrives
# from a gconv module that `iconv_open` loads with `dlopen` — and Ktor's charset layer on
# Kotlin/Native *is* glibc `iconv`. An image with the binary alone starts, answers `/health/ready`
# with `200`, and returns `500` on the first rendered page. That is why `dev/image-smoke.sh` insists
# on reading a timestamp out of the HTML, and why this image carries five things and not one.
FROM --platform=linux/amd64 gradle:9.7.1-jdk25-noble AS build

# `gradle:*-noble` carries the right glibc and NOT ONE STATIC ARCHIVE: no `libc.a`, no `crt1.o`, no
# `/usr/lib/gcc/x86_64-linux-gnu` at all. The link then fails with `unable to find library -lc`,
# which reads like a linker-flag problem and is one `apt-get install` away. `zlib1g-dev` is for the
# curl engine, which asks the system for `-lz` and nothing else.
RUN apt-get update \
 && apt-get install -y --no-install-recommends g++ zlib1g-dev \
 && rm -rf /var/lib/apt/lists/*

# Which variant to link. `false` is the shipping build today; `true` adds the curl engine, which is
# what the delivery half will need and what B-23 has to compare against.
ARG XYK_HTTP_CLIENT=false

WORKDIR /app
COPY . .

# The cache mounts are what make a second build minutes rather than tens of minutes: without them a
# fresh BuildKit builder downloads the Kotlin/Native distribution on every run.
RUN --mount=type=cache,target=/root/.konan \
    --mount=type=cache,target=/root/.gradle \
    gradle :server:linkReleaseExecutableNative \
      -Pxyk.staticLink=true -Pxyk.httpClient=${XYK_HTTP_CLIENT} --no-daemon \
 && cp server/build/bin/native/releaseExecutable/server.kexe /app/server.kexe \
 && readelf -d /app/server.kexe | head -20

FROM scratch

# Creates the directory; in the cluster a volume is mounted over it. `scratch` has no `mkdir`.
WORKDIR /data
WORKDIR /app

# FIVE PATHS, AND THE LIST WAS TAKEN WITH `strace -e trace=openat` FROM A RUNNING BINARY rather than
# reasoned out. They are copied OUT OF THE BUILD STAGE for the reason in the header.
COPY --from=build /etc/ld.so.cache /etc/ld.so.cache
COPY --from=build /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2
COPY --from=build /lib/x86_64-linux-gnu/libc.so.6 /lib/x86_64-linux-gnu/libc.so.6
# The WHOLE gconv directory, not one module: glibc picked `UTF-16.so` to convert UTF-8 elsewhere in
# this portfolio, so the set of reachable modules is not something a COPY line should predict. The
# price is known — curating it down saved 2 811 555 bytes there — and an unusual `charset=` in a
# `Content-Type` is a `500` in production that no test in the suite would catch.
COPY --from=build /usr/lib/x86_64-linux-gnu/gconv /usr/lib/x86_64-linux-gnu/gconv
# Nothing reads this today: the binary embeds no zones and `strace` opened neither `/usr/share/zoneinfo`
# nor `/etc/localtime`. It is 346 KB of insurance for the day somebody asks for a named zone, and the
# journal renders UTC on purpose (B-12).
COPY --from=build /usr/share/zoneinfo /usr/share/zoneinfo
# Certificates, because the delivery half will need them and their absence looks like a connection
# error rather than a missing file (B-17 measured exactly that).
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt

COPY --from=build /app/server.kexe /app/server

# `MALLOC_ARENA_MAX=2` because glibc counts the **host's** cores when it decides how many arenas to
# allow, not the container's quota, and each arena is address space this process never asked for.
# Measured on this service in B-21: peaks of 42 120 – 56 188 kB against 45 112 – 65 536 without it,
# at the same limit and the same load. It was held back then pending that measurement; the
# measurement is done and said take it. The hazard recorded beside it still stands and is not ours:
# combined with `-Xallocator=std` it multiplied peak RSS tenfold elsewhere, and this image ships
# `-Xbinary=pagedAllocator=false` (B-28), not that.
ENV MALLOC_ARENA_MAX=2
ENV XYK_DB_PATH=/data/xyk.db
VOLUME ["/data"]
EXPOSE 8080

ENTRYPOINT ["/app/server"]
