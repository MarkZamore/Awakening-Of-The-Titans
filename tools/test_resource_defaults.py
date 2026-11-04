"""Only author resource selections are seeded; graphics and existing files remain personal."""
import copy,json,tempfile,unittest,io,zipfile
from pathlib import Path
from unittest.mock import patch
import resource_defaults as r
import pack_pipeline as p
class ResourceDefaultsTests(unittest.TestCase):
    def test_upstream_lock_cannot_omit_original_files_or_promote_our_new_dependency(self):
        manifest={'version':'1','minecraft':{'version':'1.21.1','modLoaders':[{'id':'fabric-0.19.5'}]},'files':[{'projectID':1,'fileID':2,'required':True}]};buffer=io.BytesIO()
        with zipfile.ZipFile(buffer,'w') as z:z.writestr('manifest.json',json.dumps(manifest));z.writestr('overrides/options.txt',b'resourcePacks:["vanilla"]\n')
        lock={'pack':{'version':'1','minecraftVersion':'1.21.1','loader':{'type':'fabric','version':'0.19.5'}},'files':[{'projectId':1,'fileId':2,'required':True}],'overrides':[{'path':'options.txt','archiveEntry':'overrides/options.txt'}]}
        with zipfile.ZipFile(buffer) as z:
            p.verify_archive_membership(z,lock)
            for mutation in ('omit-mod','omit-override','promote-yosbr'):
                changed=copy.deepcopy(lock)
                if mutation=='omit-mod':changed['files']=[]
                elif mutation=='omit-override':changed['overrides']=[]
                else:changed['files'].append({'projectId':374274,'fileId':4581910,'required':True})
                with self.subTest(mutation=mutation),self.assertRaises(ValueError):p.verify_archive_membership(z,changed)
    def test_exact_selected_order_names_and_escapes_survive_without_personal_settings(self):
        raw=b'renderDistance:17\nlang:ru_ru\nresourcePacks:["vanilla","fabric","file/Anime Icons Xaero\\u0027s v7.1.zip","file/daotoc-content"]\nincompatibleResourcePacks:["file/old-format.zip"]\nfov:0.8\n'
        actual=r.project(raw);r.validate(actual);self.assertEqual(raw.split(b'resourcePacks:',1)[1].split(b'\n',1)[0],actual.split(b'\n')[0].split(b':',1)[1]);self.assertNotIn(b'renderDistance',actual);self.assertNotIn(b'lang:',actual);self.assertNotIn(b'fov:',actual);self.assertNotIn(b'Excalibur',actual)
    def test_added_graphics_language_key_or_duplicate_list_is_rejected(self):
        valid=b'resourcePacks:["vanilla"]\n'
        for extra in (b'renderDistance:17\n',b'simulationDistance:10\n',b'fov:0.8\n',b'lang:ru_ru\n',b'key_key.attack:key.mouse.left\n',valid):
            with self.subTest(extra=extra),self.assertRaises(ValueError):r.validate(valid+extra)
    def test_future_author_selection_rebinds_without_copying_new_graphics(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'source';base=root/'author';source.mkdir();base.mkdir();raw=b'resourcePacks:["vanilla","file/new-author.zip"]\nrenderDistance:99\nlang:de_de\n';(base/'options.txt').write_bytes(raw);upstream={'overrides':[p.record(base,base/'options.txt')]};overlay={'operations':[{'authorOptionsProjection':'resource-packs-only','source':'tools/overlay/config/yosbr/options.txt'}]}
            r.rebind(source,base,upstream,overlay,p);payload=(source/'tools/overlay/config/yosbr/options.txt').read_bytes();self.assertEqual(b'resourcePacks:["vanilla","file/new-author.zip"]\n',payload);self.assertEqual(p.sha(raw),overlay['operations'][0]['upstreamOptionsSha256']);self.assertEqual(p.sha(payload),overlay['operations'][0]['sha256'])
    def test_external_technical_dependency_restores_exact_hash_and_never_replaces_unknown_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'source';cache=root/'cache';source.mkdir();cache.mkdir();raw=b'locked-official-jar-fixture';row={'path':'mods/yosbr.jar','source':'tools/overlay/mods/yosbr.jar','sourceUrl':'https://edge.forgecdn.net/files/4581/910/yosbr-0.1.2.jar','sizeBytes':len(raw),'sha256':p.sha(raw)}
            cached=cache/'locked'/p.sha(raw);cached.parent.mkdir();cached.write_bytes(raw)
            with patch.object(p,'urlopen',side_effect=AssertionError('Verified cache must not fetch')):p.ensure_external_overlay(source,{'operations':[row]},cache)
            self.assertEqual(raw,(source/row['source']).read_bytes());(source/row['source']).write_bytes(b'unknown')
            with patch.object(p,'download',side_effect=AssertionError('Reject before download')),self.assertRaisesRegex(ValueError,'Changed existing external'):p.ensure_external_overlay(source,{'operations':[row]},cache)
if __name__=='__main__':unittest.main()
