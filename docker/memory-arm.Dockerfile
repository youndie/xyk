# One arm of B-21: the same service, a different allocator, nothing else changed.
#
# The binary is passed in rather than built here, because the three of them differ only by a Gradle
# property (`-Pxyk.allocator=…`) and building each inside its own image would make the comparison
# depend on three build contexts instead of one.
FROM gcr.io/distroless/cc-debian13
ARG BINARY=server.kexe
COPY ${BINARY} /usr/local/bin/xyk
VOLUME ["/data"]
ENV XYK_DB_PATH=/data/xyk.db
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/xyk"]
