import hashlib
import json
import subprocess
import sys
import tarfile
import zipfile
import re
from pathlib import Path

channel, reference, commit = sys.argv[1:]
directory = Path(".ci-peer").resolve()
directory.mkdir(exist_ok=True)
download = directory / "upstream.archive"
if channel == "custom":
    repository = "repos/225284228a-droid/EasyTier-Custom"
    metadata = json.loads(subprocess.check_output(["gh", "api", f"{repository}/actions/runs/{reference}/artifacts"]))
    artifact = next(item for item in metadata["artifacts"] if item["name"] == "easytier-linux-x86_64.tar.gz" and not item["expired"])
    with download.open("wb") as output:
        subprocess.run(["gh", "api", f"{repository}/actions/artifacts/{artifact['id']}/zip"], stdout=output, check=True)
else:
    repository = "repos/EasyTier/EasyTier"
    release = json.loads(subprocess.check_output(["gh", "api", f"{repository}/releases/tags/{reference}"]))
    if channel == "stable":
        source = json.loads(subprocess.check_output(["gh", "api", f"{repository}/commits/{reference}"]))
        actual_commit = source["sha"]
    else:
        match = re.search(r"Commit:\s*\[?([0-9a-f]{40})", release["body"])
        actual_commit = match.group(1) if match else release["target_commitish"]
    if actual_commit != commit:
        raise RuntimeError("Official reference release changed during the build")
    artifact = next(item for item in release["assets"] if re.fullmatch(r"easytier-linux-x86_64-.*\.zip", item["name"]))
    subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--max-time", "180", "--silent", "--show-error", "--output", str(download), artifact["browser_download_url"]], check=True)
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
if channel == "custom" and commit not in (peer.parent / "COMMIT.txt").read_text():
    raise RuntimeError("Reference peer source commit mismatch")
checksum_file = peer.parent / "SHA256SUMS"
for row in checksum_file.read_text().splitlines() if checksum_file.exists() else []:
    expected, name = row.split(maxsplit=1)
    path = (peer.parent / name.lstrip("*").removeprefix("./")).resolve()
    if not path.is_relative_to(directory):
        raise RuntimeError("Invalid checksum file path")
    with path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
            raise RuntimeError("Reference peer file checksum mismatch")
peer.chmod(0o755)
print(peer)
