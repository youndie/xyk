# The twin's image, built the same way the subject's is: static binary, no base image.
#
# `CGO_ENABLED=0` is the point rather than a habit — it is what makes this a fair second column. The
# Kotlin arm is a statically linked binary on `scratch`; a cgo Go build would need a base image and
# the comparison would be measuring packaging.
FROM golang:1.23-bookworm AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /twin .

FROM scratch
COPY --from=build /twin /twin
# The Go runtime honours this; Kotlin/Native has nothing to honour, which is one of the things the
# three columns are for. The harness sets it to the container's limit.
ENV XYK_DB_PATH=/data/xyk.db
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["/twin"]
