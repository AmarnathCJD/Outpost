ARG GO_VERSION=1.27.1
FROM golang:${GO_VERSION}-bookworm AS build
WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/outpost .

FROM node:24-bookworm-slim AS node

FROM debian:bookworm-slim
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git gh openssh-client tmux bash build-essential ripgrep less nano vim-tiny jq procps python3 unzip locales && rm -rf /var/lib/apt/lists/*
COPY --from=build /usr/local/go /usr/local/go
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
ARG CODEX_VERSION=0.155.1
ARG CLAUDE_VERSION=2.1.278
RUN npm install -g @openai/codex@${CODEX_VERSION} @anthropic-ai/claude-code@${CLAUDE_VERSION} && npm cache clean --force
RUN codex --version && claude --version
RUN useradd --uid 10001 --create-home --shell /bin/bash dev && mkdir -p /workspaces /home/dev/go && chown -R dev:dev /workspaces /home/dev
COPY --from=build /out/outpost /usr/local/bin/outpost
COPY deploy/entrypoint.sh /usr/local/bin/outpost-entrypoint
RUN chmod 755 /usr/local/bin/outpost-entrypoint
ENV PATH="/usr/local/go/bin:/home/dev/go/bin:${PATH}" HOME=/home/dev GOPATH=/home/dev/go LANG=C.UTF-8 TERM=xterm-256color
USER dev
WORKDIR /workspaces
EXPOSE 8787
ENTRYPOINT ["/usr/local/bin/outpost-entrypoint"]
