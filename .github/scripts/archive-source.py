import subprocess
import sys
import zipfile
from pathlib import Path

destination, prefix = sys.argv[1:]
paths = subprocess.check_output(["git", "ls-files", "--recurse-submodules", "-z"])
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
    for raw_path in paths.split(b"\0"):
        if raw_path:
            path = Path(raw_path.decode("utf-8"))
            archive.write(path, prefix + path.as_posix())
