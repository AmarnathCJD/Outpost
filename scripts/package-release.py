"""Build upload-ready release bundles from explicitly selected artifacts and source."""
from pathlib import Path
import hashlib
import shutil
import tarfile
import zipfile

root = Path(__file__).resolve().parent.parent
dist = root / 'dist'
release = dist / 'release'
release.mkdir(parents=True, exist_ok=True)
required = {
    'APK': root / 'app/build/outputs/apk/release/app-release.apk',
    'Windows ZIP': dist / 'outpost-windows.zip',
    'Linux server': dist / 'outpost-server-linux-amd64',
    'Linux relay': dist / 'outpost-relay',
}
for name, path in required.items():
    if not path.is_file():
        raise SystemExit(f'Missing {name}: run the release builds first.')
shutil.copy2(required['APK'], dist / 'outpost.apk')

def metadata(info):
    info.uid = info.gid = 0
    info.uname = info.gname = ''
    info.mode = 0o755 if info.name.endswith('.sh') or Path(info.name).name in {'outpost-server', 'outpost-relay'} else 0o644
    return info

blocked = {'secrets', '.env', '.signing', 'build', '__pycache__'}
with tarfile.open(dist / 'outpost-server.tar.gz', 'w:gz') as archive:
    files = [root / 'README.md', root / '.dockerignore']
    for directory in ('server', 'deploy', 'docs', 'scripts', 'relay', 'assets/branding', 'assets/screenshots'):
        files.extend(p for p in (root / directory).rglob('*') if p.is_file())
    for path in sorted(files):
        relative = path.relative_to(root)
        if blocked.intersection(relative.parts) or path.suffix in {'.exe', '.log', '.jks', '.pyc', '.pem'} or path.name.startswith('wfy-server'):
            continue
        if path.is_symlink():
            raise SystemExit(f'Refusing to package symlink: {relative}')
        archive.add(path, arcname=relative.as_posix(), recursive=False, filter=metadata)

notice = root / 'docs/THIRD-PARTY-NOTICES.txt'
for name, members in {
    'outpost-server-linux-amd64.tar.gz': [(required['Linux server'], 'outpost-server'), (root / 'docs/LINUX-NATIVE.md', 'README.md'), (root / 'docs/ASSISTANTS.md', 'ASSISTANTS.md'), (root / 'docs/PAIRING.md', 'PAIRING.md'), (notice, notice.name)],
    'outpost-relay-linux-amd64.tar.gz': [(required['Linux relay'], 'outpost-relay'), (root / 'relay/install.sh', 'install.sh'), (root / 'relay/README.md', 'README.md'), (root / 'docs/PAIRING.md', 'PAIRING.md'), (notice, notice.name)],
}.items():
    with tarfile.open(dist / name, 'w:gz') as archive:
        for path, target in members:
            archive.add(path, arcname=target, recursive=False, filter=metadata)

with zipfile.ZipFile(dist / 'outpost-branding.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted((root / 'assets/branding').iterdir()):
        if path.is_file():
            archive.write(path, path.name)
    archive.write(root / 'desktop/outpost.ico', 'outpost.ico')

names = ['outpost.apk', 'outpost-windows.zip', 'outpost-server.tar.gz', 'outpost-server-linux-amd64.tar.gz', 'outpost-relay-linux-amd64.tar.gz', 'outpost-branding.zip']
for name in names:
    shutil.copy2(dist / name, release / name)
shutil.copy2(root / 'docs/RELEASE_NOTES.md', release / 'RELEASE_NOTES.md')
names.append('RELEASE_NOTES.md')
with (release / 'SHA256SUMS').open('w', encoding='utf-8', newline='\n') as checksums:
    for name in names:
        checksums.write(f"{hashlib.sha256((release / name).read_bytes()).hexdigest()}  {name}\n")
shutil.copy2(release / 'SHA256SUMS', dist / 'SHA256SUMS')
print('Upload-ready release assets: dist/release/')
