# Release preparation

Build requirements: Go 1.26+, .NET 10 SDK, JDK 17/21, Android SDK 36 and Python 3. The Windows package includes its .NET runtime. The Android release uses the existing private `.signing` keystore; retain that key for future updates.

```powershell
.\gradlew.bat assembleDebug assembleRelease lintRelease
powershell -ExecutionPolicy Bypass -File scripts/build-windows.ps1
powershell -ExecutionPolicy Bypass -File scripts/build-relay.ps1
powershell -ExecutionPolicy Bypass -File scripts/build-server.ps1
python scripts/package-release.py
```

Exit the desktop host before replacing its running Windows binaries, or pass `-OutputDirectory dist/windows-next` to the Windows build script. Restarting the backend ends its active terminals.

Upload these files from `dist/release/` to a GitHub release:

| Asset | Purpose |
|---|---|
| `outpost.apk` | Personally signed Android release APK |
| `outpost-windows.zip` | Native Windows x64 desktop UI and backend |
| `outpost-server.tar.gz` | Linux server source and Docker deployment scripts |
| `outpost-server-linux-amd64.tar.gz` | Native Linux x64 backend binary and setup notes |
| `outpost-relay-linux-amd64.tar.gz` | VPS relay binary, systemd installer and HTTPS proxy guide |
| `outpost-branding.zip` | Transparent SVG/PNG marks, app icon and Windows ICO |
| `SHA256SUMS` | SHA-256 hashes for release assets |
| `RELEASE_NOTES.md` | Prepared release description |

GitHub creates the full repository source archives automatically when a release points to a commit. Build artifacts are ignored by Git and are uploaded separately. Do not upload `.signing`, deployment secrets, local host settings, test workspaces or the staging directories inside `dist/`.

Before publishing, verify the APK signature with Android `apksigner verify --verbose dist/release/outpost.apk` and check the hashes. See `docs/VALIDATION.md` for the checks performed and their limits. Physical-device workflows use the matching debug signing key to retain the existing installation's data; a release-signed APK cannot replace that debug installation in place.

The source is maintained on a feature branch. Add your repository remote and commit/push the reviewed source on that branch, then create a PR. Release upload/publishing requires your GitHub repository and credentials. No repository push or public release is performed by these build scripts.
