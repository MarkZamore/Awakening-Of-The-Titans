"""Verify actual production instance prepare preserves stopped QA MCEF state."""
import argparse,hashlib,json,subprocess
from pathlib import Path
from copy_native_cache import closed
def snapshot(base):
 result={}
 for file in sorted(base.rglob('*')):
  if file.is_symlink():raise ValueError('Linked runtime state is forbidden')
  if file.is_file():
   stat=file.stat();result[file.relative_to(base).as_posix()]={'size':stat.st_size,'mtimeNs':stat.st_mtime_ns,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()}
 return result
def quarantine(root):return sorted(str(p.relative_to(root)) for p in root.rglob('*') if p.is_dir() and 'mcef' in p.name.lower() and 'conflict' in str(p.relative_to(root)).lower())
def main():
 p=argparse.ArgumentParser();p.add_argument('qa_host',type=Path);p.add_argument('fixture',type=Path);p.add_argument('label');a=p.parse_args()
 root,game=closed(a.fixture,a.label);host=a.qa_host.resolve()
 if 'artifacts' not in host.parts or not host.is_file():raise ValueError('Built private QA host required')
 state=game/'launcher-runtime/mcef'
 if not(state/'libraries/windows_amd64/libcef.dll').is_file() or not(state/'browser-cache').is_dir():raise ValueError('Actual initialized native/browser state is absent')
 before=snapshot(state)
 options=game/'options.txt';optionsBefore=options.read_bytes();optionsTime=options.stat().st_mtime_ns;oldQuarantine=quarantine(root)
 subprocess.run(['dotnet',str(host),'prepare-integration',str(root)],check=True)
 after=snapshot(state);newQuarantine=quarantine(root);unchanged=before==after
 optionsUnchanged=options.read_bytes()==optionsBefore and options.stat().st_mtime_ns==optionsTime
 report={'status':'PASS' if unchanged and optionsUnchanged and newQuarantine==oldQuarantine else 'FAIL','productionPrepareInstanceExecuted':True,'nativeAndBrowserOutsideMods':True,'nativeAndBrowserAllBytesAndMtimesUnchanged':unchanged,'personalOptionsBytesAndMtimeUnchanged':optionsUnchanged,'newMcefQuarantineCreated':newQuarantine!=oldQuarantine,'before':before,'after':after,'actualSteamTransportVerified':False,'sourceExternalDownloadOverrideUsed':False}
 (root/'evidence'/a.label/'native-cache-prepare-retention.json').write_text(json.dumps(report,indent=2))
 print(json.dumps({k:v for k,v in report.items() if k not in ['before','after']}))
 if report['status']!='PASS':raise ValueError('Native prepare changed runtime state; see private report')
if __name__=='__main__':main()
