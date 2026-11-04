#!/usr/bin/env python3
"""Build the optional native Multiplayer provider-boundary QA fixture."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, default=Path(r'C:\Tools\jdk-25.0.2'))
    parser.add_argument('--minecraft-jar', type=Path, required=True)
    parser.add_argument('--brigadier-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    minecraft = args.minecraft_jar.resolve()
    brigadier = args.brigadier_jar.resolve()
    with zipfile.ZipFile(minecraft) as archive:
        if json.loads(archive.read('version.json'))['id'] != '1.21.1':
            raise ValueError('Native fixture requires Minecraft 1.21.1')
        for name in ['net/minecraft/class_310', 'net/minecraft/class_332', 'net/minecraft/class_500']:
            archive.getinfo(name + '.class')
    root = Path(__file__).resolve().parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    compiler = args.jdk / 'bin' / ('javac.exe' if (args.jdk / 'bin/javac.exe').is_file() else 'javac')
    with tempfile.TemporaryDirectory(prefix='native-multiplayer-', dir=output) as work:
        classes = Path(work) / 'classes'
        classes.mkdir()
        subprocess.run([str(compiler), '--release', '21', '-g:none', '-encoding', 'UTF-8', '-cp',
                        os.pathsep.join([str(minecraft), str(brigadier)]), '-d', str(classes),
                        str(root / 'NativeRecordingDrawContext.java'), str(root / 'NativeMultiplayerSteamProbe.java')], check=True)
        jar = output / 'aot-native-multiplayer-qa-probe.jar'
        with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_STORED) as archive:
            for path in sorted(classes.rglob('*.class')):
                entry = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (1980, 1, 1, 0, 0, 0))
                entry.create_system = 0
                archive.writestr(entry, path.read_bytes())
    receipt = {name: hashlib.sha256(path.read_bytes()).hexdigest()
               for name, path in [('minecraftJarSha256', minecraft), ('brigadierJarSha256', brigadier), ('probeJarSha256', jar)]}
    (output / 'build-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print(str(jar))
    print('SHA256 ' + receipt['probeJarSha256'])


if __name__ == '__main__':
    main()
