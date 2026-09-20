# Pairing and host discovery

Pairing removes the SSH-host, port, password and API-token form from everyday laptop setup. Your laptop still needs its VPS relay configuration once. Each phone then needs only a QR scan, an invitation, or the relay address and laptop pairing code.

## On the laptop

1. Configure **VPS relay**, including its HTTPS/WebSocket URL, relay token and verified relay SSH fingerprint. Enable the relay.
2. Open **Pair a phone**. Choose a laptop name and enable discovery. Save changes while hosting is stopped, then start hosting.
3. Wait for **Registered - ready to pair**. Select **Show QR code**, or copy the pairing invitation.

The generated pairing code is private. It is stored with Windows DPAPI and remains stable across normal restarts. Changing the laptop's name or pairing settings requires stopping and restarting hosting; stopping the host ends running terminals.

## On Android

Open **Work → Pair a laptop**, or **Settings → Hosts**.

- **Scan laptop QR** reads the desktop QR with the camera. Camera permission is requested only when scanning; manual pairing also works.
- **Pairing invitation** accepts the desktop's copied invitation and fills both fields.
- **Relay address + pairing code** works when scanning/copying an invitation is inconvenient. The relay address may be its HTTPS domain or the full relay URL.

Select **Find and pair laptop**, then tap **Connect** on its saved host card. SSH credentials and the laptop identity are filled from the encrypted pairing profile. No manual host-key prompt is expected for a correctly paired laptop; a different key is rejected.

Saved hosts can be searched by name or relay address. Online hosts appear first; offline hosts retain their last-seen time. Discovery refreshes when the app opens/returns to the foreground and every 15 seconds while Hosts is visible. **Connect to my last laptop when Outpost opens** is optional and applies when that laptop is online during the startup discovery check.

Direct SSH remains under **Settings → Connection**. No relay is required for direct LAN/VPN connections.

## Multiple laptops

Every laptop uses its own SSH identity and generated pairing code. It registers through an authenticated outbound connection and receives a separate loopback listener on the VPS. Phone WebSocket connections select the host by ID and supply its pairing lookup credential. There is no shared public host listing.

Use a distinct name for each laptop. Copying another laptop's entire host configuration and SSH key copies its identity too; the relay rejects simultaneous registrations with that identity. Install normally on each machine instead.

The phone includes the paired laptop ID in its workspace identity, keeping saved drafts, bookmarks and terminal controls separate between laptops.

## Offline hosts, new codes and forgetting

- The laptop must be awake, online and hosting. The relay reconnects automatically after network interruptions. Phone terminal sessions can resume while their host processes remain alive.
- The relay persists encrypted host records in `hosts.json`, beside its SSH identity. A relay restart loads all saved hosts as offline until they reconnect.
- **Generate new code** replaces that laptop's discovery access on its next registration. Previously paired phones must pair again. This changes pairing access, not the existing API token or direct SSH credentials.
- **Forget** removes a pairing from that phone and clears its active connection if selected. It does not delete laptop files or revoke another phone's credentials.

## Protocol and storage

The desktop generates a random 128-bit code. The backend derives a lookup ID and an AES-GCM profile key using PBKDF2-HMAC-SHA256 and separate derivation labels. The profile contains the laptop name, SSH public key, username, backend port and API credential; only the encrypted profile is sent to the relay. The lookup credential alone cannot decrypt it.

Registration is authenticated with the relay token and signed by the laptop's SSH host key over the current SSH session ID. Replaying a registration on another connection or claiming another laptop identity is rejected. The phone checks both the authenticated encrypted profile and the laptop's SSH key before accessing its API.

The HTTPS proxy must forward the complete relay path, including `POST /discover`, `/laptop` and `/phone`. The existing Nginx prefix configuration in the relay guide already does this. Profiles and credentials must not be added to access logs. Pairing invitations place their contents in a URL fragment and are processed inside the app.

For a native host, enable pairing with `WFY_PAIRING_PHRASE` (32 random hexadecimal characters, with optional hyphens) and optionally `WFY_HOST_NAME`, alongside the normal embedded SSH and relay environment settings. Use a persistent SSH identity directory and an explicit `WFY_LISTEN` address. The Windows desktop manages these automatically.
