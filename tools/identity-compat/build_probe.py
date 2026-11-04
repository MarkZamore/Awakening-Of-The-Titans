"""Build the test-only native Fabric skin observer; never writes mods/ or locks."""
from pathlib import Path
import argparse, hashlib, json, subprocess, tempfile, zipfile

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jdk', type=Path, default=Path(r'C:\Tools\jdk-21.0.12.1+1'))
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    sources = sorted(Path(__file__).resolve().parent.glob('Native*Probe.java'))
    javac = a.jdk / 'bin' / ('javac.exe' if (a.jdk / 'bin/javac.exe').exists() else 'javac')
    a.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='identity-probe-', dir=a.out) as t:
        classes = Path(t) / 'classes'
        subprocess.run([str(javac), '--release', '21', '-g:none', '-encoding', 'UTF-8', '-d', str(classes), *(str(s) for s in sources)], check=True)
        output = a.out / 'aot-native-identity-probe.jar'
        with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as z:
            for f in sorted(classes.rglob('*.class')):
                info = zipfile.ZipInfo(f.relative_to(classes).as_posix(), (1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                z.writestr(info, f.read_bytes())
    print(json.dumps({'jar': str(output.resolve()), 'sha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'gameplayAcceptanceClaimed': False}))

if __name__ == '__main__': main()
