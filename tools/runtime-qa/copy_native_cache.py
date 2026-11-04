"""Copy a closed fixture's actual MCEF cache to a second closed QA fixture."""
import argparse,ctypes,hashlib,json,shutil
from pathlib import Path
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def closed(root,label):
 root=root.resolve()
 if 'artifacts' not in root.parts or not (root/'.aot-runtime-qa.json').is_file():raise ValueError('Marked independent fixture required')
 ev=root/'evidence'/label
 run=json.loads((ev/'run.json').read_text(encoding='utf-8-sig'));exit=json.loads((ev/'exit.json').read_text(encoding='utf-8-sig'))
 if run['ProcessId']!=exit['processId'] or exit['exitCode']!=0:raise ValueError('Matching normal exit0 required')
 kernel=ctypes.WinDLL('kernel32',use_last_error=True);kernel.OpenProcess.restype=ctypes.c_void_p
 handle=kernel.OpenProcess(0x1000,False,exit['processId'])
 if handle:
  code=ctypes.c_ulong();kernel.GetExitCodeProcess(ctypes.c_void_p(handle),ctypes.byref(code));kernel.CloseHandle(ctypes.c_void_p(handle))
  if code.value==259:raise ValueError('Recorded process still alive')
 game=Path(run['GameDirectory']).resolve()
 if root not in game.parents:raise ValueError('Game escaped fixture')
 return root,game
def main():
 p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('source_label');p.add_argument('destination',type=Path);p.add_argument('destination_label');a=p.parse_args()
 srcroot,srcgame=closed(a.source,a.source_label);dstroot,dstgame=closed(a.destination,a.destination_label)
 if srcroot==dstroot:raise ValueError('Independent roots required')
 rel=Path('mods/mcef-libraries/windows_amd64');src=srcgame/rel;dst=dstgame/rel
 if not(src/'libcef.dll').is_file() or dst.exists():raise ValueError('Valid source and absent destination required')
 files={str(f.relative_to(src)).replace('\\','/'):sha(f) for f in src.rglob('*') if f.is_file()}
 for f in src.rglob('*'):
  if f.is_symlink():raise ValueError('No linked native cache')
 shutil.copytree(src,dst)
 after={str(f.relative_to(dst)).replace('\\','/'):sha(f) for f in dst.rglob('*') if f.is_file()}
 if after!=files:raise ValueError('Copied cache mismatch')
 report={'sourceLabel':a.source_label,'destinationLabel':a.destination_label,'cacheOnly':True,'sourceAndDestinationStoppedExit0':True,'sourceNativeFiles':files,'copiedFiles':len(files),'productionPayloadChanged':False,'externalDownloadFixed':False}
 (dstroot/'mcef-native-cache-copy.json').write_text(json.dumps(report,indent=2))
 print(json.dumps({'copiedFiles':len(files),'allSha256Match':True,'externalDownloadFixed':False}))
if __name__=='__main__':main()
