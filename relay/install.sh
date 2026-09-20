#!/usr/bin/env bash
set -euo pipefail
if [[ ${EUID} -ne 0 ]]; then echo 'Run with sudo bash install.sh /path/to/outpost-relay'; exit 1; fi
binary=${1:?Provide the compiled Linux outpost-relay binary}
control_port=${OUTPOST_RELAY_CONTROL_PORT:-8443}
phone_port=${OUTPOST_RELAY_PHONE_PORT:-2223}
for port in "$control_port" "$phone_port"; do
  if [[ ! $port =~ ^[0-9]+$ ]] || ((port < 1024 || port > 65535)); then echo 'Use ports between 1024 and 65535'; exit 1; fi
done
if [[ $control_port == "$phone_port" ]]; then echo 'Relay ports must differ'; exit 1; fi
id outpost-relay >/dev/null 2>&1 || useradd --system --home-dir /var/lib/outpost-relay --shell /usr/sbin/nologin outpost-relay
install -d -m 700 -o outpost-relay -g outpost-relay /var/lib/outpost-relay
install -m 755 "$binary" /usr/local/bin/outpost-relay.new
mv /usr/local/bin/outpost-relay.new /usr/local/bin/outpost-relay
if [[ ! -f /var/lib/outpost-relay/token ]]; then
  umask 077
  head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > /var/lib/outpost-relay/token
  chown outpost-relay:outpost-relay /var/lib/outpost-relay/token
fi
cat > /etc/systemd/system/outpost-relay.service <<EOF
[Unit]
Description=Outpost laptop relay
After=network-online.target
Wants=network-online.target
[Service]
User=outpost-relay
Group=outpost-relay
ExecStart=/usr/local/bin/outpost-relay
Environment=OUTPOST_RELAY_CONTROL=0.0.0.0:$control_port
Environment=OUTPOST_RELAY_HTTP=127.0.0.1:8444
Environment=OUTPOST_RELAY_PUBLIC=0.0.0.0:$phone_port
Environment=OUTPOST_RELAY_TOKEN_FILE=/var/lib/outpost-relay/token
Environment=OUTPOST_RELAY_IDENTITY=/var/lib/outpost-relay/host_ed25519
Restart=on-failure
RestartSec=3
TimeoutStopSec=15
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/outpost-relay
MemoryMax=160M
TasksMax=128
LimitNOFILE=1024
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable outpost-relay >/dev/null
systemctl restart outpost-relay
for _ in $(seq 1 30); do
  if [[ -s /var/lib/outpost-relay/host_ed25519 ]] && systemctl is-active --quiet outpost-relay; then break; fi
  sleep 0.2
done
systemctl is-active --quiet outpost-relay
echo "Laptop outbound SSH port: $control_port"
echo "Phone SSH port: $phone_port (opens when the laptop connects)"
echo 'Relay fingerprint:'
runuser -u outpost-relay -- /usr/local/bin/outpost-relay fingerprint
echo 'Relay token is saved in /var/lib/outpost-relay/token. Copy it privately into the desktop VPS relay settings.'
echo 'Allow both TCP ports in your VPS firewall and cloud security rules. This service does not change existing firewall rules.'
