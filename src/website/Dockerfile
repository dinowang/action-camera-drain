# syntax=docker/dockerfile:1.7
# Multi-stage build for Action Camera Catch.
#
# Final image: FROM scratch + ca-certificates (~15 MB).
# Multi-arch: build with `docker buildx build --platform linux/amd64,linux/arm64`.

ARG GO_VERSION=1.25
FROM --platform=$BUILDPLATFORM golang:${GO_VERSION}-alpine AS build
WORKDIR /src

# Cache module downloads.
COPY go.mod go.sum ./
RUN go mod download

COPY . .

ARG TARGETOS
ARG TARGETARCH
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} \
    go build \
      -ldflags="-s -w" \
      -trimpath \
      -o /out/catch \
      ./cmd/catch

# Get CA bundle for HTTPS to Azure.
FROM alpine:3.20 AS certs
RUN apk add --no-cache ca-certificates

# Final image — distroless via scratch.
FROM scratch
COPY --from=certs /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /out/catch /usr/local/bin/catch

# /data is the default download root; mount a NAS volume here.
VOLUME ["/data"]
ENV DOWNLOAD_ROOT=/data \
    HTTP_PORT=8080

# NOTE: no USER directive on purpose.
#
# The container needs to own the files it creates under DOWNLOAD_ROOT so that
# `os.Chtimes()` can stamp the source mtime — which requires the running UID
# to match the bind-mounted host directory's owner (or be root).
#
# Default: run as root, which always satisfies chtimes. If you prefer non-root,
# override via docker-compose `user: "1000:1000"` (matching your NAS user's
# UID/GID — typically `id -u` / `id -g` on Synology).

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/catch"]
