"""Verify Fabric's exact native input and the real Log4j2.22.1 bounded filter."""
import argparse,hashlib,json,os,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent
def digest(raw,algorithm='sha256'):return hashlib.new(algorithm,raw).hexdigest()
def run(args,cwd=None):
    result=subprocess.run(list(map(str,args)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',errors='replace')
    if result.returncode:raise ValueError(result.stdout[-5000:]+result.stderr[-5000:])
    return result.stdout+result.stderr
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--shared',type=Path,required=True);p.add_argument('--intermediary-jar',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--jdk',type=Path,default=Path(r'C:\Tools\jdk-25.0.2'));a=p.parse_args();shared=a.shared.resolve();output=a.output.resolve()
    if 'artifacts' not in [part.lower() for part in output.parts]:raise ValueError('Use ignored artifacts for native proof output')
    output.mkdir(parents=True,exist_ok=True);meta=json.loads((shared/'versions/1.21.1/1.21.1.json').read_text());game=(shared/'versions/1.21.1/1.21.1.jar').read_bytes()
    if digest(game,'sha1')!=meta['downloads']['client']['sha1']:raise ValueError('Native game input differs')
    logging=meta['logging']['client']['file'];native=(ROOT/'native-client-1.12.xml').read_bytes()
    if digest(native,'sha1')!=logging['sha1'] or len(native)!=logging['size']:raise ValueError('Original native logging config differs')
    libraries=[]
    for row in meta['libraries']:
        artifact=row.get('downloads',{}).get('artifact')
        if not artifact or 'natives-' in artifact['path']:continue
        path=shared/'libraries'/artifact['path']
        if not path.is_file():continue
        if digest(path.read_bytes(),'sha1')!=artifact['sha1']:raise ValueError('Native library differs: '+row['name'])
        libraries.append(path)
    api=next(path for path in libraries if path.name=='log4j-api-2.22.1.jar');core=next(path for path in libraries if path.name=='log4j-core-2.22.1.jar');suffix='.exe' if os.name=='nt' else '';javac=a.jdk/'bin'/('javac'+suffix);java=a.jdk/'bin'/('java'+suffix)
    run([javac,'--release','21','-encoding','UTF-8','-d',output,'-cp',os.pathsep.join(map(str,[api,core])),ROOT/'ConnectionLogProbe.java'])
    cp=os.pathsep.join(map(str,[output,a.intermediary_jar.resolve(),*libraries]));text=run([java,'-Dlog4j.configurationFile='+str(ROOT/'log4j2.xml'),'-cp',cp,'ConnectionLogProbe'],output)
    if 'NATIVE_CONNECTION_LOGGER=net.minecraft.class_2535' not in text:raise ValueError('Native logger identity was not resolved')
    if any(marker in text for marker in ('ERROR StatusConsoleListener','Unrecognized format specifier','Unable to locate appender')):raise ValueError('Native XML plugin/configuration failure: '+text)
    log=(output/'logs/latest.log').read_text(encoding='utf-8');labels=['Failed to sent packet','Double fault','Skipping packet due to errors','Timeout']
    if not all('synthetic diagnostic '+label in log for label in labels):raise ValueError('A caught Throwable stack was lost')
    if any(marker in log for marker in ('UNWANTED_PACKET_TRACE','UNWANTED_OTHER_DEBUG')):raise ValueError('Unbounded DEBUG output reached file')
    if not all(marker in log for marker in ('EXPECTED_INFO','EXPECTED_WARN','EXPECTED_ERROR','synthetic retained error')):raise ValueError('Normal diagnostics were lost')
    if 'synthetic diagnostic' in text:raise ValueError('Caught DEBUG stacks leaked into native console')
    with zipfile.ZipFile(shared/'versions/1.21.1/1.21.1.jar') as z:official_class=z.read('vt.class')
    with zipfile.ZipFile(a.intermediary_jar) as z:mapped_class=z.read('net/minecraft/class_2535.class')
    report={'schemaVersion':1,'status':'PASS_NATIVE_INPUT_AND_LOG4J_FILTER','minecraftVersion':'1.21.1','loader':'Fabric 0.19.5','logger':'net.minecraft.class_2535','nativeLoggerResolvedFromInitializedClass':True,'officialConnectionClass':'vt','officialClientSha256':digest(game),'officialConnectionClassSha256':digest(official_class),'mappedConnectionClassSha256':digest(mapped_class),'caller':'exceptionCaught(ChannelHandlerContext,Throwable)','debugThrowableLabels':labels,'nativeDefault':{'url':logging['url'],'sizeBytes':len(native),'sha1':digest(native,'sha1'),'sha256':digest(native)},'deliveryTemplateSha256':digest((ROOT/'log4j2.xml').read_bytes()),'log4jVersion':'2.22.1','caughtThrowableStacks':4,'normalInfoWarnErrorPreserved':True,'packetAndOtherDebugRejected':True,'caughtDebugConsoleOutputRejected':True,'fullClientAcceptanceClaimed':False}
    (output/'proof.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
