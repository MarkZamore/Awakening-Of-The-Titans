"""Prepare a private dedicated fixture from the exact pack; never a personal world."""
import argparse,hashlib,io,json,shutil,urllib.request,zipfile
from pathlib import Path
INSTALLER_URL="https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar"
INSTALLER_SHA="61e035bf7bf70153e127440ce34de47c9036f0a2d0c65d1529454bd35ceefe4f"
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def prepare(source,out):
 source=source.resolve();out=out.resolve()
 if "artifacts" not in out.parts or out.exists() or source==out or source in out.parents or out in source.parents:raise ValueError("Use a new independent artifacts fixture")
 if (source/"saves").exists():raise ValueError("No personal saves allowed")
 out.mkdir(parents=True);copied=[];excluded=[]
 for root in ["mods","config","defaultconfigs","data","kubejs","scripts"]:
  base=source/root
  if not base.exists():continue
  for file in sorted(base.rglob("*")):
   if file.is_symlink():raise ValueError("No links")
   if not file.is_file():continue
   rel=file.relative_to(source).as_posix()
   if root=="mods" and file.suffix==".jar":
    with zipfile.ZipFile(file) as jar:
     if "fabric.mod.json" in jar.namelist():
      meta=json.loads(jar.read("fabric.mod.json"),strict=False)
      if meta.get("environment")=="client":
       excluded.append({"path":rel,"id":meta["id"],"reason":"fabric.mod.json environment=client","sha256":sha(file)});continue
   dest=out/rel;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,dest);copied.append({"path":rel,"sha256":sha(file)})
 raw=urllib.request.urlopen(INSTALLER_URL,timeout=60).read()
 if hashlib.sha256(raw).hexdigest()!=INSTALLER_SHA:raise ValueError("Fabric installer SHA256 mismatch")
 (out/"fabric-installer.jar").write_bytes(raw)
 (out/"eula.txt").write_text("eula=true\n")
 (out/"server.properties").write_text("server-ip=127.0.0.1\nserver-port=25578\nonline-mode=false\nlevel-name=AotQaDedicated\nallow-flight=true\nenable-rcon=false\n")
 report={"source":str(source),"minecraft":"1.21.1","fabricLoader":"0.19.5","copied":copied,"excludedClientOnly":excluded,"installerUrl":INSTALLER_URL,"installerSha256":INSTALLER_SHA,"personalWorldsCopied":False}
 (out/"qa-source-snapshot.json").write_text(json.dumps(report,indent=2))
 print(json.dumps({"copied":len(copied),"excludedClientOnly":len(excluded),"fixture":str(out)}))
if __name__=="__main__":
 p=argparse.ArgumentParser();p.add_argument("source",type=Path);p.add_argument("out",type=Path);a=p.parse_args();prepare(a.source,a.out)
