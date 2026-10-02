# THE NEGATIVE CONTROL for `dev/image-smoke.sh`, and the reason that script exists in the shape it
# does.
#
# It is `xyk:scratch` with **one thing missing**: the gconv directory. glibc has no built-in charset
# converters, Ktor's charset layer on Kotlin/Native is glibc `iconv`, and `iconv_open` loads its
# converters with `dlopen` — so this image starts and answers `/health/ready` with `200` like the
# full one, and fails wherever a converter is needed.
#
#   docker build -f docker/scratch.Dockerfile -t xyk:scratch .
#   docker build -f docker/scratch-control.Dockerfile -t xyk:scratch-nogconv .
#   dev/image-smoke.sh xyk:scratch-nogconv     # must exit 4; `make image-scratch` requires exactly that
#
# WHERE IT FAILS IS NOT WHERE IT WAS FIRST EXPECTED TO. It was written to fail on a rendered page,
# because that is where a service of the same shape failed; on this one it renders every page, and the
# control did not fire (research §1.14). What reaches a converter here is a request body whose
# `Content-Type` declares a charset other than UTF-8: measured 2026-10-02, this image answers such a
# body in `windows-1251`, `KOI8-R` or `ISO-8859-1` with `201` and stores U+FFFD for every non-ASCII
# character, and refuses `US-ASCII` with `400`; the full image decodes all four. The smoke test's
# charset step sends windows-1251 and requires the text back, so it exits 4 here.
#
# It also answers the second question: what the gconv tree costs, as a measured number rather than a
# subtraction. `--build-arg XYK_BASE=xyk:scratch-curl` weighs it on the build that will actually ship.
#
# A smoke test that has never failed on purpose has not been shown able to notice a failure, and
# this is the failure it was written for — one that a status-code check sails straight past.
ARG XYK_BASE=xyk:scratch
FROM ${XYK_BASE} AS full

FROM scratch
WORKDIR /data
WORKDIR /app

COPY --from=full /etc/ld.so.cache /etc/ld.so.cache
COPY --from=full /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2
COPY --from=full /lib/x86_64-linux-gnu/libc.so.6 /lib/x86_64-linux-gnu/libc.so.6
COPY --from=full /usr/share/zoneinfo /usr/share/zoneinfo
COPY --from=full /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
# /usr/lib/x86_64-linux-gnu/gconv is deliberately absent. That is the whole experiment.
COPY --from=full /app/server /app/server

ENV XYK_DB_PATH=/data/xyk.db
EXPOSE 8080
ENTRYPOINT ["/app/server"]
