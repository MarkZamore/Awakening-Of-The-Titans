#!/usr/bin/env python3
"""Build optional reflection-only QA probe; never installs a gameplay mod."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, default=Path(r'C:\Tools\jdk-25.0.2'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    compiler = args.jdk / 'bin' / ('javac.exe' if (args.jdk / 'bin/javac.exe').is_file() else 'javac')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='xaero-probe-', dir=output) as work:
        classes = Path(work) / 'classes'
        classes.mkdir()
        subprocess.run([str(compiler), '--release', '21', '-g:none', '-encoding', 'UTF-8',
                        '-d', str(classes), str(root / 'XaeroProbe.java')], check=True)
        jar = output / 'aot-xaero-qa-probe.jar'
        with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_STORED) as archive:
            for path in sorted(classes.rglob('*.class')):
                entry = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (1980, 1, 1, 0, 0, 0))
                entry.create_system = 0
                archive.writestr(entry, path.read_bytes())
    print(str(jar))
    print('SHA256 ' + hashlib.sha256(jar.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
