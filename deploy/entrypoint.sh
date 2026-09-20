#!/bin/bash
set -euo pipefail
umask 077
if [ -n "${WFY_SQL_PASSWORD_FILE:-}" ]; then
  export SQLSERVER_PASSWORD="$(cat "$WFY_SQL_PASSWORD_FILE")"
fi
mkdir -p "$HOME/.config" "$HOME/.ssh" "$HOME/.codex" "$HOME/.claude"
chmod 700 "$HOME/.ssh"
if [ ! -f "$HOME/.codex/config.toml" ]; then
  printf '%s\n' '# Landlock works within the unprivileged Docker workspace.' '[features]' 'use_legacy_landlock = true' > "$HOME/.codex/config.toml"
fi
if [ ! -f "$HOME/.tmux.conf" ]; then
  printf '%s\n' 'set -g history-limit 20000' 'set -g mouse on' 'set -g status off' 'set -g default-terminal "xterm-256color"' > "$HOME/.tmux.conf"
fi
exec /usr/local/bin/outpost
