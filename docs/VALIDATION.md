# Validation record

Validation covers the Ubuntu VM deployment, native Windows hosting, local WSL, direct SSH, the standalone VPS relay and Android API 36 device workflows. Real Codex custom-provider replies are verified below. GitHub account operations, successful Claude provider replies and the latest signed release's full phone workflow remain unverified.

## 2026-09-20 v0.4.1 files, terminal input and native AI chats

- Local assistant integration: 11 checks passed using disposable CLI protocol fixtures with the Windows backend built with the race detector. Covered executable/environment discovery, Codex and Claude conversation IDs, exact-ID context resumption, duplicate request protection, transcript persistence through backend restart, same-thread concurrency rejection, process-tree cancellation, provider error reporting and removal. No race detector report occurred.
- Real Codex: the laptop's existing custom provider completed a read-only request and a second request recovered a marker from the same saved conversation. A physical-phone run then sent a message through the native composer, relay and laptop to that provider, rendered the actual response, reopened the chat and verified the same conversation ID and context on a follow-up.
- Real Claude: installed configuration was detected. The configured endpoint rejected a read-only request with HTTP 401 (invalid key/endpoint). Successful Claude account-backed replies could not be established; its streaming/session integration was exercised with protocol fixtures. Outpost does not alter the user's provider credentials.
- Physical Android: actual file-row tap opened the editor; host file changes appeared automatically; unsaved phone edits survived conflicting changes and stale saves were rejected. Keyboard Enter and toolbar Enter executed composed PowerShell commands through the WebView/relay path. Native chat ID import and unsent draft restoration passed. Restarting the phone app reopened the saved workspace and file.
- Final editor device check: logical line numbers remained visible with wrapping on and off. After scrolling to line 46, opening and closing the actual Android keyboard retained that viewport. Hardware Enter, the toolbar Enter control and the soft-keyboard Send action all executed composed terminal commands.
- Desktop restart check: repeated PATH concatenation in tool refresh could exceed the Windows command interpreter's limit and prevent npm Codex from finding Node. PATH entries are now deduplicated in the desktop and backend. The existing real Codex conversation successfully resumed after restarting through the desktop UI, retaining its original marker and ID.
- Android's hidden terminal textarea could capture input without executing the composed text. The terminal now directs tapping/typing to its native composer, supports explicit multiline input and retains a draft when it cannot queue a send. The phone checks above use the actual composer and Enter controls.
- Terminal display: physical screenshots exposed a clipped page even though commands reached the shell. The terminal now anchors to the WebView viewport and updates its dimensions on native size changes. A final device run verified visible output, 31-to-15-row resizing with the real keyboard and restoration to 31 rows, soft-keyboard Send execution, and visible replay after reconnect. The README includes actual editor, PowerShell and Codex screenshots from this phone/laptop connection.
- Final build and cleanup: Android debug/release assembly and release lint passed (0 errors, 23 warnings, 2 hints). The 0.4.1/code 6 release APK passed v2 signature verification. The installed debug build was updated while retaining its data. Packaged Windows executables match the running laptop host; the packaged relay matches the active VPS binary. Disposable chats, test workspaces, instrumentation package, screenshots containing pairing information and temporary runners were removed. No terminal sessions were left running by these checks.

These checks use the debug-signed phone app to preserve existing data. Release assembly/signature/lint checks do not replace a complete minified-release device run. Relay protocol and routing are unchanged from 0.4.0.

## 2026-09-20 v0.4.0 pairing and private host discovery

- Local integration: 14 checks passed with two real backend processes. Covered simultaneous host registration, private lookup, AES-GCM profile decryption and identity matching, absence of plaintext credentials in the registry, host-specific file reads/writes, API authentication, wrong/missing route credentials, ambiguous legacy routes, duplicate host rejection, forged proof rejection, offline/last-seen state, peer isolation, code rotation, registry restart persistence, reconnection and legacy raw forwarding alongside paired hosts.
- Desktop/VPS: the updated relay was deployed over the existing HTTPS proxy and the Windows host registered successfully. The desktop pairing page and QR dialog were exercised. The actual displayed QR was captured privately at native display resolution for Android decoder validation.
- Android device: ZXing decoded that desktop QR; the app parsed its invitation, rejected an incorrect pairing code, decrypted the profile obtained from the live HTTPS relay and saved the host. It verified the laptop's SSH identity without a manual trust prompt, read the real workspace, saved a disposable file on the laptop, rejected a deliberately incorrect SSH pin, and reconnected. Saved pairing and optional automatic connection were checked after a process restart. A sustained check kept the app connected through six workspace refreshes over 30 seconds, including relay keepalives. The Hosts screen was inspected from an app-rendered screenshot.
- Connection readiness now waits until the initial workspace/settings sync is complete. Invalid profiles are isolated to their own host card, so another laptop on the same relay can still be used.
- The README was rewritten as the GitHub project landing page, with an SVG banner, feature overview, connection diagram and setup links. Pairing, Windows and relay documentation describe the implemented flow and rotation behavior.

These device checks use the debug-signed app to preserve installed data. QR image decoding and invitation handling were exercised; physically aiming the camera at the screen and the runtime camera permission prompt were not automated. Account-backed provider/GitHub operations and other Android versions retain the limits below. Temporary runners and disposable files are removed after verification, as requested.

## 2026-09-20 v0.3.1 laptop, standalone relay and phone editor

- Direct embedded SSH: 13 disposable checks passed, including password/public-key authentication, invalid-credential/target/shell rejection, API authentication, repository discovery, saves and stale-save conflicts, concurrent channels, unsupported authorized-key options, real ConPTY input/resize/replay, session cleanup, graceful shutdown acknowledgment and stable host identity after restart. The final rerun passed after fixing the shutdown response race.
- Standalone relay: 12 checks passed over raw SSH transport and 12 over WebSocket transport. Covered token and fingerprint rejection, restricted forwarding, file edits, concurrent laptop rejection, native terminal input, forced disconnect/restart/reconnect/replay and cleanup.
- Live deployment: the dedicated relay runs under systemd on Ubuntu. Nginx configuration validation and reload passed. Both phone and laptop connected through the deployed HTTPS/WebSocket endpoints with TLS validation enabled. Laptop health reported the relay connected. The laptop's inner SSH identity remains distinct from the relay identity.
- Physical Android: the updated debug-signed app connected through the deployed VPS to the actual Windows workspace. The runner checked Windows health, reading the real project README, saving a disposable file and inspecting its diff, native terminal execution/input/output, complete SSH disconnect/reconnect and session replay, ending the session, and smaller-font settings/screens.
- Editor: 13 direct edge cases passed on the phone, covering paired insertion, selected-text wrapping, newline indentation, paired deletion, closing-character skip, apostrophes, paste, multiline indent/outdent, Unicode cursor movement and disabled smart typing. The physical keyboard workflow passed bracket-toolbar input, Enter/autoindent, typing, Save through the VPS to the laptop, Undo/Redo and hiding the keyboard. A screenshot confirmed that the editor, controls and keyboard fit together.
- Builds: Windows desktop publish, backend/relay builds and Go vet, Android debug/release assembly and release lint passed. Font licenses are bundled with the app.
- Final branding/release pass: Android debug/release builds passed with the new mark; release lint reported 0 errors, 23 warnings and 2 hints. The v0.3.1/code 4 release APK passed signature verification (v2). The installed phone app's adaptive and monochrome icon resources were checked and the launcher icon was rendered on the device. The phone had relocked, so the final visible app-header preview was not repeated. Windows EXE icon extraction and desktop Phone access/VPS relay navigation passed. Updating the GUI preserved the running laptop backend; authenticated health reported Windows ready, relay connected and no leftover terminal sessions.
- Release preparation: archive contents, path exclusions, executable modes, LF shell scripts and SHA-256 checksums passed. The packaged relay binary matches the deployed systemd service. No credentials or personal server addresses were found by the focused source scan. Temporary integration runners, test packages, fixtures and fixture-only phone bookmarks/drafts were removed after validation; existing credentials and signing keys were retained.

The phone runs the debug-signed build to preserve existing app data. A production-signed APK cannot update that debug install in place. Authenticated provider responses, private GitHub pushes/PR creation, mobile-data-only connectivity and other Android versions were not exercised. A live HTTPS relay check is not a test of every carrier/network.

## 2026-09-20 native Windows and Ubuntu deployment

- Ubuntu VPS: v0.3.0 deployed; workspace and SQL Server containers healthy. API listens only on host loopback. Earlier source directory retained as a rollback copy; named volumes and credentials preserved.
- Windows backend: 31 disposable integration checks passed. Covered authentication, configured-port healthcheck, repository discovery under a spaced parent path, native Git worktrees/hooks, feature commits/diffs, migration gates, UTF-8 edits, save conflicts, no-overwrite rename/restore, search, Windows filename/environment edge cases, real ConPTY input/resize/reconnect, task and Go execution, normal shell exit, script cleanup, and graceful host shutdown.
- Live SSH deployment/relay: 12 checks passed. Covered Linux API/files/search/Git/recovery and persistent tmux; then reached the actual Windows repository through the Ubuntu reverse forward, rejected the wrong host's token, and resumed a native PowerShell variable through two SSH forwards.
- SQL Server: authentication, temporary schema creation, seed/query and transaction rollback passed on the remote container. Go, Git, tmux, Codex and Claude version checks passed there.
- Desktop: self-contained Windows publish succeeded; live navigation/status and workspace settings save/reload were checked through Windows UI Automation and screenshots. Graceful desktop exit stopped the backend and relay. Windows SQL Server setup is provided as an interactive optional installer; SQL Server was not installed on this laptop during validation.
- Desktop lifecycle: four checks passed for close-to-tray hosting, automatic relay restart after forced disconnection, relay process cleanup after a simulated desktop crash, and reopening the desktop to recover the existing backend and native terminal state.
- Final shutdown check: the host waited for an in-flight API request to finish before closing its workspace; the completed request received its response and the host exited successfully.
- Android: v0.3.0 signed release assembly, release lint and APK signature verification passed. The phone became available over wireless ADB late in validation. The release update was rejected because its signing key differs from the existing debug install. The existing app was updated to v0.3.0 with the matching debug key, retaining its data; cold launch succeeded in 1.8 seconds with no AndroidRuntime crash reported. The device stayed locked, preventing interactive workflow and screenshot validation. No uninstall or credential reset was performed. Earlier phone workflow results below apply to the historical builds described there.

Disposable validation sources and fixtures are removed after use, per the owner's earlier request. These checks establish the listed behavior, not a guarantee against all possible failures.

## 2026-09-20 cleanup and handoff

The owner requested deletion of automated test sources and temporary test files. Those sources and runners have been removed; the results below describe checks performed before removal and are not a currently runnable test suite.

The v0.2 pass completed seven Android JVM checks, backend race/vet checks on Windows and Linux, live WSL Git/file/recovery/history/stash/port checks, and SQL authentication/schema/seed/rollback checks. Six baseline phone checks passed. Extended phone workflows passed encrypted draft recovery, conflicting saves, recovery collisions, staging/commit/history, stash application with retention, browser previews and terminal reconnection. Wrong SSH passwords, changed host keys and invalid API tokens were rejected.

The phone found a mixed Long/Int workspace-sorting crash, which was fixed and rechecked. Later fixes scoped saved sessions to the SSH user/backend, validated editor preferences and cancelled pending host-key prompts when the app closes. The last lifecycle and large-text selector refinements were build-checked, but their full phone rerun was superseded by the cleanup request. A complete minified release workflow remains unverified: the attempted instrumentation runner could not load optimized shared dependencies. The normal minified app launched successfully; no production account or remote server was used.

After cleanup, the release build and release lint passed, and the backend compiled and passed `go vet`. Local demo workspaces were deleted. Phone-side QA packages could not be removed because the phone was no longer connected through ADB. Build/signature checks do not establish universal or account-backed end-to-end readiness.

## Completed local checks

- Windows: `go test -race ./...` and `go vet ./...` pass.
- Linux workspace: `go test -race ./...` passes, including the real tmux terminal reconnect integration and the new PR/diff routes.
- Android Kotlin compilation and `lintDebug` pass. Lint reports dependency-update/style warnings, not errors.
- Signed release APK builds and passes `apksigner verify` (APK signature scheme v2).
- The deployed API smoke flow passes: GitHub public clone, separate feature/migration worktrees, file editing, stale-save rejection, path restrictions, protected push/PR rejection, stage/commit, Go execution and persistent sessions.
- SQL Server 2022 CU22 is healthy. Private-network access, authentication, temporary table creation, seeding, query, transaction rollback and cleanup pass.
- Codex and Claude CLI version checks pass inside the unprivileged workspace. Codex's pinned Landlock compatibility configuration executes allowed commands and rejects writes outside the allowed directory.
- Per-file diff tests cover untracked additions, staged additions, modifications, deletions, path validation and content that resembles diff headers.
- PR list/detail/diff routes pass against a POSIX GitHub CLI fixture. This validates request handling and response structure, not a real GitHub account.

## Device workflow

The phone has passed navigation, connection validation, command-palette filtering/navigation, password SSH, encrypted Ed25519 SSH, terminal input, file editing, undo/redo, server save, file reopening and Git navigation. The terminal-created marker was verified inside the workspace. UI changes were inspected through app-only screenshots.

The final UI regression run passed navigation, command palette, PR overview/files/checks/discussion and coloured diff rendering. The final connected encrypted-key workflow also passed after the gutter and commit-button refinements. PR presentation tests use explicit sample data and do not claim live GitHub account validation. Final artifact hashes are in `dist/SHA256SUMS`.

## Remaining external validation

- GitHub login, private repository access and creating an actual feature PR require the user's GitHub account. No external push or PR was created during these checks.
- Codex/Claude account sign-in and authenticated AI responses require the user's account. CLI installation, terminal transport and sandbox compatibility were checked.
- Remote Ubuntu deployment and the Windows relay are now validated as described above. Local WSL uses a private Docker IP through SSH because this computer's VirtioProxy loopback forwarding stalls; the Ubuntu deployment uses localhost.
- Android minimum API is 26, but physical-device verification covers API 36 only. Release minification is build-checked; development phone tests use the debug build.

## Practical limits

File editing supports UTF-8 text up to 2 MiB. Untracked diff previews are limited to 512 KiB, command/PR diff output to 1 MiB, and very long diff lines are shortened. PR lists return at most 100 matches; refine the search or open by number. GitHub may return partial metadata for very large PRs; the app offers the GitHub link.

Local Git hooks are defence in depth. Arbitrary terminal users can bypass them, so GitHub branch protection must enforce repository policy. Migration push confirmation records the user's confirmation; it does not independently verify manager approval.
