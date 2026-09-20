# Native Linux backend

The Docker deployment bundles developer tools and SQL Server automatically. This native binary is an alternative for an existing Linux x64 environment.

Install Git, Go, bash, tmux, ripgrep and the tools you use (GitHub CLI, Node, Codex/Claude). SQL Server must be installed separately or reachable on another host. Keep the backend workspace root private to your user.

```bash
mkdir -p "$HOME/.config/outpost" "$HOME/workspaces"
chmod 700 "$HOME/.config/outpost"
umask 077
# Generate once. Reuse the same token on later starts.
test -s "$HOME/.config/outpost/token" || openssl rand -hex 32 > "$HOME/.config/outpost/token"
export WFY_ROOT="$HOME/workspaces"
export WFY_LISTEN=127.0.0.1:8787
export WFY_TOKEN_FILE="$HOME/.config/outpost/token"
./outpost-server
```

Keep this process running or manage it with a user systemd service. Use the existing Linux SSH server on Android and forward to backend `127.0.0.1:8787`. Configure the same private token in Android. Never publish the API port directly to the internet.

Embedded SSH is optional: set `WFY_SSH_LISTEN=0.0.0.0:2222`, `WFY_SSH_USER=outpost` and `WFY_SSH_DIR="$HOME/.config/outpost/ssh"`. Its password is the backend token, with optional public keys in that directory's `authorized_keys`. The generated host key must persist across restarts.

The API checks file revisions, protects configured branches and stores workspace settings in `<workspace root>/.wfy`. Terminal sessions survive phone disconnects while the backend/tmux processes remain alive. Back up workspace data and credentials separately from this executable.
