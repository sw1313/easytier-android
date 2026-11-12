import hashlib
import json
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path

run_id, commit = sys.argv[1:]
repository = "repos/225284228a-droid/EasyTier-Custom"
metadata = json.loads(subprocess.check_output(["gh", "api", f"{repository}/actions/runs/{run_id}/artifacts"]))
artifact = next(item for item in metadata["artifacts"] if item["name"] == "easytier-linux-x86_64.tar.gz" and not item["expired"])
directory = Path(".ci-peer").resolve()
directory.mkdir(exist_ok=True)
download = directory / "upstream.archive"
with download.open("wb") as output:
    subprocess.run(["gh", "api", f"{repository}/actions/artifacts/{artifact['id']}/zip"], stdout=output, check=True)
with download.open("rb") as stream:
    digest = "sha256:" + hashlib.file_digest(stream, "sha256").hexdigest()
if artifact.get("digest") and digest != artifact["digest"]:
    raise RuntimeError("Upstream artifact digest mismatch")

if zipfile.is_zipfile(download):
    with zipfile.ZipFile(download) as archive:
        for member in archive.infolist():
            if not (directory / member.filename).resolve().is_relative_to(directory):
                raise RuntimeError("Invalid upstream ZIP path")
        archive.extractall(directory)
    nested = list(directory.rglob("*.tar.gz"))
    if nested:
        with tarfile.open(nested[0]) as archive:
            archive.extractall(directory, filter="data")
else:
    with tarfile.open(download) as archive:
        archive.extractall(directory, filter="data")

peer = next(directory.rglob("easytier-core"))
commit_file = peer.parent / "COMMIT.txt"
if commit not in commit_file.read_text():
    raise RuntimeError("Reference peer source commit mismatch")
for row in (peer.parent / "SHA256SUMS").read_text().splitlines():
    expected, name = row.split(maxsplit=1)
    path = (peer.parent / name.lstrip("*").removeprefix("./")).resolve()
    if not path.is_relative_to(directory):
        raise RuntimeError("Invalid checksum file path")
    with path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
            raise RuntimeError("Reference peer file checksum mismatch")
peer.chmod(0o755)
print(peer)
