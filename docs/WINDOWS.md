# Outpost on Windows

Windows 10 version 1809+ or Windows 11, x64. No Docker, WSL or .NET installation is required. The laptop runs the Go backend with built-in SSH; the phone connects directly to it. Developer tools such as Git and Go run natively as your Windows user.

## Start

1. Extract **all** of `outpost-windows.zip` to a permanent folder such as `C:\Outpost`. Open `Outpost.exe`.
2. Under **Workspace**, choose the parent folder containing your Git repositories. For example, choose `C:\Projects` for `C:\Projects\backend`. Repositories appear on your phone without copying or cloning them again.
3. Select **Start hosting**. Use **Developer tools** to inspect/install missing tools and log in to GitHub, Codex or Claude. Setup may request elevation for installers; the host itself does not need administrator rights.
4. In **Phone access**, choose **Allow phone connections** once and accept Windows' administrator prompt. This adds a firewall rule for the Outpost executable and SSH port; no Windows optional feature download is needed. **Git & environment** edits the same settings used by Android; environment changes apply to new sessions.

Keep the EXEs and setup script together. Closing the window hides it in the system tray. Choose **Exit and stop hosting** in the tray menu to quit. **Open at Windows sign-in** and **Start hosting when Outpost opens** are independent options; enable both for automatic hosting after signing in.

For updates, exit Outpost before replacing the extracted files. Host preferences and workspace data are stored separately and remain in place. If another program already uses the backend port, choose a different free port; the relay backend port need not change.

## Pair a phone through the relay

Configure **VPS relay** with its HTTPS URL, then open **Pair a phone**. Choose a laptop name and enable discovery. Start hosting and wait for **Registered**. Scan the QR in Android **Hosts**, or paste the pairing invitation. The phone saves the laptop and fills SSH credentials automatically. Hosts shows online/offline status and supports multiple laptops.

Pairing codes are stored with Windows DPAPI. Stop hosting before changing the name or code; stopping ends active terminals. See [pairing and discovery](PAIRING.md) for the full workflow.

## Connect directly on the same network

1. Leave **Use an external SSH relay instead** switched off. Direct laptop access is the default, including when upgrading older configurations.
2. Select the laptop Wi-Fi/Ethernet address in **Phone access**, check its Outpost SSH username and port, and save.
3. On Android, use that laptop address as SSH host (for example `192.168.1.100`), normally port **2222**, and SSH username **outpost**. Use **Copy laptop token** as the SSH password. This login belongs to Outpost and is independent of the Windows account password.
4. **Backend host** stays `127.0.0.1`: this means localhost on the laptop reached through SSH. Use the backend port shown by the desktop host (normally `8787`, or a configured port such as `8788`).
5. Use **Copy laptop token** for the API token. Connect and verify the laptop SSH fingerprint against **Show SSH fingerprint** in the desktop UI. All tools run on this laptop's files.

An active hotspot may show a second address. Choose the network the phone can reach. Refresh detects current adapters; DHCP can change Wi-Fi addresses. The desktop status checks SSH locally; phone reachability also depends on the network and firewall.

Outpost keeps its stable SSH host identity in `%LOCALAPPDATA%\Outpost\ssh`. To use key authentication, add your public key as a plain OpenSSH line to `authorized_keys` there, then import the matching private key in Android. Restricted key lines with OpenSSH options are rejected. Do not delete `host_ed25519` during upgrades: it identifies this laptop to the phone.

Embedded SSH supports local TCP forwarding for the API and service previews, with password and public-key authentication. Use Outpost Sessions for PowerShell; this endpoint does not offer SSH shell, exec or SFTP. Stop hosting before changing the SSH username or port; start again and rerun the firewall setup after moving the executable or changing its port.

## VPS relay for changing Wi-Fi addresses

The **VPS relay** section connects the laptop out to a separate Go relay service. Turn it on, set the VPS address, relay token, fingerprint and ports, then restart hosting. For networks without open extra ports, also enter its `wss://.../laptop` URL. The phone receives the matching `/phone` URL in the desktop connection details.

The laptop reconnects automatically when Wi-Fi returns. The phone always uses the VPS address; its SSH username, password/API token and verified host key still belong to the laptop. No static laptop IP or inbound laptop firewall rule is needed. **Relay: connected** means the remote phone forward has been allocated successfully. Error details appear on the VPS relay page.

For HTTPS access, use `wss://YOUR_DOMAIN/.well-known/outpost-relay/laptop` on Windows and the matching `/phone` URL on Android. Use the VPS hostname, configured phone SSH port, laptop username and laptop backend port shown by the desktop. Keep Outpost running; sleep/shutdown makes the laptop workspace unavailable.

See [relay deployment](../relay/README.md) for the standalone service and HTTPS proxy configuration. This relay is separate from the legacy OpenSSH reverse-forward option below.

## Reach your laptop over mobile data

A private Wi-Fi address such as `192.168.x.x` cannot be reached from the public internet by itself. Use a VPN connecting your phone and laptop, or configure your router's public address and SSH port forwarding if your connection permits it.

**Phone access → Set up access away** offers a Tailscale installer. Sign in on the laptop and install/sign in to Tailscale on the phone. Allow Outpost through the firewall, then select the laptop Tailscale address or enter its VPN hostname in the address override. The laptop remains the workspace server; no Ubuntu server is required. Tailscale coordinates peer connections and may relay encrypted traffic when a direct peer connection is unavailable.

VPN authentication and router setup require your own accounts/network settings. Installing Outpost alone does not make a private laptop address reachable over mobile data.

For native service previews in direct mode, use Android **Running services** with host `127.0.0.1` and the service's actual laptop port, such as `8080`.

## Optional external relay

Use your Ubuntu server as an SSH relay. On Windows, configure a key-based OpenSSH alias in `%USERPROFILE%\.ssh\config` and connect once with `ssh YOUR_ALIAS` to verify its host fingerprint. Password-protected keys must already be unlocked in Windows `ssh-agent` for unattended relay connections.

1. Explicitly enable **Use an external SSH relay instead** in **Phone access**, then enter that alias and a free relay backend port, normally **18787**.
2. **Save and reconnect relay**, then check its status. Outpost reconnects after network interruptions.
3. On Android, use the Ubuntu server's SSH hostname, port and username with your phone's password or private key.
4. Set **Backend host** to `127.0.0.1`, **Backend port** to `18787`, and use **Copy laptop token** in the desktop window for the API token.

The Ubuntu-hosted workspace remains available at backend port **8787**, using the Ubuntu token. These are two separate hosts. Choose the laptop connection to work on your actual laptop files.

Only the relay server's existing SSH port needs to be reachable. The backend listens on Windows loopback and the reverse forward listens on Ubuntu loopback. No router port forwarding or Windows inbound firewall rule is needed. The relay server must allow TCP forwarding. The host uses your installed OpenSSH client and does not copy private keys.

To preview a service on the laptop, enable the optional service forward (e.g. local **8080** → relay **18080**). In Android **Running services**, forward host `127.0.0.1`, port `18080`. These service ports are separate from the backend connection.

## Native terminals and persistence

Windows sessions run PowerShell 7 if installed, otherwise Windows PowerShell. Interactive output, keyboard input, resize and reconnect use the native Windows console (ConPTY). PowerShell syntax applies to saved commands. Codex and Claude use their Windows CLI installations and your Windows account's login state.

Phone disconnects leave terminal processes running. Reconnecting attaches to the existing session and replays up to 1 MiB of recent output. Up to 32 sessions may run together. Stopping Outpost or shutting down Windows ends those processes; repository files, tool login state and settings persist.

Phone edits are saved directly to the laptop workspace; desktop edits become visible when refreshed on the phone. Saves check file revision and branch to catch concurrent edits. Unsaved phone drafts remain on that phone. There is no automatic file replication between the laptop and Ubuntu. Normal Git fetch/push is available when moving work between those hosts.

The keep-awake option prevents idle system sleep while hosting. It cannot override a closed-lid action, forced sleep, shutdown or organizational power policy. Keep the laptop powered and online to use it remotely.

## Database and configuration

**Developer tools → SQL Server setup** launches the native SQL Server Express installer. Finish its configuration, enable TCP/IP and configure the real instance/port and credentials under **Git & environment**. An existing SQL Server installation or reachable remote database also works. The Ubuntu Docker database is not automatically copied to Windows.

Host configuration is `%LOCALAPPDATA%\Outpost\host.json`. Its API token, pairing code and VPS relay token are protected with Windows DPAPI for the current Windows user. Workspace Git/environment settings are in `<workspace root>\.wfy\settings.json`; environment secrets are stored there, so keep the folder private. Windows tool logins use their normal user-profile locations. No credentials are included in release archives.

## Run from an administrator terminal

The GUI can be launched from a normal or administrator PowerShell terminal:

```powershell
cd C:\Outpost
.\Outpost.exe
```

For a manual backend process, use an existing long random token saved privately:

```powershell
$env:WFY_ROOT = 'C:\Projects'
$env:WFY_LISTEN = '127.0.0.1:8787'
$env:WFY_SSH_LISTEN = '0.0.0.0:2222'
$env:WFY_SSH_USER = 'outpost'
$env:WFY_SSH_DIR = "$env:LOCALAPPDATA\Outpost\ssh"
$env:WFY_TOKEN_FILE = "$env:LOCALAPPDATA\Outpost\manual-token.txt"
.\outpost-server.exe
```

Keep this terminal open; Ctrl+C stops it. The desktop GUI manages its own token, host process and relay automatically, so it is the recommended daily entry point.

Build from source with `powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1` (Go 1.26+ and .NET 10 SDK required). The resulting ZIP includes a self-contained desktop application.
