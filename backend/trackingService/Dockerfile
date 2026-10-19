# syntax=docker/dockerfile:1

# ---- build stage: full Go toolchain, thrown away afterwards ----
FROM golang:1.27 AS build
WORKDIR /src

# Download modules first, in their own layer: rebuilding after a code change
# reuses this layer as long as go.mod / go.sum did not change.
COPY go.mod go.sum ./
RUN go mod download

COPY cmd ./cmd
COPY internal ./internal
# CGO_ENABLED=0: a fully static binary, so the runtime image needs no C library.
# -trimpath and -ldflags="-s -w": no local paths or debug symbols, smaller binary.
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/tracking ./cmd/tracking

# ---- runtime stage: only the binary ----
# distroless/static has no shell, no package manager and no curl: less to attack, about 2 MB.
# The :nonroot tag runs as uid 65532 instead of root.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/tracking /tracking
EXPOSE 8085
# No curl in this image, so the binary checks itself (see cmd/tracking/main.go).
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 CMD ["/tracking", "healthcheck"]
ENTRYPOINT ["/tracking"]
