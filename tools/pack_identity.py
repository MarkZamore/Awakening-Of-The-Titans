"""Canonical display name comes only from library/content.json:name."""
import argparse,json
from pathlib import Path
from pack_pipeline import PACK_ID,pack_name,read_json,require
def identity(pack):
    content=read_json(Path(pack)/'library/content.json');require(content['id']==PACK_ID,'Pack identity differs');return {'id':PACK_ID,'name':pack_name(pack),'relativePath':content['relativePath']}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--pack',type=Path,default=Path(__file__).resolve().parents[1]);a=p.parse_args();print(json.dumps(identity(a.pack),ensure_ascii=False))
if __name__=='__main__':main()
