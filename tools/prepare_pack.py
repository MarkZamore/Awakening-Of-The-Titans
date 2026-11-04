"""Prepare the exact derivative from its author lock and mandatory overlay."""
import argparse,json
from pathlib import Path
from pack_pipeline import prepare
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--pack',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--cache',type=Path);p.add_argument('--out',type=Path);a=p.parse_args();cache=(a.cache or a.pack/'artifacts/downloads').resolve();cache.mkdir(parents=True,exist_ok=True);print(json.dumps(prepare(a.pack,cache,a.out),indent=2))
if __name__=='__main__':main()
