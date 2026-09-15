# The runtime image. The binary is built OUTSIDE this file — on the runner, or on the Linux box —
# and only copied in: building Kotlin/Native inside `docker build` costs tens of minutes, because
# the caches it needs cannot persist between runs of a fresh BuildKit builder.
#
#   ./gradlew :server:linkReleaseExecutableNative
#   docker build -f docker/native.Dockerfile -t xyk:dev .
#
# THE BASE IS distroless/cc AND ITS DEBIAN VERSION IS PAIRED WITH THE BUILDER'S GLIBC, not chosen by
# eye. A binary linked against glibc 2.39 (ubuntu 24.04, and the Linux box) needs a runtime whose
# glibc is no older, or the container builds and then dies at exec with
# `libc.so.6: version 'GLIBC_2.38' not found`. debian13 carries 2.41; debian12 carries 2.36 and
# would fail. One command settles it, and it is re-run on every tag change here or on the builder:
#
#   docker run --rm gcr.io/distroless/cc-debian13 ldd --version   # has no shell: check by exec
#
# Certificates are already in this image (`/etc/ssl/certs/ca-certificates.crt`), which is the other
# reason it is here rather than `debian:*-slim` — there they are absent, and everything outbound
# over https then fails quietly. That matters from B-10 onwards, when deliveries start.
#
# `FROM scratch` is B-18 and only if B-23 still needs the last few megabytes. It is not a smaller
# version of this file: a static binary still loads its charset converters with `dlopen`, so five
# more paths have to be copied out of a build stage, and the binary has to be linked inside the
# image for the glibc beside it to be the one it was linked against.
#
# `ENV MALLOC_ARENA_MAX=2` IS DELIBERATELY ABSENT until B-21 measures it on this service. It took a
# third off resident memory on one service with a Rust driver on the request path, did nothing on a
# service without a database, and — combined with `-Xallocator=std` — multiplied peak RSS by ten and
# had the kernel kill three runs out of ten. A setting like that is taken from a measurement of the
# thing that ships it, or not taken.
FROM gcr.io/distroless/cc-debian13

COPY server/build/bin/native/releaseExecutable/server.kexe /usr/local/bin/xyk

# The database is the only state there is, and a pod without a volume loses every undelivered event
# when it moves.
VOLUME ["/data"]
ENV XYK_DB_PATH=/data/xyk.db

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/xyk"]
