FROM golang:1.26.11-bookworm@sha256:dc40a13b61304647ed7e5d581d246b86413dfc29d63c6d018310feb93cd98fc0 AS build
WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
COPY VERSION /src/VERSION
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-X main.buildVersion=$(cat /src/VERSION)" -o /out/hongshu . && CGO_ENABLED=0 go build -trimpath -o /out/vapid ./cmd/vapid && CGO_ENABLED=0 go build -trimpath -o /out/healthcheck ./cmd/healthcheck

FROM node:22-bookworm-slim@sha256:5ec23b7c622b00547753bc6ffd78085dd868a68aeb41c8e0a1418f033e13d96d AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN WEB_BUILD_DIR=/out/web npm run build

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
WORKDIR /app
COPY --from=build /out/hongshu /out/vapid /out/healthcheck /
COPY db/migrations/ /app/migrations/
COPY --from=web /out/web/ /app/web/
ENV MIGRATIONS_DIR=/app/migrations WEB_DIR=/app/web LISTEN_ADDR=:8080
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/hongshu"]
