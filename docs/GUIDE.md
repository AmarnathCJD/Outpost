<img src="../assets/branding/outpost-mark.svg" width="72" height="72" alt="Outpost icon">

# Outpost

A personal Android workspace for Go backend development over SSH. Native Kotlin/Compose app, Go backend, Linux or native Windows terminals, Git worktrees, Codex, Claude Code, and SQL Server.

## What runs where

The phone provides files, a text editor, Git actions, terminal/AI sessions, and settings. Your Linux server or Windows laptop runs the tools on its own files. SSH supports passwords and private keys; backend HTTP and WebSocket traffic travels inside the SSH tunnel.

**Windows without Docker:** extract `dist/outpost-windows.zip`, open `Outpost.exe`, select the parent folder of your repositories and start hosting. Select **Allow phone connections** under **Phone access** (built-in SSH on port 2222; use the laptop token for both SSH password and API token), then connect directly to the laptop's Wi-Fi or VPN address. The desktop UI manages Git/environment settings, tools and tray hosting. For changing Wi-Fi addresses or mobile-data access, use the new **VPS relay** section and its HTTPS/WebSocket URL; the laptop connects outbound automatically. Direct access can also use a VPN. See [Windows setup](WINDOWS.md). Phone edits save directly to laptop files; Ubuntu is optional.

- **Work:** direct SSH connect, current/recent workspace actions, pinned repositories, recovered drafts and running-service previews.
- **Files:** browse, filter/sort, view/edit, undo/redo, word wrap, find/replace, go to line, quick-open, repository content search, create/move/rename and a recovery bin.
- **Git:** coloured per-file diffs, staging, paginated/file-specific commit inspection, branches, fetch, fast-forward pull, described stashes with origin checks, guarded push and GitHub PR creation.
- **Pull requests:** search open/closed/merged PRs, open by number, read descriptions and conversation comments, view checks and changed files, and inspect additions/deletions. Inline review threads open on GitHub.
- **Sessions:** persistent Bash, Codex, Claude Code, Go commands, custom migration/seeder commands.
- **Settings:** Connection, Git, AI, Environment, Commands, Editor.

The interface uses VS Code Dark Modern colours, a bottom activity bar, a searchable command palette, editor tabs, syntax highlighting and SSH/branch status bars. Tap the command field in the header to navigate directly to files, terminals, assistants or a settings section. Server actions stay disabled until their connection/workspace requirements are met.

The interface bundles Inter, with JetBrains Mono for code and terminals. The phone editor adds automatic indentation, bracket/quote pairs, selection indentation, cursor keys, snippets and keyboard shortcuts. Tabs and breadcrumbs collapse while the keyboard is open, with Save available beside the keyboard. Text and touch targets are sized for a phone. **Settings → Editor** offers Compact, Standard, Comfortable and Large interface text, plus a separate code/terminal font size. **Environment** supports individual masked variables and bulk text editing; changes apply after saving. Notifications stay in an inline strip so they do not cover file rows or actions.

## Install on a server

Requirements: x86_64 Ubuntu/Debian, SSH access, approximately 20 GB of free disk space for images/tools plus your repositories/databases. The default limits target a 16 GB / 8 vCPU machine. SQL Server Developer is intended for development, not a licensed production database workload.

Copy the release server archive from this computer:

```powershell
scp .\dist\outpost-server.tar.gz ubuntu@YOUR_SERVER:~/
```

Then run on the server:

```bash
mkdir -p ~/outpost && tar -xzf ~/outpost-server.tar.gz -C ~/outpost && cd ~/outpost && sudo bash deploy/bootstrap.sh
```

The bootstrap installs Docker from its official signed apt repository if needed. The installer generates unique credentials, builds the workspace image, starts both containers and waits for health checks. Rerunning it retains credentials and named volumes. If Docker is already configured, `sudo bash deploy/install.sh` suffices.

Only SSH needs to be reachable from your phone. Do not expose 8787 or SQL Server publicly. The backend binds to host `127.0.0.1:8787`; SQL Server is accessible only on the Docker network as `mssql:1433`.

## Connect Android

Install `dist/outpost.apk`, then open **Settings → Connection**:

1. Enter the Linux host, SSH port, and your Linux username.
2. Choose Password or Private key; import/paste the key and optional passphrase.
3. Leave backend port at `8787` unless you changed `OUTPOST_PORT` in deployment.
4. Paste the server token printed by the installer.
5. Connect and compare the SSH fingerprint against the host key on your server before trusting it.

SSH credentials and tokens are encrypted with Android Keystore. Backups and device transfer of app data are disabled. Save your server credentials separately.

In **Settings → Git**, connect GitHub through its browser device flow, then set author name/email, PR target and migration branch. Private HTTPS clones use the `gh` credential helper. SSH Git remotes require a separate Git key inside the workspace; the phone's SSH key is not forwarded to GitHub.

In **Settings → AI**, connect Codex using device-code authentication. Your ChatGPT account must allow device-code login. Claude uses its normal Linux CLI login. Provider API keys can be configured later under **Environment**; subscriptions do not supply API credits automatically.

## Daily workflow

Clone a repository in **Work**. Open it, then use **Git → New workspace → Feature** to create a feature branch and worktree. Edit files, run `go mod tidy`, builds and tests in **Sessions**, stage selected files, commit, push the feature branch and create a PR.

For migrations, first configure the existing migration branch under Settings and fetch it. Create a **Migration** workspace from the base repository. Migration workspaces stay separate from feature files; feature PR creation is disabled there. Push migrations only after receiving your TM/TL's approval and confirming that in the app.

The API blocks direct commits/pushes to `main`, `master`, and configured protected branches. Local Git hooks protect common CLI pushes too. GitHub branch rules remain necessary for enforcement against arbitrary terminal/AI commands; this app cannot independently verify manager approval or prevent a privileged user bypassing local hooks.

## SQL Server and environment

The workspace receives `SQLSERVER_HOST=mssql`, `SQLSERVER_PORT=1433`, `SQLSERVER_USER=sa`, `SQLSERVER_DATABASE=master`, and `SQLSERVER_PASSWORD` from its local Docker secret. Use these to bootstrap a development database and a least-privilege application login. Set project-specific connection strings in the repository's ignored `.env`, or use workspace-wide Settings → Environment. The password file is `deploy/secrets/sql_password` on the host.

Environment values apply to new sessions and backend Git commands. Existing shells keep their environment. Migration and seeder tools vary by repository: run your existing commands in **Custom command** or an interactive terminal. The workspace includes Go, Git, GitHub CLI, Node, Python, compilers, `tmux`, `ripgrep`, and basic editors.

## Persistence, updates and recovery

Repository files, CLI login state, and SQL Server data live in named Docker volumes. Phone disconnections do not stop tmux sessions. Container/server restarts do stop running processes; reopen a session after restart. Rebuilds retain volumes.

Deleted files move to `/workspaces/.wfy/trash`. Open **Work → Recovery bin** or **Files → File tools → Recovery bin** to restore a file or folder to its original path or a new name. Existing destinations are preserved. Older recovery entries without metadata still require the terminal.

Unsaved editor drafts are encrypted on this phone and listed in Work. Closing an edited file offers Keep draft, Discard, or Stay. Saves check the server revision and the draft's original branch. If an AI or terminal changes the file remotely, keep the draft and compare before reloading. The editor supports regular UTF-8 text files up to 2 MiB; symlinks and special files require the terminal. Draft writes are coalesced for 250 ms, so abrupt termination during a pending write can lose the latest keystrokes.

**Git → Stashes** lets you inspect saved work and apply it while retaining the stash. Only app-recorded feature stashes can be applied to feature branches. Migration and unverified terminal-created stashes remain inspectable and require the terminal to restore.

## Preview a running service

Start your Go service in a terminal, then open **Work → Running services**. Enter the host as seen by the SSH server and its port, then Forward port. Open browser or Copy URL uses a phone-local loopback address. Forwards close when SSH disconnects.

Docker services need a reachable container IP or a port published to server localhost. If SSH uses a `PermitOpen` restriction, allow the chosen host/port there too. No public service port is required.

```bash
cd ~/outpost/deploy
sudo docker compose ps
sudo docker compose logs --tail=100 workspace
sudo docker compose exec workspace bash
sudo docker compose up -d --build --wait
```

Back up all three named volumes (`outpost_workspaces`, `outpost_home`, `outpost_mssql`) and `deploy/secrets`. For a consistent SQL Server backup use SQL Server's backup command, or stop the stack before copying volume contents. Do not use `docker compose down -v` unless you deliberately want to erase the workspace and database.

## Build

JDK 17/21 and Android SDK 36 are required. The Gradle wrapper pins the build tool and verifies its download checksum.

```powershell
.\scripts\create-signing-key.ps1
.\gradlew.bat assembleDebug assembleRelease lintDebug
```

Back up `.signing` privately. The same signing key is required for future APK updates. Signing material is excluded from Git and server bundles. Debug and release builds use different signing keys; development testing uses the debug APK.

Backend: `cd server && go build ./... && go vet ./...`. Automated test sources and fixtures were removed at the owner's request. See [architecture](ARCHITECTURE.md), [manual checks](TESTING.md), [historical validation evidence](VALIDATION.md), and [UX research and design decisions](UX-RESEARCH.md).

## Release assets

See [release preparation](RELEASING.md) for the complete build and upload steps. Upload-ready bundles are collected in `dist/release/`.

The pinned Codex CLI uses its supported legacy Landlock sandbox inside the restricted Docker workspace because nested bubblewrap namespaces are unavailable there. The entrypoint writes this default only when a Codex configuration does not exist. Revalidate command execution and write restrictions when upgrading Codex, since this compatibility feature is deprecated upstream.

After building a signed release, run `python scripts/package-release.py` to create the APK, server archive and checksums. Signing keys and deployment secrets are excluded.

## Dependencies and design references

Terminal rendering uses bundled [xterm.js](https://xtermjs.org/) (MIT; license included in app assets). The visual reference is Microsoft's [VS Code Dark Modern theme](https://github.com/microsoft/vscode/blob/main/extensions/theme-defaults/themes/dark_modern.json), adapted to phone touch targets and navigation. Provider authentication follows [Codex authentication](https://developers.openai.com/codex/auth) and [Claude Code setup](https://code.claude.com/docs/en/setup).

The standalone [VPS relay service](../relay/README.md) supports raw SSH or HTTPS/WebSocket forwarding, automatic laptop reconnection, and persistent laptop SSH identity.
