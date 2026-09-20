# Outpost 0.4.1

Work from your phone using the workspace and AI conversations already on your laptop.

- Native Codex and Claude chats with saved conversation IDs, real context resumption, host CLI configuration, tool activity, Stop, copyable responses and encrypted message drafts.
- Custom Codex provider detection; existing host credentials and normal Windows shell profiles are used automatically.
- Terminal commands execute from the keyboard Send/Enter action and the toolbar Enter button. Tapping the terminal focuses the native composer, avoiding Android's unreliable hidden terminal text input.
- Terminal output stays visible through keyboard resizing and reconnects; viewport sizing fixes the blank terminal display.
- Logical line numbers remain visible with wrapping enabled, and opening/closing the keyboard preserves the scrolled editor position.
- The last workspace and open file reopen on connection. Laptop edits automatically update clean editor buffers; unsaved phone edits are preserved and stale saves are rejected.
- Private laptop discovery, QR pairing, multiple relay hosts and the GitHub project README from 0.4.0 are included.

The VPS relay protocol is unchanged; the relay binary remains 0.4.0. Android and the Windows/Linux workspace hosts are 0.4.1. Validation details and external-account limits are recorded in `docs/VALIDATION.md`.

The existing phone installation uses the debug signing key to preserve its data. The release APK is signed separately and cannot replace a debug-signed install in place. Retain the release keystore for future updates.
