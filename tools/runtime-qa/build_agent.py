"""Build test-only lifecycle agents reproducibly; never installs them into mods."""
import argparse,os,shutil,subprocess,tempfile,zipfile
from pathlib import Path
EPOCH=(1980,1,1,0,0,0)
def build(jdk,out):
 source=Path(__file__).resolve().parent;out=out.resolve();out.mkdir(parents=True,exist_ok=True)
 javac=Path(jdk)/"bin"/("javac.exe" if os.name=="nt" else "javac")
 if not javac.is_file():raise ValueError("Pass --jdk or JAVA_HOME for a real JDK21+")
 with tempfile.TemporaryDirectory(prefix="aot-qa-",dir=out) as temp:
  classes=Path(temp)/"classes";classes.mkdir()
  names=["AotQaAgent.java","AttachQa.java","ServerStopAgent.java","probes/AotAdditionalProbeAgent.java","probes/AotNativeGuestDisconnectProbe.java","probes/AotNativeSaveAndQuitProbe.java","probes/AotBackupConfirmationProbe.java","probes/AotGpuAtlasProbe.java"]
  subprocess.run([str(javac),"--release","21","-g:none","-d",str(classes),*[str(source/n) for n in names]],check=True)
  for name,manifest in [("aot-qa-agent.jar","Premain-Class: AotQaAgent\nAgent-Class: AotQaAgent\n"),("aot-qa-server-stop.jar","Agent-Class: ServerStopAgent\n"),("aot-additional-probe.jar","Agent-Class: AotAdditionalProbeAgent\n"),("aot-gpu-atlas-probe.jar","")]:
   with zipfile.ZipFile(out/name,"w",compression=zipfile.ZIP_STORED) as z:
    def put(path,data):
     info=zipfile.ZipInfo(path,EPOCH);info.create_system=3;info.external_attr=0o100644<<16;z.writestr(info,data)
    put("META-INF/MANIFEST.MF",("Manifest-Version: 1.0\n"+manifest+"\n").encode())
    for p in sorted(classes.rglob("*.class")):put(p.relative_to(classes).as_posix(),p.read_bytes())
  shutil.copytree(classes,out/"classes",dirs_exist_ok=True)
 print(out/"aot-qa-agent.jar")
if __name__=="__main__":
 a=argparse.ArgumentParser();a.add_argument("--jdk",default=os.environ.get("JAVA_HOME",""));a.add_argument("--output",required=True,type=Path);p=a.parse_args();build(p.jdk,p.output)
