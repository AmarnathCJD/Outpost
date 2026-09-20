# Outpost VPS relay

This small Go service connects an outbound laptop to a stable VPS address. It carries the phone's encrypted SSH stream to the laptop. Workspace files, commands and terminal processes remain on Windows. Neither a static laptop IP nor an inbound laptop firewall rule is required. Networks must permit an outbound TCP connection to the relay's laptop port.

The two ports serve different purposes:

| Connection | Default port | Authentication |
|---|---:|---|
| Laptop to VPS | 8443 | Relay token; VPS SSH fingerprint pinned in desktop settings |
| Phone to laptop through VPS | 2223 | Laptop SSH username and password/key; laptop host key |

Port 8443 uses SSH, not HTTPS. If a network permits only HTTPS traffic, choose a permitted SSH port or a VPN; moving SSH to port 443 does not make it HTTPS. With host discovery enabled, one relay serves multiple laptops through separate private routes. The legacy fixed phone port still serves one non-discovery laptop at a time.

## HTTPS / WebSocket access

When new VPS ports are blocked, use the existing HTTPS listener. The relay also listens on loopback port **8444** for `/laptop` and `/phone` WebSocket upgrades and `POST /discover` host lookup. Add this location to a TLS-enabled nginx server block for a domain you control, then run `nginx -t` and reload nginx:

```nginx
location ^~ /.well-known/outpost-relay/ {
    proxy_pass http://127.0.0.1:8444/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
    proxy_send_timeout 60s;
    proxy_buffering off;
    proxy_request_buffering off;
}
```

The desktop relay URL is `wss://YOUR_DOMAIN/.well-known/outpost-relay/laptop`; Android uses the same URL ending in `/phone`. HTTPS certificate validation and the pinned SSH host keys both remain enabled. With these URLs configured, only HTTPS port **443** must be externally reachable. The two SSH ports remain the relay's internal destinations. A missing laptop returns HTTP 503 rather than accepting a broken connection.

Keep the relay domain and TLS certificate renewed. Back up the original nginx configuration before adding the reserved route.

## Deploy on Ubuntu x64

Build with Go 1.26 or newer:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-relay.ps1
scp dist/outpost-relay relay/install.sh YOUR_VPS:/tmp/
ssh YOUR_VPS 'sudo bash /tmp/install.sh /tmp/outpost-relay'
```

For raw SSH mode, allow inbound TCP 8443 and 2223 in the VPS firewall and cloud security list. For WebSocket mode, configure the HTTPS route above instead. Existing SSH port 22 and the Ubuntu-hosted workspace stay separate. The installer preserves the relay token and host identity on updates. To use other ports, set `OUTPOST_RELAY_CONTROL_PORT` and `OUTPOST_RELAY_PHONE_PORT` when running the installer.

Privately copy `/var/lib/outpost-relay/token` from the VPS into the desktop's **VPS relay** section. Enter the VPS address, both ports and the fingerprint printed by the installer. Stop hosting, save these settings, and start hosting. Wait for **Relay: connected**.

On the phone, set SSH host to the VPS address, SSH port to 2223, username to `outpost`, and password to **Copy laptop token**. Set backend host to `127.0.0.1` and backend port to the desktop's port (for example 8788). The API token is also the laptop token. Verify the phone's fingerprint against **Show SSH fingerprint** on the laptop. The relay token never belongs in Android's connection settings.

For paired hosts, the desktop registers the laptop and the phone selects its host ID automatically; see the pairing guide in the repository. The encrypted registry is stored as `hosts.json` beside the relay SSH identity. Preserve it across updates. No Nginx change is required when the full prefix above is already proxied.

The legacy phone port opens only while its laptop is connected. Wi-Fi interruptions trigger automatic reconnection with bounded backoff. Terminal sessions survive a relay/network interruption while the laptop backend remains running. Laptop sleep, shutdown or stopping the backend makes its workspace unavailable; stopping the backend ends terminals.

Inspect the service using `sudo systemctl status outpost-relay` and `sudo journalctl -u outpost-relay`. Restart it with `sudo systemctl restart outpost-relay`. The laptop should reconnect automatically. A mismatched VPS fingerprint is rejected and reported in the desktop UI.

There is no shell or workspace file API on the relay. Registered hosts receive loopback-only ephemeral listeners, selected by authenticated host routes. Legacy clients can allocate only the configured phone port. Control connections and concurrent phone streams are bounded. Relay credentials and host identity live in `/var/lib/outpost-relay`, outside deployment archives.
