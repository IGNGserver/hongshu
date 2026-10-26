FROM golang:1.26.4-bookworm AS build
WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
COPY VERSION /src/VERSION
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-X main.buildVersion=$(cat /src/VERSION)" -o /out/hongshu . && CGO_ENABLED=0 go build -trimpath -o /out/vapid ./cmd/vapid && CGO_ENABLED=0 go build -trimpath -o /out/healthcheck ./cmd/healthcheck

FROM node:22-bookworm-slim AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN WEB_BUILD_DIR=/out/web npm run build

FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /app
COPY --from=build /out/hongshu /out/vapid /out/healthcheck /
COPY db/migrations/ /app/migrations/
COPY --from=web /out/web/ /app/web/
ENV MIGRATIONS_DIR=/app/migrations WEB_DIR=/app/web LISTEN_ADDR=:8080
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/hongshu"]
