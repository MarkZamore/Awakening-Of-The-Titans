"""Project only the author's two resource-pack options into native YOSBR defaults."""
import json
from pathlib import Path
KEYS=('resourcePacks','incompatibleResourcePacks')
def project(raw):
    lines={}
    for line in raw.decode('utf-8-sig').splitlines():
        key,separator,value=line.partition(':')
        if key in KEYS:
            if not separator or key in lines:raise ValueError('Duplicate/invalid resource default: '+key)
            parsed=json.loads(value)
            if not isinstance(parsed,list) or any(not isinstance(item,str) for item in parsed):raise ValueError('Resource defaults must be string arrays')
            lines[key]=key+':'+value
    if 'resourcePacks' not in lines:raise ValueError('Author resourcePacks option is missing')
    return ('\n'.join(lines[key] for key in KEYS if key in lines)+'\n').encode('utf-8')
def validate(raw):
    text=raw.decode('utf-8-sig');keys=[line.partition(':')[0] for line in text.splitlines()]
    if not keys or set(keys)-set(KEYS) or len(keys)!=len(set(keys)) or project(raw)!=raw:raise ValueError('Defaults must contain only the two resource-pack options')
def rebind(source,baseline,upstream,overlay,api):
    operations=[op for op in overlay['operations'] if op.get('authorOptionsProjection')=='resource-packs-only']
    if not operations:return
    rows=[r for r in upstream['overrides'] if r['path']=='options.txt']
    if len(rows)!=1:raise ValueError('Author options snapshot is missing/ambiguous')
    raw=api.safe(baseline,'options.txt').read_bytes()
    if api.sha(raw)!=rows[0]['sha256']:raise ValueError('Author options differ from snapshot')
    for op in operations:
        output=api.safe(source,op['source']);output.parent.mkdir(parents=True,exist_ok=True);output.write_bytes(project(raw));op.update(api.fingerprint(output));op['upstreamOptionsSha256']=rows[0]['sha256']
