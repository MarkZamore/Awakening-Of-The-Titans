"""Stage verified exact derivative payloads; publish the manifest after all assets."""
import argparse,json,os,re,shutil,zipfile
from pathlib import Path
import pack_library
from pack_identity import identity
from pack_pipeline import read_json,write_json,verify,require,digest,fingerprint,safe
EPOCH=(1980,1,1,0,0,0)
REQUIRED_LAUNCHER_FEATURES=['private-identity-asm-v1','registry-player-skins-v1']

def launcher_features(runtime):
    features = list(REQUIRED_LAUNCHER_FEATURES)
    if not runtime.get('clientJar'):
        features.append('runtime-library-sources-v1')
    return features

def client_fields(pack, runtime):
    if runtime.get('clientJar'):
        return {'clientJar': runtime['clientJar']}
    row = read_json(Path(pack)/'tools/upstream-lock.json')['client']
    return {'legacyFiles': [{key: row[key] for key in ('path', 'sizeBytes', 'sha256')}]}
def generate(pack,out,revision):
    pack=Path(pack).resolve();out=Path(out).resolve();verify(pack);require(out!=pack,'Output cannot replace source');out.mkdir(parents=True,exist_ok=True)
    rows=read_json(pack/'tools/distribution-lock.json')['files'];assets_dir=out/'assets';require(assets_dir.resolve().is_relative_to(out),'Unsafe output assets')
    if assets_dir.exists():shutil.rmtree(assets_dir)
    assets_dir.mkdir();assets=[];groups={}
    def single(row):
        source=safe(pack,row['path']);label=re.sub('[^A-Za-z0-9._-]+','-',source.name);name=f'file-{row["sha256"][:16]}-{label}';require(not (assets_dir/name).exists(),'Asset name collision');shutil.copyfile(source,assets_dir/name)
        assets.append({'name':name,'kind':'jar',**fingerprint(assets_dir/name),'covers':[row['path']]})
    for row in rows:
        if '/' not in row['path'] or row['path'].startswith('mods/'):single(row)
        else:groups.setdefault(row['path'].split('/')[0],[]).append(row)
    for root,members in sorted(groups.items()):
        temp=assets_dir/(root+'.tmp')
        with zipfile.ZipFile(temp,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
            for row in sorted(members,key=lambda r:r['path']):
                entry=zipfile.ZipInfo(row['path'],EPOCH);entry.compress_type=zipfile.ZIP_DEFLATED;entry.create_system=3;entry.external_attr=0
                z.writestr(entry,safe(pack,row['path']).read_bytes())
        meta=fingerprint(temp);name=f'root-{meta["sha256"][:16]}-{root}.zip';temp.rename(assets_dir/name);assets.append({'name':name,'kind':'zipRoot',**meta,'covers':sorted(r['path'] for r in members)})
    info=identity(pack);runtime=read_json(pack/'portable-pack.json');mods=pack_library.read_pack_mods(pack)
    manifest={'schemaVersion':2,'requiredLauncherFeatures':launcher_features(runtime),'packId':info['id'],'name':info['name'],'relativePath':info['relativePath'],'revision':revision,'minecraftVersion':runtime['minecraftVersion'],'loader':runtime['loader'],**client_fields(pack,runtime),'modCount':sum(m['isLoadedMod'] for m in mods.values()),'files':rows,'assets':sorted(assets,key=lambda r:r['name']),'library':pack_library.stage_library(pack,out,revision)}
    write_json(out/'pack-manifest.json',manifest);verify_staged(pack,out);return {'status':'PASS','name':info['name'],'files':len(rows),'assets':len(assets),'modCount':manifest['modCount'],'revision':revision}
def verify_staged(pack,out):
    verify(pack);out=Path(out);manifest=read_json(out/'pack-manifest.json');info=identity(pack);require(manifest['packId']==info['id'] and manifest['name']==info['name'] and manifest['relativePath']==info['relativePath'],'Manifest display/identity differs from canonical source')
    runtime=read_json(Path(pack)/'portable-pack.json')
    require(manifest.get('schemaVersion')==2 and manifest.get('requiredLauncherFeatures')==launcher_features(runtime),'Manifest must require the generic identity ASM and registry skin capabilities and the applicable runtime features')
    require({key:manifest[key] for key in ('clientJar','legacyFiles') if key in manifest}==client_fields(pack,runtime),'Manifest client delivery or retirement differs')
    rows=read_json(Path(pack)/'tools/distribution-lock.json')['files'];require(manifest['files']==rows,'Manifest inventory differs');expected={r['path']:r for r in rows};covered=[]
    for asset in manifest['assets']:
        path=safe(out,'assets/'+asset['name']);require(path.is_file() and fingerprint(path)=={k:asset[k] for k in ('sizeBytes','sha256')},'Staged asset differs')
        covered.extend(asset['covers'])
        if asset['kind']=='zipRoot':
            with zipfile.ZipFile(path) as z:
                require(sorted(z.namelist())==asset['covers'],'Staged zip coverage differs')
                for rel in z.namelist():
                    raw=z.read(rel);require(len(raw)==expected[rel]['sizeBytes'] and __import__('hashlib').sha256(raw).hexdigest()==expected[rel]['sha256'],'Staged zip member differs')
        else:require(len(asset['covers'])==1 and asset['sha256']==expected[asset['covers'][0]]['sha256'],'Staged file coverage differs')
    require(sorted(covered)==sorted(expected) and len(covered)==len(set(covered)),'Every delivery file must be covered exactly once')
    pack_library.verify_library(out,manifest['library']);return {'status':'PASS','assetsVerified':len(manifest['assets'])}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--pack',required=True,type=Path);p.add_argument('--out',required=True,type=Path);p.add_argument('--revision',default=os.environ.get('GITHUB_SHA','local'));p.add_argument('--verify-only',action='store_true');a=p.parse_args();print(json.dumps(verify_staged(a.pack,a.out) if a.verify_only else generate(a.pack,a.out,a.revision),ensure_ascii=False,indent=2))
if __name__=='__main__':main()
