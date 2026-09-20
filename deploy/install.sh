#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if [ "$(uname -m)" != x86_64 ]; then
  echo 'Outpost with SQL Server requires an x86_64 Linux server.' >&2
  exit 1
fi
if ! command -v docker >/dev/null; then
  echo 'Docker is missing. Install Docker Engine and the Compose plugin, then rerun this installer.' >&2
  echo 'Official Ubuntu instructions: https://docs.docker.com/engine/install/ubuntu/' >&2
  exit 1
fi
docker compose version >/dev/null
mkdir -p secrets
chmod 700 secrets
if [ ! -s secrets/server_token ]; then openssl rand -hex 32 > secrets/server_token; fi
if [ ! -s secrets/sql_password ]; then printf 'Aa1!%s' "$(openssl rand -hex 24)" > secrets/sql_password; fi
# Compose file-backed secrets are mounted with host permissions. Parent stays private;
# files must be readable by the unprivileged container users.
chmod 444 secrets/server_token secrets/sql_password
docker compose up -d --build --wait --wait-timeout 300
printf '\nOutpost is ready. Android SSH settings:\n'
printf '  SSH host: your server IP or hostname\n  SSH user: your normal Linux user\n  Backend port: 8787\n  Server token: '
if [ "${OUTPOST_QUIET_SECRETS:-0}" = 1 ]; then printf '[stored in deploy/secrets/server_token]'; else cat secrets/server_token; fi
printf '\nDatabase: mssql:1433, user sa, password in deploy/secrets/sql_password.\n'
printf 'Only SSH needs to be reachable. API and SQL Server are not published publicly.\n'
