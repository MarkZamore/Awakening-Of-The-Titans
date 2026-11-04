"""Reproducible reflection-only Java21 QA probes; never installs a mod or edits a lock."""
import argparse, hashlib, os, subprocess, tempfile, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
NATIVE_CLASS = 'dev/lanminecraft/aotlock/test/NativeResourcesTest.class'
NATIVE_CLASS_SHA = '5eabfad698d45ab11c67cc45b5ef904a5b3ffb057605b59c83e9961183dd4310'

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jdk',type=Path,default=Path(r'C:\Tools\jdk-25.0.2'))
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--native-audit-jar',type=Path,required=True)
    args=p.parse_args();out=args.output.resolve()
    if 'artifacts' not in (part.lower() for part in out.parts):raise SystemExit('Output must be below artifacts')
    out.parent.mkdir(parents=True,exist_ok=True)
    compiler=args.jdk/'bin'/('javac.exe' if os.name=='nt' else 'javac')
    version=subprocess.check_output([str(compiler),'-version'],text=True).strip()
    if version!='javac 25.0.2':raise SystemExit('Pinned javac25.0.2 is required')
    with zipfile.ZipFile(args.native_audit_jar) as z: native=z.read(NATIVE_CLASS)
    if hashlib.sha256(native).hexdigest()!=NATIVE_CLASS_SHA:raise SystemExit('Native audit class changed; review and pin its source before rebuilding')
    with tempfile.TemporaryDirectory(prefix='aot-probes-',dir=out.parent) as temp:
        classes=Path(temp)
        subprocess.run([str(compiler),'--release','21','-g:none','-encoding','UTF-8','-d',str(classes),*[str(p) for p in sorted(ROOT.glob('*.java'))]],check=True)
        entries={NATIVE_CLASS:native,'META-INF/MANIFEST.MF':b'Manifest-Version: 1.0\nAgent-Class: AotAdditionalProbeAgent\n\n'}
        for f in classes.rglob('*.class'):entries[f.relative_to(classes).as_posix()]=f.read_bytes()
        with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_STORED) as z:
            for name,data in sorted(entries.items()):
                info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.create_system=3;info.external_attr=0o100644<<16;z.writestr(info,data)
    print(out);print(hashlib.sha256(out.read_bytes()).hexdigest())

if __name__=='__main__':main()
