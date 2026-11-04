"""Deterministic Java21 Fabric helper build; never installs output or edits pack locks."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, urllib.request, zipfile

ROOT = Path(__file__).resolve().parent
VERSION = '1.0.2'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def run(values, cwd=None):
    result = subprocess.run(list(map(str, values)), cwd=cwd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.stdout.strip(): print(result.stdout.strip()[-10000:])
    if result.stderr.strip(): print(result.stderr.strip()[-10000:])
    if result.returncode: raise SystemExit(result.returncode)
    return result.stdout.strip()

def args_file(path, values):
    path.write_text('\n'.join('"'+str(x).replace('\\','/')+'"' for x in values)+'\n', encoding='utf-8')
    return '@'+str(path)

def snapshot():
    return {p.relative_to(ROOT).as_posix():sha(p) for p in sorted(ROOT.rglob('*')) if p.is_file() and '__pycache__' not in p.parts}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--jdk',type=Path,default=Path(r'C:\Tools\jdk-25.0.2'))
    parser.add_argument('--native-shared',type=Path)
    parser.add_argument('--author-jar',type=Path)
    parser.add_argument('--mcef-jar',type=Path)
    parser.add_argument('--verify-installed',type=Path)
    options=parser.parse_args()
    output=options.output.resolve()
    if 'artifacts' not in [x.lower() for x in output.parts]: raise SystemExit('Output must be inside artifacts')
    output.mkdir(parents=True,exist_ok=True)
    jdk=options.jdk.resolve(); suffix='.exe' if os.name=='nt' else ''
    java=jdk/'bin'/('java'+suffix); javac=jdk/'bin'/('javac'+suffix)
    compiler=run([javac,'-version'])
    if '25.0.2' not in compiler:raise SystemExit('JDK25.0.2 is required for deterministic compilation')
    sources=snapshot(); digest=hashlib.sha256(json.dumps(sources,sort_keys=True).encode()).hexdigest()
    build=output/('build-'+digest[:16]);classes=build/'classes';classes.mkdir(parents=True,exist_ok=True)
    pins=json.loads((ROOT/'build-dependencies.json').read_text())
    dependencies={}
    for pin in pins['compile']+pins['nativeAudit']:
        path=output/'dependencies'/pin['fileName'];path.parent.mkdir(parents=True,exist_ok=True)
        if not path.exists():path.write_bytes(urllib.request.urlopen(pin['url'],timeout=120).read())
        if sha(path)!=pin['sha256']:raise SystemExit('Dependency digest mismatch: '+pin['fileName'])
        dependencies[pin['fileName']]=path.resolve()
    mixin=dependencies[pins['compile'][0]['fileName']]
    compile_cp=os.pathsep.join(str(dependencies[p['fileName']]) for p in pins['compile'])
    run([javac,args_file(build/'compile.args',['--release','21','-g:none','-encoding','UTF-8','-proc:none','-cp',compile_cp,'-d',classes,*sorted((ROOT/'src/main/java').rglob('*.java'))])])
    entries={'META-INF/MANIFEST.MF':b'Manifest-Version: 1.0\r\n\r\n','META-INF/LICENSE':(ROOT/'LICENSE').read_bytes()}
    for folder in [classes,ROOT/'src/main/resources']:
        for path in sorted(folder.rglob('*')):
            if path.is_file():entries[path.relative_to(folder).as_posix()]=path.read_bytes()
    jar=output/('aot-world-type-lock-'+VERSION+'.jar')
    with zipfile.ZipFile(jar,'w',compression=zipfile.ZIP_STORED) as archive:
        for name,data in sorted(entries.items()):
            if name.endswith('.class') and int.from_bytes(data[6:8],'big')!=65:raise SystemExit('Expected Java21 classes')
            if not name.endswith('.class'):data=data.replace(b'\r\n',b'\n')
            info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.create_system=3;info.external_attr=0o644<<16
            archive.writestr(info,data)
    report={'schemaVersion':1,'modId':'aot_world_type_lock','version':VERSION,'presetId':'attack_on_titan:paradis','minecraft':'1.21.1','fabricLoader':'0.19.5','compiler':compiler,'jar':str(jar),'sha256':sha(jar),'sourceSnapshot':sources,'nativeVerified':False,'runtimeVerified':False}
    if options.verify_installed and sha(options.verify_installed)!=sha(jar):raise SystemExit('Installed helper differs from reproducible build')
    if options.native_shared:
        shared=options.native_shared.resolve();descriptor=shared/'versions/1.21.1/1.21.1.json';metadata=json.loads(descriptor.read_text())
        game=shared/'versions/1.21.1/1.21.1.jar'
        if hashlib.sha1(game.read_bytes()).hexdigest()!=metadata['downloads']['client']['sha1']:raise SystemExit('Official client digest mismatch')
        mappings=output/'intermediary.tiny'
        with zipfile.ZipFile(dependencies['intermediary-1.21.1-v2.jar']) as archive:mappings.write_bytes(archive.read('mappings/mappings.tiny'))
        mapped=output/'minecraft-1.21.1-intermediary.jar'
        if not mapped.exists():run([java,'-Xmx1G','-jar',dependencies['tiny-remapper-0.11.2-fat.jar'],game,mapped,mappings,'official','intermediary'])
        libraries=[]
        for entry in metadata['libraries']:
            if entry.get('rules'):
                allowed=False
                for rule in entry['rules']:
                    required_os=rule.get('os',{}).get('name')
                    current_os='windows' if os.name=='nt' else 'linux'
                    if required_os is None or required_os==current_os:allowed=rule['action']=='allow'
                if not allowed:continue
            artifact=entry.get('downloads',{}).get('artifact',{})
            if not artifact or 'natives-' in artifact['path']:continue
            path=shared/'libraries'/artifact['path']
            if not path.is_file():raise SystemExit('Prepare native library '+str(path))
            if hashlib.sha1(path.read_bytes()).hexdigest()!=artifact['sha1']:raise SystemExit('Native library digest mismatch: '+str(path))
            libraries.append(path)
        # The remapper contains exact ASM9.7; the same Mixin implementation used by Fabric is tested.
        asm=[path for name,path in dependencies.items() if name.startswith('asm')]
        mcef_cp=[]
        if options.mcef_jar:
            if sha(options.mcef_jar)!='b5270016b85a6c3c26cc88c11ca3c128b937e6b98d3c86605d48502693f81553':raise SystemExit('MCEF exact JAR mismatch')
            mcef_cp=[options.mcef_jar.resolve()]
        cp=os.pathsep.join(map(str,[*mcef_cp,classes,ROOT/'src/main/resources',mapped,*[dependencies[p['fileName']] for p in pins['compile']],*asm,*libraries]))
        tests=build/'test-classes';tests.mkdir(parents=True,exist_ok=True)
        test_sources=sorted((ROOT/'src/native-test/java').rglob('*.java'))
        if not test_sources:raise SystemExit('Native tests are missing')
        run([javac,args_file(build/'test-compile.args',['--release','21','-g:none','-encoding','UTF-8','-proc:none','-cp',cp,'-d',tests,*test_sources])])
        for path in (ROOT/'src/native-test/resources').rglob('*'):
            if path.is_file():
                dest=tests/path.relative_to(ROOT/'src/native-test/resources');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,dest)
        test_cp=os.pathsep.join([str(tests),cp])
        proof=build/'native-proof';proof.mkdir(parents=True,exist_ok=True)
        report['nativeMixinProof']=run([java,'-Xmx768M','-cp',test_cp,'dev.lanminecraft.aotlock.test.MixinTransformTest',proof],cwd=proof)
        report['nativeMixinVerified']=True
        if options.mcef_jar:
            report['nativeMcefFactoryProof']=run([java,'-Xmx768M','-cp',test_cp,'dev.lanminecraft.aotlock.test.McefNativeTransformTest',proof],cwd=proof)
            report['nativeMcefFactoryVerified']=True
        report['nativeMetadataProof']=run([java,'-Xmx1G','-cp',test_cp,'dev.lanminecraft.aotlock.test.NativeResourcesTest',ROOT/'src/main/resources',proof/'metadata.json'],cwd=proof)
        report['nativeMetadataVerified']=True
        qa_jar=output/'aot-world-type-lock-native-audit.jar'
        with zipfile.ZipFile(qa_jar,'w',compression=zipfile.ZIP_STORED) as archive:
            # Runtime bridge invokes this using the initialized game's classloader.
            # Service declarations are deliberately absent from the runtime QA jar.
            path=tests/'dev/lanminecraft/aotlock/test/NativeResourcesTest.class'
            archive.writestr('dev/lanminecraft/aotlock/test/NativeResourcesTest.class',path.read_bytes())
        report['nativeRuntimeAuditJar']=str(qa_jar)
        report['nativeClientSha1']=metadata['downloads']['client']['sha1']
        report['nativeMappedSha256']=sha(mapped)
    if snapshot()!=sources:raise SystemExit('Helper sources changed during build; rerun after owner finishes')
    (output/'build-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({key:report[key] for key in ['jar','sha256','nativeVerified','runtimeVerified']}))

if __name__=='__main__':main()
