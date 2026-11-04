"""Required gameplay IDs survive author filename changes; excluded graves fail closed."""
import io,json,tempfile,unittest,zipfile
from pathlib import Path
import pack_pipeline as p
def jar(mid,nested=None):
    stream=io.BytesIO();meta={'schemaVersion':1,'id':mid,'version':'1'}
    with zipfile.ZipFile(stream,'w') as z:
        if nested:meta['jars']=[{'file':'META-INF/jars/nested.jar'}];z.writestr('META-INF/jars/nested.jar',nested)
        z.writestr('fabric.mod.json',json.dumps(meta))
    return stream.getvalue()
class BaseModPolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);(self.root/'mods').mkdir();(self.root/'mods/original-name.jar').write_bytes(jar('yigd'));self.upstream={'files':[{'path':'mods/original-name.jar','projectId':544912}]};self.overlay={'requiredBaseMods':[{'id':'yigd','projectId':544912}],'forbiddenModIds':['gravestones','pneumonocore']}
    def test_author_filename_rename_preserves_required_id_and_official_project(self):
        (self.root/'mods/original-name.jar').rename(self.root/'mods/author-renamed.jar');self.upstream['files'][0]['path']='mods/author-renamed.jar';p.verify_base_mod_policy(self.root,self.upstream,self.overlay)
    def test_missing_original_mod_fails(self):
        (self.root/'mods/original-name.jar').unlink()
        with self.assertRaisesRegex(ValueError,'missing/duplicated: yigd'):p.verify_base_mod_policy(self.root,self.upstream,self.overlay)
    def test_duplicate_original_id_fails_even_under_a_different_filename(self):
        (self.root/'mods/surprise.jar').write_bytes(jar('yigd'))
        with self.assertRaisesRegex(ValueError,'missing/duplicated: yigd'):p.verify_base_mod_policy(self.root,self.upstream,self.overlay)
    def test_same_native_id_from_another_project_fails(self):
        self.upstream['files'][0]['projectId']=999
        with self.assertRaisesRegex(ValueError,'project provenance differs: yigd'):p.verify_base_mod_policy(self.root,self.upstream,self.overlay)
    def test_user_rejected_gravestones_native_id_fails(self):
        (self.root/'mods/second-grave.jar').write_bytes(jar('gravestones'))
        with self.assertRaisesRegex(ValueError,'second-grave dependency.*gravestones'):p.verify_base_mod_policy(self.root,self.upstream,self.overlay)
    def test_forbidden_pneumonocore_nested_inside_another_mod_fails(self):
        (self.root/'mods/other.jar').write_bytes(jar('other',jar('pneumonocore')))
        with self.assertRaisesRegex(ValueError,'second-grave dependency.*pneumonocore'):p.verify_base_mod_policy(self.root,self.upstream,self.overlay)
    def test_exact_runtime_target_accepts_renamed_original_container(self):
        path=self.root/'mods/author-renamed-globalspawn.jar';path.write_bytes(jar('globalspawn'))
        self.upstream['files'].append({'path':'mods/'+path.name,'projectId':427483})
        self.overlay['exactRuntimeTargets']=[{'id':'globalspawn','projectId':427483,'sha256':p.digest(path)}]
        p.verify_base_mod_policy(self.root,self.upstream,self.overlay)
    def test_exact_runtime_target_rejects_changed_jar_with_the_same_native_id(self):
        path=self.root/'mods/globalspawn.jar';path.write_bytes(jar('globalspawn'))
        self.upstream['files'].append({'path':'mods/'+path.name,'projectId':427483})
        self.overlay['exactRuntimeTargets']=[{'id':'globalspawn','projectId':427483,'sha256':p.digest(path)}]
        with zipfile.ZipFile(path,'a') as archive:archive.writestr('changed-runtime-bytecode-marker',b'new author bytes')
        with self.assertRaisesRegex(ValueError,'Exact runtime target identity/SHA differs: globalspawn'):p.verify_base_mod_policy(self.root,self.upstream,self.overlay)
if __name__=='__main__':unittest.main()
