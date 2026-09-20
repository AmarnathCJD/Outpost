# Mobile workbench improvements

Reviewed against official product and platform documentation on 2026-09-19, with implementation and local verification continuing on 2026-09-20. These references guided the workflow design; Outpost is a mobile client, not a complete VS Code implementation.

| Reference | Finding | Implementation |
| --- | --- | --- |
| [VS Code user interface](https://code.visualstudio.com/docs/getstarted/userinterface) | Frequent navigation should be direct, with workspace context and quick file access. | Work now uses a compact SSH toolbar, current/recent workspace actions, pinned repositories, a workspace filter, and full-screen file/content search. The command palette also opens these tools. |
| [VS Code Remote SSH](https://code.visualstudio.com/docs/remote/ssh) | Remote development includes opening running services through forwarded ports. | Running services lets you forward a host/port over the existing SSH connection, copy the local URL, open a browser, and close the forward. |
| [VS Code staging and commits](https://code.visualstudio.com/docs/sourcecontrol/staging-commits) | Reviewing changes and history is part of the editing workflow. | Commit history is paginated, can be filtered to a file, and opens a coloured diff. Editor options link to file history. Existing PR files/checks/discussion remain available. |
| [Git stash](https://git-scm.com/docs/git-stash) | Applying retains a stash; popping can remove it. Stashes are shared across worktrees. | Stashes have descriptions, origin information, inspection, and explicit apply-and-retain. Restore uses immutable object IDs. Server-recorded origin blocks migration or unverified stashes from the feature apply action. |
| [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) | Interactive targets should be at least 48 dp with meaningful semantics. | Primary buttons and the command entry have a 48 dp minimum. New icon actions have spoken labels; key workspace and search text uses 15–24 sp with existing user scaling. |

## Recovery and clarity

- Unsaved edits are encrypted through Android Keystore and backed up locally with serialized, coalesced writes. Work lists recoverable drafts. Closing an edited file offers keep, discard, or stay. Restored drafts retain their server revision, so save still detects remote changes.
- Drafts and workspace pins are scoped by SSH host, port, username, and backend target. Changing credentials on the same server does not change the scope.
- Saved terminal controls use the same server identity, including SSH user and backend. Legacy sessions without that identity are rediscovered from the server after connecting.
- Deleted files and directories can be restored from a Recovery bin to their original path or another path. Restore refuses existing destinations.
- Repository search has file-name and fixed-text modes, case/whole-word controls, and an optional file glob. A content result opens at its matched line.
- Full Git patches are now fetched on demand instead of being generated and transferred on every background sync. Refresh results are discarded when the selected workspace or connection changes.
- Draft saves also check the original branch (or detached commit). Save completion preserves later typing and updates retained drafts even after the editor closes. Identical retries after a lost save response are safe no-ops.
- HTTP cancellation closes the underlying request. Automatic retries and redirects are disabled. Missing folders return the explorer to the root rather than triggering endless SSH reconnects. History pagination holds a commit reference so new AI/terminal commits do not shift subsequent pages.
- File operations reject directory-symlink traversal and editor replacement of symlinks/special files. Linux restoration and rename use `RENAME_NOREPLACE` to avoid overwriting a destination created concurrently in the terminal.

## Boundaries

- Port forwards are temporary and close with SSH. URLs bind to loopback only; they do not publish the service publicly. A successful forwarding setup does not imply the destination service is running. The browser link defaults to HTTP; other protocols can use the displayed local port.
- Draft persistence is asynchronous and coalesced for 250 ms. Abrupt process/device termination during a pending write can lose the latest keystrokes. It is recovery support, not a replacement for Save. Local drafts are not shared between devices.
- File-name search scans bounded command output and returns at most 100 matching paths. Content search returns at most 100 matching lines, at most 10 per file, in files up to 1 MiB. Hidden files are excluded; file globs may explicitly include otherwise ignored non-hidden files. The explorer can open hidden configuration files.
- Recovery entries created by older versions without metadata remain in `.wfy/trash` and require terminal recovery. The app does not automatically purge recovery entries or stashes.
- Stashes made in the terminal or older versions have an unverified origin and remain inspectable. Restoring those requires the terminal. Terminal access can bypass app safeguards; repository branch protection remains authoritative.
- Native debugger integration, language-server completion, Git conflict merge editing, and multi-file dirty tabs are future work. This pass improves the existing phone workflow without claiming desktop IDE parity.

## Validation policy for this pass

Server tests on Windows and Linux, Android JVM tests, compilation/lint and WSL smoke checks ran before the authorized phone checks. Phone checks exercised real SSH/editor/Git/recovery/terminal workflows and app screenshots. Automated test sources and fixtures were subsequently removed at the owner's request. See VALIDATION.md for the executed results and the remaining large-text, minified-release and external-account validation limits.
