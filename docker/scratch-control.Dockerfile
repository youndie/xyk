# THE NEGATIVE CONTROL for `dev/image-smoke.sh`, and the reason that script exists in the shape it
# does.
#
# It is `xyk:scratch` with **one thing missing**: the gconv directory. glibc has no built-in charset
# converters, Ktor's charset layer on Kotlin/Native is glibc `iconv`, and `iconv_open` loads its
# converters with `dlopen` — so this image is expected to start, answer `/health/ready` with `200`,
# and fail to render a page.
#
#   docker build -f docker/scratch.Dockerfile -t xyk:scratch .
#   docker build -f docker/scratch-control.Dockerfile -t xyk:scratch-nogconv .
#   dev/image-smoke.sh xyk:scratch-nogconv     # expected to exit 1 — it does NOT, see research 1.14
#
# It also answers the second question: what the gconv tree costs, as a measured number rather than a
# subtraction. `--build-arg XYK_BASE=xyk:scratch-curl` weighs it on the build that will actually ship.
#
# A smoke test that has never failed on purpose has not been shown able to notice a failure, and
# this is the failure it was written for — the one that a status-code check sails straight past.
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
