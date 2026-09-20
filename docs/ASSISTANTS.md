# AI conversations

Outpost uses Codex and Claude Code installed on the workspace host. It keeps the hosting user's login, environment and CLI configuration. You do not need a separate phone API key when the host is already configured.

## Start or continue a chat

1. Connect to your laptop and open a workspace.
2. Open **Sessions**, then **Codex** or **Claude Code**. This continues the latest Outpost chat for that provider and workspace.
3. Use **New chat** for a separate conversation. Optionally paste a conversation ID from the laptop CLI to continue its context.
4. Type in the native composer and tap Send. Responses, code blocks and tool activity appear in the chat. You can leave the screen while the host continues working.

The conversation menu copies its ID or opens the same conversation in a terminal. An imported conversation uses the provider's existing context; the phone displays messages received through Outpost from that point onward.

Codex IDs come from its `thread.started` event and subsequent messages use `codex exec resume <ID>`. Claude session IDs are saved from its stream and reused with `--resume`. Outpost never uses `--last`, which could accidentally select another conversation.

## Existing laptop configuration

Run Outpost under the Windows account that normally runs your coding tools. Native Windows tools inherit that account's environment and shell profile. Normal Codex/Claude configuration and authentication directories remain in use. On Linux, install and authenticate the tools as the user running the backend.

Provider status detects installed tools and local configuration. It does not make a paid API request or guarantee that a provider will accept the credentials. Custom Codex providers can be usable even when `codex login status` reports no OpenAI login; Outpost also checks the selected provider configuration.

If needed, sign in through **Sessions > Sign in**, the app's provider settings, or the normal laptop CLI. API-key users can configure environment variables in **Git & environment**. An HTTP 401 response means the provider rejected the configured credentials or endpoint; Outpost shows that error in the chat.

If the executable is outside PATH, set `OUTPOST_CODEX_PATH` or `OUTPOST_CLAUDE_PATH` to its full path. `CODEX_HOME` and `CLAUDE_CONFIG_DIR` continue to work as normal. Configuration changes apply to the next reply; a running reply keeps its original environment.

## Editing, permissions and stopping

The default Codex mode permits workspace edits within its sandbox. Claude uses its acceptEdits mode. **Read only / plan** selects Codex read-only or Claude plan mode. Tool requests that require interactive permission can be continued with **Open in terminal**; Outpost does not silently bypass provider approvals.

**Stop reply** terminates that reply's process tree and preserves the conversation. It does not undo files already changed. Inspect changes in **Git** before committing. Only one assistant runs in the same workspace or conversation at a time.

## Persistence

- Conversation IDs and the bounded Outpost transcript live in `.wfy/chats` under the host's workspace root. These are personal state, excluded from repository/release packaging.
- The CLI's own session directory holds its full context. Keep both the workspace state and the provider's user configuration/session directories when moving hosts.
- Phone disconnects do not stop a reply. A host shutdown stops a running reply; the next message resumes the saved conversation.
- Unsent phone messages are encrypted in the Android vault. Failed sends retain the draft, and retries use the same request ID to avoid duplicate turns.
- Removing a chat deletes its Outpost display history, not the original CLI conversation or files edited during the work.

Current bounds: 256 Outpost chats, four simultaneous replies across separate workspaces, 64 KiB per prompt, a 4 MiB display history per chat and a 30-minute reply timeout. Individual tool output is shortened. The full provider conversation remains available through its CLI.

Codex protocol reference: [Non-interactive mode](https://developers.openai.com/codex/noninteractive/).
