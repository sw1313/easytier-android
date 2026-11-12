import subprocess
import sys
import zipfile
import os
from pathlib import Path

destination, prefix = sys.argv[1:]
paths = subprocess.check_output(["git", "ls-files", "--recurse-submodules", "-z"])
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
    for raw_path in paths.split(b"\0"):
        if raw_path:
            path = Path(raw_path.decode("utf-8"))
            name = prefix + path.as_posix()
            if path.is_symlink():
                entry = zipfile.ZipInfo(name)
                entry.create_system = 3
                entry.external_attr = 0o120777 << 16
                archive.writestr(entry, os.readlink(path))
            else:
                archive.write(path, name)
