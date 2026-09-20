# Build and deployment checks

Automated test sources, phone test runners, and disposable fixtures were removed at the owner's request on 2026-09-20. Earlier execution results are retained in VALIDATION.md as historical evidence.

Build Android using the existing personal signing key:

```powershell
.\gradlew.bat assembleRelease lintRelease
python scripts/package-release.py
```

Compile and inspect the backend:

```sh
cd server
go build ./...
go vet ./...
```

Build the native Windows desktop package (Go and .NET 10 SDK):

```powershell
.\scripts\build-windows.ps1
```

Open `dist/windows/Outpost.exe`, select a disposable repository parent, start hosting, and verify settings save/reload, terminal reconnect, host stop/start and relay reconnection. Use the relay details from **Phone access** to verify that the phone reaches the intended host. Native Windows paths and PowerShell commands differ from Linux paths and Bash commands.

After deploying, check both containers:

```sh
cd ~/outpost/deploy
sudo docker compose ps
sudo docker compose logs --tail=100 workspace
sudo docker compose logs --tail=100 mssql
```

Both services should report healthy. In the app, configure SSH and the server token, verify the host fingerprint, connect, and confirm that repositories and sessions load. Configure GitHub and provider sign-ins with your own accounts before using private repositories or authenticated AI sessions.

Use a disposable feature workspace for the first remote edit/save, stage/commit, terminal reconnect, service preview and PR checks. Remote Ubuntu deployment and account-backed workflows remain separate from the historical local WSL checks.
