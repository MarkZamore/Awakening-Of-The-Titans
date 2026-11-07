"""Source-only, exact-byte upstream import and mandatory derivative overlay.

The launcher never runs this tool. New author versions are prepared independently;
conflicts fail before touching the currently published source tree.
"""
from __future__ import annotations
import argparse, concurrent.futures, hashlib, json, os, re, shutil, stat, tempfile, time, zipfile
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

PACK_ID = 'attack-on-titan'
ROOTS = ('mods','config','kubejs','scripts','defaultconfigs','data','datapacks','resourcepacks','shaderpacks','configureddefaults','resources','packmenu','launcher')
FORBIDDEN = {'saves','logs','screenshots','crash-reports','options.txt','servers.dat','usercache.json','usernamecache.json','launcher_profiles.json','server.properties','eula.txt'}
HOSTS = {'edge.forgecdn.net','mediafilez.forgecdn.net','media.forgecdn.net','piston-data.mojang.com','piston-meta.mojang.com','launchermeta.mojang.com','launcher.mojang.com'}
UA = 'LANMinecraft-AttackOnTitan-source-preparation/1'
WITHDRAWN = {(1653965,8711093): {'id':8711093,'projectId':1653965,'fileName':'stronger-shifters-1.3.0.jar','fileLength':56780146,'gameVersions':['Fabric','1.21.1'],'sha256':'ef8c6749022a6e6d97a79143da0837ebccf20e4e88349f7f2110fcb5255a1e81'}}

def require(ok, message):
    if not ok: raise ValueError(message)

def sha(raw): return hashlib.sha256(raw).hexdigest()
def digest(path):
    with Path(path).open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()
def encoded(value): return (json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
def write_json(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(encoded(value))
def unique(pairs):
    result={}
    for key,value in pairs:
        require(key not in result,'Duplicate JSON field: '+key);result[key]=value
    return result
def read_json(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'),object_pairs_hook=unique)
def relative(value):
    require(isinstance(value,str) and value and '\\' not in value and ':' not in value and not PurePosixPath(value).is_absolute(),'Unsafe path: '+repr(value))
    require(all(p not in {'','.','..'} and not p.endswith((' ','.')) and not any(ord(c)<32 or c in '<>"|?*' for c in p) and p.split('.')[0].lower() not in {'con','prn','aux','nul',*(f'com{i}' for i in range(1,10)),*(f'lpt{i}' for i in range(1,10))} for p in value.split('/')),'Unsafe path: '+value)
    return value
def safe(root,value):
    root=Path(root).resolve();p=root/relative(value)
    for part in [p,*p.parents]:
        require(not part.is_symlink() and not (hasattr(part,'is_junction') and part.is_junction()),'Linked path: '+value)
        if part==root: break
    require(p.resolve().is_relative_to(root),'Escaping path: '+value)
    return p
def fingerprint(path):
    path=Path(path);return {'sizeBytes':path.stat().st_size,'sha256':digest(path)}
def record(root,path): return {'path':Path(path).relative_to(root).as_posix(),**fingerprint(path)}
def matches(path,row): return Path(path).is_file() and Path(path).stat().st_size==row['sizeBytes'] and digest(path)==row['sha256']
def url(value,api=False):
    parsed=urlsplit(value)
    require(parsed.scheme=='https' and parsed.hostname in (HOSTS|({'www.curseforge.com'} if api else set())) and not parsed.username and not parsed.password and parsed.port in {None,443},'Nonofficial URL: '+value)
    return value
def fetch_json(value):
    url(value,True)
    for attempt in range(5):
        try:
            with urlopen(Request(value,headers={'User-Agent':UA}),timeout=60) as response: return json.load(response)
        except Exception:
            if attempt==4: raise
            time.sleep(attempt+1)
def download(value,target,size=None,hash_value=None):
    url(value);target=Path(target)
    if target.is_file() and (size is None or target.stat().st_size==size) and (hash_value is None or digest(target)==hash_value): return target
    target.parent.mkdir(parents=True,exist_ok=True)
    for attempt in range(5):
        partial=target.with_name(target.name+'.part')
        try:
            with urlopen(Request(value,headers={'User-Agent':UA}),timeout=120) as response,partial.open('wb') as output: shutil.copyfileobj(response,output,1024*1024)
            require(size is None or partial.stat().st_size==size,'Download size mismatch: '+target.name)
            require(hash_value is None or digest(partial)==hash_value,'Download hash mismatch: '+target.name)
            os.replace(partial,target);return target
        except Exception:
            partial.unlink(missing_ok=True)
            if attempt==4: raise
            time.sleep(attempt+1)
def file_url(file_id,name): return f'https://edge.forgecdn.net/files/{file_id//1000}/{file_id%1000:03d}/{quote(name)}'
class Links(HTMLParser):
    def __init__(self): super().__init__();self.rows=[];self.current=None
    def handle_starttag(self,tag,attrs):
        if tag=='a':
            address=dict(attrs).get('href','');parsed=urlsplit(address)
            require(parsed.scheme=='https' and parsed.netloc=='www.curseforge.com' and parsed.path.startswith('/minecraft/'),'Unexpected modlist project URL')
            self.current={'websiteUrl':address,'title':''}
    def handle_data(self,data):
        if self.current is not None:self.current['title']+=data
    def handle_endtag(self,tag):
        if tag=='a' and self.current is not None:self.rows.append(self.current);self.current=None

def inventory(root):
    root=Path(root);rows=[]
    for name in ROOTS:
        for path in (root/name).rglob('*'):
            safe(root,path.relative_to(root).as_posix())
            if path.is_file():rows.append(record(root,path))
    for path in root.glob('minecraft-*-client.jar'):rows.append(record(root,path))
    if (root/'portable-pack.json').is_file():rows.append(record(root,root/'portable-pack.json'))
    return sorted(rows,key=lambda r:r['path'])
def check_records(root,rows,complete=False):
    seen=set()
    for row in rows:
        path=safe(root,row['path']);folded=row['path'].casefold()
        require(folded not in seen,'Duplicate path: '+row['path']);seen.add(folded)
        require(type(row.get('sizeBytes')) is int and row['sizeBytes']>=0 and re.fullmatch('[0-9a-f]{64}',row.get('sha256','')),'Invalid file fingerprint')
        require(matches(path,row),'Missing/changed locked file: '+row['path'])
    if complete: require(inventory(root)==sorted([{k:r[k] for k in ('path','sizeBytes','sha256')} for r in rows],key=lambda r:r['path']),'Unexpected or missing active content')

def import_upstream(source,cache,archive_url,archive_sha,archive_size,project_id,file_id,version,workers=8):
    source=Path(source).resolve();cache=Path(cache).resolve();source.mkdir(parents=True,exist_ok=True)
    require(not any((source/rel).exists() for rel in ('tools/overlay.json','tools/distribution-lock.json','.portable-instance.json','.portable-pack-sync.json','saves')),'Historical author import must use an independent author-only source tree')
    archive=download(archive_url,cache/'archives'/archive_sha,archive_size,archive_sha)
    with zipfile.ZipFile(archive) as z:
        manifest=json.loads(z.read('manifest.json'));links=Links();links.feed(z.read('modlist.html').decode('utf-8-sig'))
        require(manifest.get('manifestType')=='minecraftModpack' and manifest.get('manifestVersion')==1 and manifest.get('version')==version,'Unexpected upstream manifest identity')
        require(len(links.rows)==len(manifest['files']),'Unaligned author manifest/modlist')
        minecraft=manifest['minecraft']['version'];loaders=manifest['minecraft']['modLoaders'];require(len(loaders)==1 and loaders[0]['primary'],'Ambiguous upstream loader')
        kind,loader_version=loaders[0]['id'].split('-',1);require(kind=='fabric','This derivative supports Fabric only')
        overrides=[];seen=set()
        for info in z.infolist():
            if not info.filename.startswith('overrides/') or info.is_dir():continue
            rel=relative(info.filename[len('overrides/'):]);require(rel.casefold() not in seen and not stat.S_ISLNK(info.external_attr>>16),'Duplicate/linked archive entry');seen.add(rel.casefold())
            target=safe(source,rel);raw=z.read(info);target.parent.mkdir(parents=True,exist_ok=True)
            require(not target.exists() or target.read_bytes()==raw,'Refusing changed author override: '+rel);target.write_bytes(raw)
            overrides.append({'path':rel,'sizeBytes':len(raw),'sha256':sha(raw),'archiveEntry':info.filename})
    metadata_dir=cache/'metadata';metadata_dir.mkdir(parents=True,exist_ok=True)
    def one(pair):
        row,page=pair;pid,fid=row['projectID'],row['fileID'];meta_path=metadata_dir/f'{pid}-{fid}.json'
        if not meta_path.is_file():write_json(meta_path,fetch_json(f'https://www.curseforge.com/api/v1/mods/{pid}/files/{fid}'))
        meta=read_json(meta_path)['data'];exception=WITHDRAWN.get((pid,fid))
        if meta is None and exception:meta=dict(exception)
        require(meta and meta['id']==fid and meta['projectId']==pid,'Unexpected/missing CurseForge file identity')
        name=relative(meta['fileName']);require('/' not in name,'Nested author filename')
        category=urlsplit(page['websiteUrl']).path.split('/')[2]
        directory={'mc-mods':'mods','texture-packs':'resourcepacks','shaders':'shaderpacks'}.get(category);require(directory is not None,'Unsupported manifest project category: '+category)
        rel=directory+'/'+name;address=file_url(fid,name);payload=download(address,cache/'files'/str(fid)/name,meta['fileLength'],exception['sha256'] if exception else None)
        # CurseForge's public file metadata does not currently expose hashes.
        # The import pins actual bytes from its official TLS CDN; all replays use SHA256.
        for h in meta.get('hashes',[]):
            algorithm={1:'sha1',2:'md5'}.get(h['algo'])
            if algorithm:
                with payload.open('rb') as f: require(hashlib.file_digest(f,algorithm).hexdigest().lower()==h['value'].lower(),'Official file digest mismatch')
        target=safe(source,rel);target.parent.mkdir(parents=True,exist_ok=True);require(not target.exists() or digest(target)==digest(payload),'Refusing changed author file: '+rel);shutil.copyfile(payload,target)
        result={'path':rel,**fingerprint(payload),'projectId':pid,'fileId':fid,'fileName':name,'required':row.get('required',True),'sourceUrl':address,'websiteUrl':page['websiteUrl'],'title':page['title'],'gameVersions':meta.get('gameVersions',[])}
        if exception:result['metadataException']='Original author-manifest project/file; public metadata withdrawn. Exact official CDN payload SHA256 pinned independently; fabric.mod.json atakanshift version1.0.0 despite upstream filename1.3.0.'
        return result
    files=[];errors=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        pending={pool.submit(one,pair):pair[0] for pair in zip(manifest['files'],links.rows,strict=True)}
        for future in concurrent.futures.as_completed(pending):
            try:files.append(future.result())
            except Exception as error:errors.append(str(pending[future])+': '+str(error))
    require(not errors,'Upstream import incomplete: '+'; '.join(errors))
    versions=fetch_json('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json');version_url=next(r['url'] for r in versions['versions'] if r['id']==minecraft)
    native=fetch_json(version_url)['downloads']['client'];payload=download(native['url'],cache/'clients'/native['sha1'],native['size'])
    with payload.open('rb') as f: require(hashlib.file_digest(f,'sha1').hexdigest()==native['sha1'],'Mojang client SHA1 mismatch')
    name=f'minecraft-{minecraft}-client.jar';shutil.copyfile(payload,source/name)
    client={'path':name,**fingerprint(payload),'sourceUrl':native['url'],'sha1':native['sha1']}
    lock={'schemaVersion':1,'pack':{'projectId':project_id,'fileId':file_id,'version':version,'name':manifest['name'],'author':manifest.get('author'),'minecraftVersion':minecraft,'loader':{'type':kind,'version':loader_version},'archive':{'url':archive_url,'sizeBytes':archive_size,'sha256':archive_sha}},'files':sorted(files,key=lambda r:r['path']),'overrides':sorted(overrides,key=lambda r:r['path']),'client':client}
    write_json(source/'tools/upstream-lock.json',lock);return lock

def verify_archive_membership(archive,lock):
    manifest=json.loads(archive.read('manifest.json'));platform=manifest['minecraft'];loaders=platform['modLoaders'];pack=lock['pack']
    require(manifest['version']==pack['version'] and platform['version']==pack['minecraftVersion'] and len(loaders)==1 and loaders[0]['id']==pack['loader']['type']+'-'+pack['loader']['version'],'Author archive platform/version differs from snapshot')
    expected={(row['projectID'],row['fileID']):row.get('required',True) for row in manifest['files']};actual={(row['projectId'],row['fileId']):row.get('required',True) for row in lock['files']}
    require(len(expected)==len(manifest['files']) and len(actual)==len(lock['files']) and expected==actual,'Upstream lock must contain the complete original manifest; NEW dependencies are overlay-only')
    entries={item.filename for item in archive.infolist() if item.filename.startswith('overrides/') and not item.is_dir()};locked={row['archiveEntry'] for row in lock['overrides']}
    require(len(locked)==len(lock['overrides']) and entries==locked and all(row['archiveEntry']=='overrides/'+row['path'] for row in lock['overrides']),'Upstream lock must contain every exact original override')
def restore_upstream(root,lock,cache):
    root=Path(root);cache=Path(cache);root.mkdir(parents=True,exist_ok=True)
    arc=lock['pack']['archive'];archive=download(arc['url'],cache/'archives'/arc['sha256'],arc['sizeBytes'],arc['sha256'])
    with zipfile.ZipFile(archive) as z:
        verify_archive_membership(z,lock)
        for row in lock['overrides']:
            raw=z.read(row['archiveEntry']);require(len(raw)==row['sizeBytes'] and sha(raw)==row['sha256'],'Changed archive override')
            target=safe(root,row['path']);target.parent.mkdir(parents=True,exist_ok=True);require(not target.exists() or matches(target,row),'Changed existing upstream file');target.write_bytes(raw)
    def one(row):
        existing=cache/'files'/str(row.get('fileId',''))/row.get('fileName','missing') if row.get('fileId') else cache/'clients'/row.get('sha1','missing')
        payload=existing if matches(existing,row) else download(row['sourceUrl'],cache/'locked'/row['sha256'],row['sizeBytes'],row['sha256']);target=safe(root,row['path']);target.parent.mkdir(parents=True,exist_ok=True)
        require(not target.exists() or matches(target,row),'Changed existing upstream binary');shutil.copyfile(payload,target)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(one,[*lock['files'],lock['client']]))
    check_records(root,[*lock['files'],*lock['overrides'],lock['client']]);return root

def shipping(rel):
    # Minecraft's personal options are distinct from FancyMenu's legitimate
    # config/fancymenu/options.txt. Never filter mod configs by basename alone.
    personal={'options.txt','configureddefaults/options.txt','config/defaultoptions/options.txt','servers.dat','usercache.json','usernamecache.json','launcher_profiles.json','server.properties','eula.txt'}
    return (rel.split('/')[0] in ROOTS and rel not in personal
            and not set(PurePosixPath(rel).parts)&{'saves','logs','screenshots','crash-reports'}
            and not Path(rel).name.startswith(('.portable-','portable-pack-source')))

PRESET_TARGET = 'launcher/controls-preset.txt'
PRESET_FIRST_OPTIONS = 'configureddefaults/options.txt'
YOSBR_OPTIONS = 'config/yosbr/options.txt'

def overlay_shipping(op):
    return shipping(op['path']) or (
        op['path'] == PRESET_FIRST_OPTIONS and op.get('operation') == 'add'
        and op.get('source') == 'tools/overlay/' + YOSBR_OPTIONS
        and op.get('authorOptionsProjection') == 'resource-packs-only'
    )
def portable_bytes(upstream, overlay=None):
    descriptor = {'schemaVersion': 1, 'minecraftVersion': upstream['pack']['minecraftVersion'],
                  'loader': upstream['pack']['loader']}
    if not (overlay or {}).get('officialClientOnly'):
        descriptor['clientJar'] = upstream['client']['path']
    return encoded(descriptor)
def pack_name(source):
    name=read_json(Path(source)/'library/content.json')['name']
    require(isinstance(name,str) and name.strip() and len(name)<=120 and not any(ord(c)<32 for c in name),'Invalid canonical pack display name')
    return name
def projected_payload(source,op):
    payload=safe(source,op['source']);require(matches(payload,op),'Overlay payload differs: '+op['path']);raw=payload.read_bytes()
    if op.get('template'):
        require(op['template']=='pack-display-name' and raw.count(b'{{PACK_DISPLAY_NAME}}')==1,'Invalid display-name template')
        raw=raw.replace(b'{{PACK_DISPLAY_NAME}}',pack_name(source).encode('utf-8'))
    return raw
def expected_inventory(upstream,overlay,source=None):
    require(overlay.get('schemaVersion')==1 and overlay.get('packId')==PACK_ID and isinstance(overlay.get('operations'),list) and overlay['operations'],'Missing/empty mandatory overlay')
    require(sum('aot_world_type_lock' in op.get('modIds',[]) for op in overlay['operations'])==1,'Mandatory world-creation helper is missing/duplicated in overlay')
    if upstream['pack'].get('projectId')==1535260:
        require(overlay.get('networkLogging')=='fabric-1.21.1-caught-throwables','Mandatory pack-owned network exception logging policy is missing')
        require(overlay.get('initialResourceDefaults')=='yosbr-resource-packs-only','Mandatory native first-launch author resource defaults are missing')
        require(overlay.get('baseModPolicy')=='preserve-awakening-1.8.1-gameplay' and len(overlay.get('requiredBaseMods',[]))==8 and len(overlay.get('forbiddenModIds',[]))==2,'Mandatory original gameplay/single-grave policy is missing')
        require(overlay.get('officialClientOnly') is True,'Minecraft client must remain outside the public pack payload')
        require(overlay.get('publicServerPolicy')=='no-public-server-shortcuts-v1','Mandatory public-server menu policy is missing')
    require(overlay.get('platform')=={'minecraftVersion':upstream['pack']['minecraftVersion'],'loaderType':upstream['pack']['loader']['type']},'Overlay platform differs from author')
    all_base={r['path']:r for r in [*upstream['files'],*upstream['overrides'],upstream['client']]};seen=set()
    for pin in overlay.get('requiredUpstream',[]):require(pin['path'] in all_base and all_base[pin['path']]['sha256']==pin['sha256'],'Changed required upstream mechanism: '+pin['path'])
    for op in overlay['operations']:
        rel=relative(op['path']);relative(op['source']);require(op['source'].startswith('tools/overlay/'),'Overlay payload must be committed under tools/overlay');require(overlay_shipping(op) and rel.casefold() not in seen,'Invalid/duplicate overlay target');seen.add(rel.casefold())
        require(op.get('operation') in {'add','replace'} and op.get('reason'),'Invalid overlay operation')
        require((op['operation']=='add' and rel not in all_base) or (op['operation']=='replace' and rel in all_base and op.get('upstreamSha256')==all_base[rel]['sha256']),'Conflicting upstream overlay target: '+rel)
        if op.get('authorOptionsProjection'):
            require(op['authorOptionsProjection']=='resource-packs-only' and rel in {YOSBR_OPTIONS,PRESET_FIRST_OPTIONS} and op.get('upstreamOptionsSha256')==all_base.get('options.txt',{}).get('sha256'),'Author resource defaults binding differs')
    require((PRESET_TARGET in seen)==(PRESET_FIRST_OPTIONS in seen),'Controls preset and its first-apply options template must ship together')
    require(PRESET_FIRST_OPTIONS not in seen or overlay.get('initialResourceDefaults')=='yosbr-resource-packs-only','Controls preset first-apply template requires the YOSBR projection')
    expected={r['path']:{k:r[k] for k in ('path','sizeBytes','sha256')} for r in [*upstream['files'],*upstream['overrides']] if shipping(r['path'])}
    if not overlay.get('officialClientOnly'):
        row=upstream['client'];expected[row['path']]={k:row[k] for k in ('path','sizeBytes','sha256')}
    for row in overlay['operations']:
        if row.get('template'):
            require(source is not None,'Display projection requires canonical source');raw=projected_payload(source,row);expected[row['path']]={'path':row['path'],'sizeBytes':len(raw),'sha256':sha(raw)}
        else:expected[row['path']]={k:row[k] for k in ('path','sizeBytes','sha256')}
    raw=portable_bytes(upstream,overlay);expected['portable-pack.json']={'path':'portable-pack.json','sizeBytes':len(raw),'sha256':sha(raw)}
    return sorted(expected.values(),key=lambda r:r['path'])
def receipt(upstream,overlay,source=None):
    return {'schemaVersion':1,'packId':PACK_ID,'upstreamLockSha256':sha(encoded(upstream)),'overlaySha256':sha(encoded(overlay)),'inventorySha256':sha(encoded(expected_inventory(upstream,overlay,source))),'operations':[{'path':r['path'],'sha256':r['sha256'],'sizeBytes':r['sizeBytes']} for r in overlay['operations']]}
def verify_author(root,upstream):
    rows=[*upstream['files'],*upstream['overrides'],upstream['client']];check_records(root,rows)
    actual=[]
    for p in Path(root).rglob('*'):
        rel=p.relative_to(root).as_posix()
        if rel.split('/')[0] in {'tools','.git'}:continue
        safe(root,rel)
        if p.is_file():actual.append(record(root,p))
    require(sorted(actual,key=lambda r:r['path'])==sorted([{k:r[k] for k in ('path','sizeBytes','sha256')} for r in rows],key=lambda r:r['path']),'Unknown author content/membership')
def mod_ids(path):
    result=[]
    with zipfile.ZipFile(path) as z:
        if 'fabric.mod.json' in z.namelist():result.append(json.loads(z.read('fabric.mod.json'),strict=False)['id'])
    return result
def verify_required_settings(root,overlay,effective=True):
    for pin in overlay.get('requiredUpstream',[]):
        settings=pin.get('booleanSettings',{})
        if not settings:continue
        text=safe(root,pin['path']).read_text(encoding='utf-8-sig')
        for key,value in settings.items():
            require(type(value) is bool,'Required setting must be boolean')
            found=re.findall(r"(?m)^B:"+re.escape(key)+r" = '(true|false)';\r?$",text)
            require(found==['true' if value else 'false'],'Required authored FancyMenu setting differs: '+key)
    if not effective:return
    # A removed operation must not accidentally restore author buttons which
    # write Overworld settings and exit. This is preparation/publication policy,
    # never a launch-time rewrite of a player's configuration.
    menus=Path(root)/'config/fancymenu/customization'
    spawn=menus/'spawn_layout.txt'
    if spawn.is_file():
        text=spawn.read_text(encoding='utf-8-sig');require('action_type:write_file_in_game_dir' not in text and 'action_type:quitgame' not in text,'Spawn-choice menu can rewrite the required Paradis start')
    world_type=menus/'world_type_layout.txt'
    if world_type.is_file():require('label = Spawn in OverWorld' not in world_type.read_text(encoding='utf-8-sig'),'Overworld creation choice returned')
    if overlay.get('publicServerPolicy'):
        from server_menu_policy import verify as verify_server_menu
        verify_server_menu(root,overlay)
def verify_logging(root,overlay):
    if overlay.get('networkLogging'):
        require(overlay['networkLogging']=='fabric-1.21.1-caught-throwables','Unreviewed native logging profile')
        row=overlay.get('loggingReceipt',{});require(row.get('path')=='tools/network-logging-policy.json' and matches(safe(root,row['path']),row),'Missing/changed mandatory logging receipt')
        import network_logging_policy
        network_logging_policy.verify(Path(root))
def verify_resource_defaults(root,overlay,baseline=None):
    if not overlay.get('initialResourceDefaults'):return
    import resource_defaults
    require(overlay['initialResourceDefaults']=='yosbr-resource-packs-only','Unreviewed initial options policy')
    require(sum('yosbr' in op.get('modIds',[]) for op in overlay['operations'])==1,'Mandatory native YOSBR initializer is missing')
    defaults=safe(root,YOSBR_OPTIONS).read_bytes();resource_defaults.validate(defaults)
    if any(op['path']==PRESET_FIRST_OPTIONS for op in overlay['operations']):
        require(safe(root,PRESET_FIRST_OPTIONS).read_bytes()==defaults,'Controls preset first-apply template differs from YOSBR')
    if baseline is not None:require(defaults==resource_defaults.project(safe(baseline,'options.txt').read_bytes()),'Initial resource defaults differ from author selection/order')
def verify_base_mod_policy(root,upstream,overlay):
    requirements=overlay.get('requiredBaseMods',[]);forbidden=overlay.get('forbiddenModIds',[]);targets=overlay.get('exactRuntimeTargets',[])
    if not requirements and not forbidden and not targets:return
    import pack_library
    root=Path(root);actual={}
    for jar in sorted((root/'mods').glob('*.jar')):
        relative=jar.relative_to(root).as_posix()
        for mod in pack_library.read_mods(jar.read_bytes(),jar.name,loader='fabric'):
            if mod['isLoadedMod']:actual.setdefault(mod['id'],[]).append(relative)
    require(isinstance(forbidden,list) and all(isinstance(mid,str) for mid in forbidden) and len(set(forbidden))==len(forbidden),'Invalid forbidden mod ID policy')
    require(not set(actual)&set(forbidden),'A user-excluded second-grave dependency is present: '+', '.join(sorted(set(actual)&set(forbidden))))
    ids=[row.get('id') for row in requirements];require(len(set(ids))==len(ids),'Duplicate required BASE policy ID')
    source={row['path']:row for row in upstream['files']}
    for row in requirements:
        mid=row['id'];paths=actual.get(mid,[]);require(len(paths)==1,'Required original mod is missing/duplicated: '+mid)
        require(paths[0] in source and source[paths[0]].get('projectId')==row['projectId'],'Required original mod project provenance differs: '+mid)
    for row in targets:
        mid=row['id'];paths=actual.get(mid,[]);require(len(paths)==1,'Exact runtime target is missing/duplicated: '+mid)
        require(paths[0] in source and source[paths[0]].get('projectId')==row['projectId'] and digest(safe(root,paths[0]))==row['sha256'],'Exact runtime target identity/SHA differs: '+mid)
def ensure_external_overlay(source,overlay,cache):
    missing=[]
    for op in overlay['operations']:
        if not op.get('sourceUrl'):continue
        url(op['sourceUrl']);target=safe(source,op['source'])
        require(not target.exists() or matches(target,op),'Changed existing external overlay payload: '+op['path'])
        if not target.exists():missing.append((op,target))
    for op,target in missing:
        payload=download(op['sourceUrl'],Path(cache)/'locked'/op['sha256'],op['sizeBytes'],op['sha256']);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(payload,target)

def overlay_plan(source,baseline,upstream,overlay):
    expected_inventory(upstream,overlay,source)
    require(overlay.get('schemaVersion')==1 and overlay.get('packId')==PACK_ID,'Invalid mandatory overlay identity')
    require(overlay.get('platform')=={'minecraftVersion':upstream['pack']['minecraftVersion'],'loaderType':upstream['pack']['loader']['type']},'Author changed platform; helper/overlay compatibility must be requalified')
    operations=overlay.get('operations');require(isinstance(operations,list) and operations,'Mandatory overlay is empty')
    base={r['path']:r for r in [*upstream['files'],*upstream['overrides'],upstream['client']]};plan={};seen=set()
    for pin in overlay.get('requiredUpstream',[]):require(pin['path'] in base and base[pin['path']]['sha256']==pin['sha256'],'Changed required upstream mechanism: '+pin['path'])
    # Required author booleans are checked against the baseline; menu policy
    # applies only to the effective derivative after replacements below.
    verify_required_settings(baseline,overlay,effective=False)
    for op in operations:
        rel=relative(op['path']);require(overlay_shipping(op),'Overlay targets nonshipped content: '+rel);require(rel.casefold() not in seen,'Duplicate overlay target');seen.add(rel.casefold())
        require(isinstance(op.get('reason'),str) and op['reason'].strip(),'Overlay reason missing');require(op.get('operation') in {'add','replace'},'Unsupported overlay operation')
        if op['operation']=='add':require(rel not in base,'Addition collides with author resource: '+rel)
        else:require(rel in base and op.get('upstreamSha256')==base[rel]['sha256'],'Upstream conflict; review changed target: '+rel)
        payload=safe(source,op['source']);require(matches(payload,op),'Overlay payload differs: '+rel)
        if rel.startswith('mods/'):
            ids=mod_ids(payload);require(ids==op.get('modIds') and ids,'Overlay JAR identity differs')
            for row in upstream['files']:
                if row['path'].startswith('mods/') and row['path']!=rel:require(not set(ids)&set(mod_ids(safe(baseline,row['path']))),'Author now supplies an overlay mod; review duplicate ID')
        plan[rel]=projected_payload(source,op)
    return plan

def derive(source,baseline,output,upstream,overlay):
    source=Path(source);baseline=Path(baseline);output=Path(output)
    require(not output.exists() or not any(output.iterdir()),'Preparation output must be new/empty')
    verify_author(baseline,upstream)
    plan=overlay_plan(source,baseline,upstream,overlay)
    output.mkdir(parents=True,exist_ok=True)
    for row in [*upstream['files'],*upstream['overrides'],upstream['client']]:
        if not shipping(row['path']) and (row is not upstream['client'] or overlay.get('officialClientOnly')):continue
        target=safe(output,row['path']);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(safe(baseline,row['path']),target)
    for rel,payload in plan.items():
        target=safe(output,rel);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(payload)
    (output/'portable-pack.json').write_bytes(portable_bytes(upstream,overlay))
    verify_required_settings(output,overlay)
    verify_resource_defaults(output,overlay,baseline)
    verify_base_mod_policy(output,upstream,overlay)
    if overlay.get('networkLogging'):
        row=overlay['loggingReceipt'];target=safe(output,row['path']);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(safe(source,row['path']),target)
        verify_logging(output,overlay)
    proof=receipt(upstream,overlay,source)
    active={'schemaVersion':1,'packId':PACK_ID,'upstream':upstream['pack'],'receipt':proof,'files':expected_inventory(upstream,overlay,source)}
    check_records(output,active['files'],complete=True)
    write_json(output/'tools/distribution-lock.json',active);write_json(output/'tools/overlay-receipt.json',proof);return active

def verify(pack):
    pack=Path(pack);upstream=read_json(pack/'tools/upstream-lock.json');overlay=read_json(pack/'tools/overlay.json');active=read_json(pack/'tools/distribution-lock.json')
    expected=receipt(upstream,overlay,pack)
    require(active.get('packId')==PACK_ID and active.get('receipt')==expected and read_json(pack/'tools/overlay-receipt.json')==expected,'Stale/missing mandatory overlay receipt')
    require(active.get('files')==expected_inventory(upstream,overlay,pack),'Distribution lock differs from complete upstream/overlay projection')
    verify_required_settings(pack,overlay)
    verify_logging(pack,overlay)
    verify_resource_defaults(pack,overlay)
    verify_base_mod_policy(pack,upstream,overlay)
    for row in overlay['operations']:require(projected_payload(pack,row)==safe(pack,row['path']).read_bytes(),'Missing/changed mandatory overlay output')
    require(all(shipping(row['path']) or row['path'] in {upstream['client']['path'],'portable-pack.json',PRESET_FIRST_OPTIONS} for row in active['files']),'Private/personal file in distribution')
    check_records(pack,active['files'],complete=True)
    if active.get('supportFiles'):check_records(pack,active['supportFiles'])
    return {'status':'PASS','files':len(active['files']),'upstreamVersion':upstream['pack']['version'],'overlayOperations':len(overlay['operations']),'runtimeAcceptanceClaimed':False}

def authored_support_inventory(source):
    source=Path(source);support=[]
    for rel in ('README.md','AGENTS.md','SAFETY.md','TESTING-STATUS.md'):
        if (source/rel).is_file():support.append(record(source,source/rel))
    for path in (source/'library').rglob('*'):
        if path.is_file():support.append(record(source,path))
    for rel in ('tools/network_logging_policy.py','tools/network-logging-policy.json','tools/resource_defaults.py','tools/base-mod-policy-evidence.json','tools/verify_published.py','tools/server_menu_policy.py','tools/server-menu-policy-proof.json','tools/public_server_policy.py','tools/public-server-policy.json'):
        if (source/rel).is_file():support.append(record(source,source/rel))
    for name in ('tools/logging','tools/first-launch-defaults','tools/modeled-update'):
        for path in (source/name).rglob('*'):
            if path.is_file() and '__pycache__' not in path.parts:support.append(record(source,path))
    return sorted(support,key=lambda r:r['path'])
def expected_active_lock(source,upstream,overlay):
    return {'schemaVersion':1,'packId':PACK_ID,'upstream':upstream['pack'],'receipt':receipt(upstream,overlay,source),'files':expected_inventory(upstream,overlay,source),'supportFiles':authored_support_inventory(source)}
def require_committed_pins(source,upstream,overlay):
    expected=expected_active_lock(source,upstream,overlay)
    require(safe(source,'tools/distribution-lock.json').read_bytes()==encoded(expected),'Committed distribution/support pins differ; review and run source prepare before publication')
    require(safe(source,'tools/overlay-receipt.json').read_bytes()==encoded(expected['receipt']),'Committed overlay receipt differs; run source prepare before publication')
    return expected
def prepare(source,cache,output=None,*,refresh_pins=True):
    source=Path(source).resolve();cache=Path(cache).resolve();upstream=read_json(source/'tools/upstream-lock.json');overlay=read_json(source/'tools/overlay.json')
    require(not any((source/name).exists() for name in ('saves','.portable-instance.json','.portable-pack-sync.json','portable-pack-source.json')),'Use an independent source checkout, never an installed/live pack')
    expected_inventory(upstream,overlay,source)
    require(refresh_pins or output is None,'Strict restore has no separate output; use authored prepare for candidates')
    committed=require_committed_pins(source,upstream,overlay) if not refresh_pins else None
    for op in overlay['operations']:
        if not op.get('sourceUrl'):projected_payload(source,op)
    ensure_external_overlay(source,overlay,cache)
    with tempfile.TemporaryDirectory(prefix='aot-prepare-',dir=cache) as temp:
        baseline=restore_upstream(Path(temp)/'author',upstream,cache)
        candidate=Path(temp)/'derived';active=derive(source,baseline,candidate,upstream,overlay)
        destination=Path(output).resolve() if output else source
        if destination==source:
            active['supportFiles']=authored_support_inventory(source)
            require(committed is None or active==committed,'Committed pins changed during restore; review source instead of refreshing pins in CI')
            write_json(candidate/'tools/distribution-lock.json',active)
        if destination!=source:require(not destination.exists() or not any(destination.iterdir()),'Output must be new/empty')
        else:
            previous=read_json(source/'tools/distribution-lock.json') if (source/'tools/distribution-lock.json').is_file() else None
            if previous:
                known={r['path']:r for r in previous['files']}
                for row in inventory(source):require(row['path'] in known and row==known[row['path']],'Changed/unknown existing source content: '+row['path'])
            else:require(not inventory(source),'Initial source contains unreviewed gameplay files')
        destination.mkdir(parents=True,exist_ok=True)
        # Complete download, overlay and source preflight above precede every install.
        for row in active['files']:
            target=safe(destination,row['path']);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(safe(candidate,row['path']),target)
        if destination==source and previous:
            wanted={r['path'] for r in active['files']}
            for row in previous['files']:
                if row['path'] not in wanted:safe(source,row['path']).unlink()
        for rel in ('tools/distribution-lock.json','tools/overlay-receipt.json'):
            target=safe(destination,rel);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(candidate/rel,target)
        if overlay.get('networkLogging'):
            rel=overlay['loggingReceipt']['path'];target=safe(destination,rel);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(candidate/rel,target)
        if destination!=source:
            if (source/'library').is_dir():shutil.copytree(source/'library',destination/'library',dirs_exist_ok=True)
            for rel in ('tools/upstream-lock.json','tools/overlay.json'):
                target=safe(destination,rel);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source/rel,target)
            for row in overlay['operations']:
                target=safe(destination,row['source']);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(safe(source,row['source']),target)
        return verify(destination)

def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    imp=sub.add_parser('import-upstream');imp.add_argument('--out',type=Path,required=True);imp.add_argument('--cache',type=Path,required=True);imp.add_argument('--url',required=True);imp.add_argument('--sha256',required=True);imp.add_argument('--size',type=int,required=True);imp.add_argument('--project-id',type=int,required=True);imp.add_argument('--file-id',type=int,required=True);imp.add_argument('--version',required=True)
    for name in ('prepare','verify'):
        p=sub.add_parser(name);p.add_argument('--pack',type=Path,default=Path(__file__).resolve().parents[1])
        if name=='prepare':p.add_argument('--cache',type=Path,required=True);p.add_argument('--out',type=Path)
    args=parser.parse_args()
    if args.command=='import-upstream':result=import_upstream(args.out,args.cache,args.url,args.sha256,args.size,args.project_id,args.file_id,args.version);result={'status':'PASS','files':len(result['files']),'overrides':len(result['overrides'])}
    elif args.command=='prepare':args.cache.mkdir(parents=True,exist_ok=True);result=prepare(args.pack,args.cache,args.out)
    else:result=verify(args.pack)
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
