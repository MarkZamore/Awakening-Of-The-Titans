"""Copy only the official1.21.1 runtime actually referenced by a previous private QA launch."""
import argparse,hashlib,json,shutil
from pathlib import Path

def prepare(fixture,label,out):
 fixture=fixture.resolve();out=out.resolve()
 if "artifacts" not in fixture.parts or "artifacts" not in out.parts or out.exists() or out==fixture or fixture in out.parents or out in fixture.parents:raise ValueError("Use disjoint independent artifacts cache paths")
 if not (fixture/".aot-runtime-qa.json").is_file():raise ValueError("Source must be a marked QA fixture")
 launcher=fixture/"Minecraft/Launcher";outlauncher=out/"Minecraft/Launcher";done=set();records=[]
 def put(file):
  file=file.absolute()
  if file in done:return
  if not file.is_relative_to(launcher) or file.is_symlink() or file.is_junction() or not file.is_file():raise ValueError("Unsafe or missing cache file")
  relative=file.relative_to(launcher);target=outlauncher/relative;target.parent.mkdir(parents=True,exist_ok=True)
  shutil.copy2(file,target);before=hashlib.sha256(file.read_bytes()).hexdigest()
  if hashlib.sha256(target.read_bytes()).hexdigest()!=before:raise ValueError("Copied cache checksum mismatch")
  records.append({"path":relative.as_posix(),"sha256":before,"sizeBytes":file.stat().st_size});done.add(file)
 def tree(folder):
  if folder.exists():
   for f in sorted(folder.rglob("*")):
    if f.is_file():put(f)
 runtime=json.loads((fixture/"runtime.json").read_text(encoding="utf-8-sig"))
 tokens=[json.loads(line) for line in (fixture/"evidence"/label/"java-arguments.txt").read_text(encoding="utf-8-sig").splitlines()]
 for flag in ["-cp","-classpath","--class-path"]:
  if flag in tokens:
   for f in tokens[tokens.index(flag)+1].split(";"):put(Path(f))
 tree(Path(runtime["RuntimeRoot"]))
 tree(launcher/"Shared/versions/1.21.1")
 tree(launcher/"Shared/versions"/runtime["ProfileId"])
 java=Path(runtime["JavaPath"]).parent.parent;tree(java)
 asset_id=tokens[tokens.index("--assetIndex")+1]
 assets=launcher/"Shared/assets";index=assets/"indexes"/(asset_id+".json");put(index)
 objects=json.loads(index.read_text())["objects"]
 for h in sorted({r["hash"] for r in objects.values()}):put(assets/"objects"/h[:2]/h)
 tree(assets/"log_configs")
 out.mkdir(parents=True,exist_ok=True)
 (out/"selective-cache-proof.json").write_text(json.dumps({"schemaVersion":1,"minecraft":"1.21.1","profile":runtime["ProfileId"],"personalFilesCopied":False,"files":records},indent=2))
 print(json.dumps({"files":len(records),"sizeBytes":sum(x["sizeBytes"] for x in records),"personalFilesCopied":False}))
if __name__=="__main__":
 p=argparse.ArgumentParser();p.add_argument("fixture",type=Path);p.add_argument("label");p.add_argument("out",type=Path);a=p.parse_args();prepare(a.fixture,a.label,a.out)
