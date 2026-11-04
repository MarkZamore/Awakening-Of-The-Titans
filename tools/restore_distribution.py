"""Restore missing exact active files, or verify without network/writes."""
import argparse,json
from pathlib import Path
from pack_pipeline import prepare,verify
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--pack',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--cache',type=Path);p.add_argument('--verify-only',action='store_true');a=p.parse_args()
    if a.verify_only:result=verify(a.pack)
    else:
        cache=(a.cache or a.pack/'artifacts/downloads').resolve();cache.mkdir(parents=True,exist_ok=True);result=prepare(a.pack,cache,refresh_pins=False)
    print(json.dumps(result,indent=2))
if __name__=='__main__':main()
