"""Stage a new author release plus our mandatory overlay; never change current source."""
import argparse,json,shutil,tempfile
from pathlib import Path
import pack_pipeline
import resource_defaults
from pack_pipeline import import_upstream,prepare,require,write_json,read_json
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--pack',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--cache',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--url',required=True);p.add_argument('--sha256',required=True);p.add_argument('--size',type=int,required=True);p.add_argument('--project-id',type=int,default=1535260);p.add_argument('--file-id',type=int,required=True);p.add_argument('--version',required=True);a=p.parse_args()
    source=a.pack.resolve();output=a.out.resolve();cache=a.cache.resolve();require(not output.is_relative_to(source),'Candidate must be outside source checkout');require(not output.exists() or not any(output.iterdir()),'Candidate output must be empty');cache.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='aot-author-update-',dir=cache) as temp:
        stage=Path(temp);fresh=stage/'author';lock=import_upstream(fresh,cache,a.url,a.sha256,a.size,a.project_id,a.file_id,a.version)
        authored=stage/'source';authored.mkdir()
        for name in ('tools','library','.github'):
            if (source/name).is_dir():shutil.copytree(source/name,authored/name,ignore=shutil.ignore_patterns('__pycache__','distribution-lock.json','overlay-receipt.json'))
        for name in ('README.md','AGENTS.md','SAFETY.md','TESTING-STATUS.md','.gitignore','.gitattributes','LICENSE'):
            if (source/name).is_file():shutil.copyfile(source/name,authored/name)
        write_json(authored/'tools/upstream-lock.json',lock)
        overlay=read_json(authored/'tools/overlay.json');resource_defaults.rebind(authored,fresh,lock,overlay,pack_pipeline);write_json(authored/'tools/overlay.json',overlay)
        result=prepare(authored,cache)
        # Only a fully valid candidate is exposed. Runtime QA and owner review
        # still precede committing/publishing this independently staged release.
        shutil.copytree(authored,output,dirs_exist_ok=True)
        print(json.dumps({**result,'candidate':str(output),'published':False,'runtimeAcceptanceClaimed':False},indent=2))
if __name__=='__main__':main()
