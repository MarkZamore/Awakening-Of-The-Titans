"""Execute unmodified YOSBR with the real Fabric0.19.5 API and isolated paths."""
import argparse,hashlib,json,os,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parent
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def run(args):
    r=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',errors='replace')
    if r.returncode:raise ValueError(r.stdout+r.stderr)
    return r.stdout+r.stderr
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--shared',type=Path,required=True);p.add_argument('--yosbr',type=Path,required=True);p.add_argument('--defaults',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--jdk',type=Path,default=Path(r'C:\Tools\jdk-25.0.2'));a=p.parse_args();output=a.output.resolve()
    if 'artifacts' not in [part.lower() for part in output.parts]:raise ValueError('Use ignored artifacts')
    if sha(a.yosbr)!='db4c744fd71f5617639cb0fdff72378b08d2852004f4045c62090de1bf53afcb':raise ValueError('Unreviewed YOSBR JAR')
    output.mkdir(parents=True,exist_ok=True);loader=a.shared/'libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar';api=a.shared/'libraries/org/apache/logging/log4j/log4j-api/2.22.1/log4j-api-2.22.1.jar';core=a.shared/'libraries/org/apache/logging/log4j/log4j-core/2.22.1/log4j-core-2.22.1.jar';suffix='.exe' if os.name=='nt' else ''
    run([a.jdk/'bin'/('javac'+suffix),'--release','21','-d',output,ROOT/'YosbrFileCopyProbe.java'])
    asm=sorted((a.shared/'libraries/org/ow2/asm').rglob('*9.10.1.jar'))
    if len(asm)!=5:raise ValueError('Prepare the exact Fabric0.19.5 ASM9.10.1 dependencies')
    cp=os.pathsep.join(map(str,[output,a.yosbr.resolve(),loader.resolve(),api.resolve(),core.resolve(),*(p.resolve() for p in asm)]));result=run([a.jdk/'bin'/('java'+suffix),'-cp',cp,'YosbrFileCopyProbe',output/'fixture',a.defaults.resolve()])
    if 'PASS_ACTUAL_YOSBR' not in result:raise ValueError('Actual JAR copy test did not finish')
    report={'schemaVersion':1,'status':'PASS_ACTUAL_UNMODIFIED_YOSBR_FILE_COPY','yosbrSha256':sha(a.yosbr),'fabricLoaderVersion':'0.19.5','fabricLoaderSha256':sha(loader),'defaultsSha256':sha(a.defaults),'missingFileInitializedExactly':True,'existingFileBytesAndMtimeUnchanged':True,'updatedDefaultsDoNotOverwritePersonalOptions':True,'gameProvider':'Synthetic path-only proxy with real Fabric API','minecraftClientRunClaimed':False};(output/'proof.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
