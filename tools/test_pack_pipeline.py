"""Behavioral proofs for mandatory overlays and lossless author-update staging."""
import copy,io,json,shutil,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import pack_pipeline as p
import pack_library

def jar(mod_id='author',version='1'):
    stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w') as z:z.writestr(zipfile.ZipInfo('fabric.mod.json'),json.dumps({'id':mod_id,'version':version,'name':mod_id,'schemaVersion':1}))
    return stream.getvalue()

class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);self.base=self.root/'base';self.source=self.root/'source';self.base.mkdir();self.source.mkdir()
        self.put(self.base,'mods/author.jar',jar());self.put(self.base,'config/spawn.json',b'{"paradis":true,"other":1}\n');self.put(self.base,'config/other.json',b'{"newAuthorOption":2}\n');self.put(self.base,'options.txt',b'renderDistance:4\n');self.put(self.base,'minecraft-1.21.1-client.jar',b'official-client-fixture')
        self.upstream={'schemaVersion':1,'pack':{'version':'1','minecraftVersion':'1.21.1','loader':{'type':'fabric','version':'0.19.5'}},'files':[p.record(self.base,self.base/'mods/author.jar')],'overrides':[p.record(self.base,self.base/f) for f in ('config/spawn.json','config/other.json','options.txt')],'client':p.record(self.base,self.base/'minecraft-1.21.1-client.jar')}
        self.put(self.source,'tools/overlay/config/spawn.json',b'{"paradis":true,"other":1,"required":true}\n');self.put(self.source,'tools/overlay/mods/own.jar',jar('aot_world_type_lock'))
        self.overlay={'schemaVersion':1,'packId':p.PACK_ID,'platform':{'minecraftVersion':'1.21.1','loaderType':'fabric'},'operations':[
            {'operation':'replace','path':'config/spawn.json','source':'tools/overlay/config/spawn.json',**p.fingerprint(self.source/'tools/overlay/config/spawn.json'),'upstreamSha256':p.digest(self.base/'config/spawn.json'),'reason':'Required authored map start'},
            {'operation':'add','path':'mods/own.jar','source':'tools/overlay/mods/own.jar',**p.fingerprint(self.source/'tools/overlay/mods/own.jar'),'modIds':['aot_world_type_lock'],'reason':'Required new-world restriction'}]}
        p.write_json(self.source/'tools/upstream-lock.json',self.upstream);p.write_json(self.source/'tools/overlay.json',self.overlay)
    def put(self,root,rel,raw):
        target=root/rel;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
    def derived(self,name='derived',upstream=None,overlay=None):
        out=self.root/name;p.derive(self.source,self.base,out,upstream or self.upstream,overlay or self.overlay)
        p.write_json(out/'tools/upstream-lock.json',upstream or self.upstream);p.write_json(out/'tools/overlay.json',overlay or self.overlay)
        for row in (overlay or self.overlay)['operations']:
            target=out/row['source'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(self.source/row['source'],target)
        if (self.source/'library').is_dir():shutil.copytree(self.source/'library',out/'library')
        return out
    def test_canonical_name_renames_library_manifest_menu_and_release_projection_only(self):
        from generate_manifest import generate
        from pack_identity import identity
        content={'id':p.PACK_ID,'name':'First display name','relativePath':'Stable install path','summary':'Summary','description':'Description','libraryIds':['aot_world_type_lock']}
        p.write_json(self.source/'library/content.json',content);p.write_json(self.source/'library/mod-pages.json',{'author':'https://www.curseforge.com/minecraft/mc-mods/author','aot_world_type_lock':'https://github.com/MarkZamore/Awakening-Of-The-Titans'});self.put(self.source,'library/preview.jpg',b'preview-fixture')
        self.put(self.source,'tools/overlay/config/menu.txt',b'title = {{PACK_DISPLAY_NAME}}: Paradis\n')
        self.overlay['operations'].append({'operation':'add','path':'config/menu.txt','source':'tools/overlay/config/menu.txt',**p.fingerprint(self.source/'tools/overlay/config/menu.txt'),'template':'pack-display-name','reason':'Canonical pack label'})
        first=self.derived('first-name');generate(first,self.root/'dist-first','rev')
        content['name']='Renamed display';p.write_json(self.source/'library/content.json',content)
        second=self.derived('second-name');generate(second,self.root/'dist-second','rev')
        a=p.read_json(self.root/'dist-first/pack-manifest.json');b=p.read_json(self.root/'dist-second/pack-manifest.json')
        self.assertEqual(('First display name','Renamed display'),(a['name'],b['name']));self.assertEqual(a['packId'],b['packId']);self.assertEqual(a['relativePath'],b['relativePath']);self.assertEqual('Renamed display',identity(second)['name']);self.assertEqual('Stable install path',identity(second)['relativePath']);self.assertIn(b'Renamed display',(second/'config/menu.txt').read_bytes())
        self.assertEqual(2,b['schemaVersion']);self.assertEqual(['private-identity-asm-v1','registry-player-skins-v1'],b['requiredLauncherFeatures'])
        with zipfile.ZipFile(self.root/'dist-second/assets'/b['library']['name']) as z:self.assertEqual('Renamed display',json.loads(z.read('library.json'))['name'])
        from generate_manifest import verify_staged
        for omitted in ('private-identity-asm-v1','registry-player-skins-v1'):
            bad=copy.deepcopy(b);bad['requiredLauncherFeatures']=[feature for feature in b['requiredLauncherFeatures'] if feature!=omitted];p.write_json(self.root/'dist-second/pack-manifest.json',bad)
            with self.assertRaisesRegex(ValueError,'identity ASM and registry skin'):verify_staged(second,self.root/'dist-second')
    def test_repeated_preparation_is_identical_and_never_ships_personal_options(self):
        a=self.derived('a');b=self.derived('b');self.assertEqual(p.inventory(a),p.inventory(b));self.assertEqual((a/'tools/overlay-receipt.json').read_bytes(),(b/'tools/overlay-receipt.json').read_bytes());self.assertFalse((a/'options.txt').exists());self.assertEqual('PASS',p.verify(a)['status'])
    def test_fancymenu_mod_options_are_preserved_while_personal_defaults_are_excluded(self):
        self.put(self.base,'config/fancymenu/options.txt',b'author-menu-settings');self.upstream['overrides'].append(p.record(self.base,self.base/'config/fancymenu/options.txt'))
        self.put(self.base,'configureddefaults/options.txt',b'renderDistance:4');self.upstream['overrides'].append(p.record(self.base,self.base/'configureddefaults/options.txt'))
        out=self.derived();self.assertEqual(b'author-menu-settings',(out/'config/fancymenu/options.txt').read_bytes());self.assertFalse((out/'configureddefaults/options.txt').exists());self.assertFalse((out/'options.txt').exists())
    def test_controls_preset_first_apply_requires_only_the_yosbr_projection(self):
        self.put(self.base, 'options.txt', b'resourcePacks:["vanilla"]\nrenderDistance:4\n')
        self.upstream['overrides'][-1] = p.record(self.base, self.base/'options.txt')
        self.overlay['initialResourceDefaults'] = 'yosbr-resource-packs-only'
        operations = self.overlay['operations']

        def add(path, raw, source_path=None, **extra):
            source_path = source_path or 'tools/overlay/' + path
            self.put(self.source, source_path, raw)
            operations.append(dict(operation='add', path=path, source=source_path,
                                   reason='Fixture overlay', **p.fingerprint(self.source/source_path), **extra))

        projection = b'resourcePacks:["vanilla"]\n'
        binding = dict(authorOptionsProjection='resource-packs-only',
                       upstreamOptionsSha256=p.digest(self.base/'options.txt'))
        add('mods/yosbr.jar', jar('yosbr'), modIds=['yosbr'])
        add(p.YOSBR_OPTIONS, projection, **binding)
        add(p.PRESET_TARGET, b'key_key.forward:key.keyboard.w\n')
        with self.assertRaisesRegex(ValueError, 'must ship together'):
            self.derived('missing-first-apply')
        add(p.PRESET_FIRST_OPTIONS, projection, 'tools/overlay/' + p.YOSBR_OPTIONS, **binding)
        out = self.derived('paired')
        self.assertEqual('PASS', p.verify(out)['status'])
        self.assertEqual(projection, (out/p.PRESET_FIRST_OPTIONS).read_bytes())
        self.assertFalse((out/'options.txt').exists())
        operations[-1]['source'] = 'tools/overlay/options.txt'
        with self.assertRaisesRegex(ValueError, 'Invalid/duplicate overlay target'):
            self.derived('unreviewed-defaults')
    def test_authored_fancymenu_welcome_and_editor_flags_are_required_without_rewriting(self):
        raw=b"B:show_customization_overlay = 'false';\nB:show_welcome_screen = 'false';\nB:advanced_customization_mode = 'true';\n"
        self.put(self.base,'config/fancymenu/options.txt',raw);self.upstream['overrides'].append(p.record(self.base,self.base/'config/fancymenu/options.txt'))
        self.overlay['requiredUpstream']=[{'path':'config/fancymenu/options.txt','sha256':p.sha(raw),'booleanSettings':{'show_customization_overlay':False,'show_welcome_screen':False}}]
        out=self.derived();self.assertEqual(raw,(out/'config/fancymenu/options.txt').read_bytes());self.assertEqual('PASS',p.verify(out)['status'])
        changed=raw.replace(b"B:show_customization_overlay = 'false'",b"B:show_customization_overlay = 'true'");self.put(self.base,'config/fancymenu/options.txt',changed);self.upstream['overrides'][-1]=p.record(self.base,self.base/'config/fancymenu/options.txt');self.overlay['requiredUpstream'][0]['sha256']=p.sha(changed)
        with self.assertRaisesRegex(ValueError,'FancyMenu setting'):self.derived('blocked-menu')
    def test_author_update_retains_our_helper_and_policy_and_new_author_values(self):
        first=self.derived();self.put(self.base,'config/other.json',b'{"newAuthorOption":27,"authorFix":true}\n');self.put(self.base,'mods/author.jar',jar('author','2'))
        new=copy.deepcopy(self.upstream);new['pack']['version']='2';new['files'][0]=p.record(self.base,self.base/'mods/author.jar');new['overrides'][1]=p.record(self.base,self.base/'config/other.json')
        updated=self.derived('updated',new);self.assertEqual('PASS',p.verify(updated)['status']);self.assertEqual((first/'mods/own.jar').read_bytes(),(updated/'mods/own.jar').read_bytes());self.assertEqual((first/'config/spawn.json').read_bytes(),(updated/'config/spawn.json').read_bytes());self.assertIn(b'27',(updated/'config/other.json').read_bytes());self.assertEqual(jar('author','2'),(updated/'mods/author.jar').read_bytes())
    def test_production_upstream_cannot_drop_mandatory_network_logging_profile(self):
        self.upstream['pack']['projectId']=1535260
        with self.assertRaisesRegex(ValueError,'Mandatory pack-owned network'):self.derived()
        self.assertFalse((self.root/'derived').exists())
    def test_author_update_retains_bounded_native_logging_selector_and_exact_ownership(self):
        import network_logging_policy as policy
        stage=self.root/'logging-source';self.put(stage,'portable-pack.json',p.portable_bytes(self.upstream));self.put(stage,'launcher/pack-owned.txt',b'config/spawn.json\n');policy.prepare(stage,Path(__file__).parent/'logging/log4j2.xml')
        for rel in ('config/log4j2.xml','launcher/jvm-args.txt','launcher/pack-owned.txt'):
            self.put(self.source,'tools/overlay/'+rel,(stage/rel).read_bytes());self.overlay['operations'].append({'operation':'add','path':rel,'source':'tools/overlay/'+rel,**p.fingerprint(self.source/'tools/overlay'/rel),'reason':'Mandatory bounded network diagnostics'})
        self.put(self.source,'tools/network-logging-policy.json',(stage/'tools/network-logging-policy.json').read_bytes());self.overlay['networkLogging']='fabric-1.21.1-caught-throwables';self.overlay['loggingReceipt']={'path':'tools/network-logging-policy.json',**p.fingerprint(self.source/'tools/network-logging-policy.json')}
        first=self.derived('logged-first');self.assertEqual('PASS',p.verify(first)['status']);self.assertIn(b'config/log4j2.xml',(first/'launcher/pack-owned.txt').read_bytes());self.assertIn(policy.SELECTOR.encode(),(first/'launcher/jvm-args.txt').read_bytes())
        self.put(self.base,'config/other.json',b'{"authorUpdate":3}');updated=copy.deepcopy(self.upstream);updated['overrides'][1]=p.record(self.base,self.base/'config/other.json');updated['pack']['version']='2';second=self.derived('logged-second',updated);self.assertEqual('PASS',p.verify(second)['status']);self.assertEqual((first/'config/log4j2.xml').read_bytes(),(second/'config/log4j2.xml').read_bytes())
        (second/'tools/network-logging-policy.json').unlink()
        with self.assertRaisesRegex(ValueError,'mandatory logging receipt'):p.verify(second)
    def test_changed_patch_target_fails_before_output_creation(self):
        new=copy.deepcopy(self.upstream);self.put(self.base,'config/spawn.json',b'{"newAuthorSpawn":true}\n');new['overrides'][0]=p.record(self.base,self.base/'config/spawn.json');out=self.root/'blocked'
        with self.assertRaisesRegex(ValueError,'conflict|Conflicting'):p.derive(self.source,self.base,out,new,self.overlay)
        self.assertFalse(out.exists())
    def test_changed_required_upstream_mechanism_blocks_even_without_replace(self):
        self.overlay['requiredUpstream']=[{'path':'config/other.json','sha256':'0'*64}]
        with self.assertRaisesRegex(ValueError,'required upstream'):self.derived()
    def test_missing_mandatory_payload_or_receipt_fails(self):
        out=self.derived();(out/'tools/overlay-receipt.json').unlink()
        with self.assertRaises(FileNotFoundError):p.verify(out)
        (self.source/'tools/overlay/mods/own.jar').unlink()
        with self.assertRaisesRegex(ValueError,'payload'):self.derived('missing')
    def test_removing_only_helper_operation_cannot_return_to_author_world_selection(self):
        self.overlay['operations']=[op for op in self.overlay['operations'] if op['path']!='mods/own.jar']
        with self.assertRaisesRegex(ValueError,'Mandatory world-creation helper'):self.derived()
        self.assertFalse((self.root/'derived').exists())
    def test_removed_menu_override_cannot_restore_author_settings_writer(self):
        rel='config/fancymenu/customization/spawn_layout.txt';original=b'type = fancymenu_layout\n[action_type:write_file_in_game_dir] = Overworld\n[action_type:quitgame] =\n';self.put(self.base,rel,original);self.upstream['overrides'].append(p.record(self.base,self.base/rel));self.put(self.source,'tools/overlay/'+rel,b'type = fancymenu_layout\n')
        op={'operation':'replace','path':rel,'source':'tools/overlay/'+rel,**p.fingerprint(self.source/'tools/overlay'/rel),'upstreamSha256':p.sha(original),'reason':'Remove author Overworld writer'};self.overlay['operations'].append(op)
        good=self.derived('good-menu');before=p.inventory(good);self.overlay['operations'].remove(op)
        with self.assertRaisesRegex(ValueError,'Spawn-choice menu'):self.derived('blocked-menu')
        self.assertEqual(before,p.inventory(good))
    def test_author_now_supplies_our_mod_id_requires_review_and_no_duplicate_is_installed(self):
        self.put(self.base,'mods/renamed-helper.jar',jar('aot_world_type_lock','9'));self.upstream['files'].append(p.record(self.base,self.base/'mods/renamed-helper.jar'))
        with self.assertRaisesRegex(ValueError,'overlay mod'):self.derived()
        self.assertFalse((self.root/'derived').exists())
    def test_unknown_author_jar_and_unknown_override_are_rejected(self):
        for rel,raw in [('mods/surprise.jar',jar('surprise')),('config/surprise.json',b'{}')]:
            self.put(self.base,rel,raw)
            with self.assertRaisesRegex(ValueError,'Unknown author'):self.derived()
            (self.base/rel).unlink()
    def test_removed_base_jar_and_deleted_lock_row_does_not_bypass_authority(self):
        out=self.derived();(out/'mods/author.jar').unlink();active=p.read_json(out/'tools/distribution-lock.json');active['files']=[r for r in active['files'] if r['path']!='mods/author.jar'];p.write_json(out/'tools/distribution-lock.json',active)
        with self.assertRaisesRegex(ValueError,'complete upstream'):p.verify(out)
    def test_tampered_generated_portable_cannot_be_blessed_by_editing_lock(self):
        out=self.derived();self.put(out,'portable-pack.json',b'{"minecraftVersion":"1.20.1"}');active=p.read_json(out/'tools/distribution-lock.json');active['files']=[p.record(out,out/'portable-pack.json') if r['path']=='portable-pack.json' else r for r in active['files']];p.write_json(out/'tools/distribution-lock.json',active)
        with self.assertRaisesRegex(ValueError,'complete upstream'):p.verify(out)
    def test_platform_change_requires_new_qualified_overlay(self):
        new=copy.deepcopy(self.upstream);new['pack']['minecraftVersion']='1.22'
        with self.assertRaisesRegex(ValueError,'platform'):self.derived(upstream=new)
    def test_verify_is_read_only_and_never_downloads(self):
        out=self.derived();before={f.relative_to(out):(f.read_bytes(),f.stat().st_mtime_ns) for f in out.rglob('*') if f.is_file()}
        with patch.object(p,'download',side_effect=AssertionError('No network')):p.verify(out)
        self.assertEqual(before,{f.relative_to(out):(f.read_bytes(),f.stat().st_mtime_ns) for f in out.rglob('*') if f.is_file()})
    def test_source_restore_restores_missing_but_never_overwrites_changed_content(self):
        cache=self.root/'cache';cache.mkdir()
        def restored(root,lock,cache):shutil.copytree(self.base,root);return root
        with patch.object(p,'restore_upstream',side_effect=restored):p.prepare(self.source,cache)
        wanted=(self.source/'mods/author.jar').read_bytes();(self.source/'mods/author.jar').unlink()
        with patch.object(p,'restore_upstream',side_effect=restored):p.prepare(self.source,cache)
        self.assertEqual(wanted,(self.source/'mods/author.jar').read_bytes());self.put(self.source,'mods/author.jar',b'unknown-personal-jar')
        before={f.relative_to(self.source):f.read_bytes() for f in self.source.rglob('*') if f.is_file()}
        with patch.object(p,'restore_upstream',side_effect=restored),self.assertRaisesRegex(ValueError,'Changed/unknown'):p.prepare(self.source,cache)
        self.assertEqual(before,{f.relative_to(self.source):f.read_bytes() for f in self.source.rglob('*') if f.is_file()})
    def test_strict_ci_restore_recovers_missing_files_without_changing_committed_pins(self):
        cache=self.root/'cache';cache.mkdir()
        def restored(root,lock,cache):shutil.copytree(self.base,root);return root
        with patch.object(p,'restore_upstream',side_effect=restored):p.prepare(self.source,cache)
        pins=(self.source/'tools/distribution-lock.json').read_bytes();receipt=(self.source/'tools/overlay-receipt.json').read_bytes();(self.source/'mods/author.jar').unlink()
        with patch.object(p,'restore_upstream',side_effect=restored):p.prepare(self.source,cache,refresh_pins=False)
        self.assertEqual(pins,(self.source/'tools/distribution-lock.json').read_bytes());self.assertEqual(receipt,(self.source/'tools/overlay-receipt.json').read_bytes());self.assertTrue((self.source/'mods/author.jar').is_file())
    def test_strict_ci_restore_rejects_changed_docs_before_network_or_writes(self):
        cache=self.root/'cache';cache.mkdir();self.put(self.source,'README.md',b'original docs\n')
        def restored(root,lock,cache):shutil.copytree(self.base,root);return root
        with patch.object(p,'restore_upstream',side_effect=restored):p.prepare(self.source,cache)
        self.put(self.source,'README.md',b'changed docs\r\n');before={f.relative_to(self.source):f.read_bytes() for f in self.source.rglob('*') if f.is_file()}
        with patch.object(p,'restore_upstream',side_effect=AssertionError('Reject before downloads')),self.assertRaisesRegex(ValueError,'Committed distribution/support pins'):p.prepare(self.source,cache,refresh_pins=False)
        self.assertEqual(before,{f.relative_to(self.source):f.read_bytes() for f in self.source.rglob('*') if f.is_file()})
    def test_unsafe_paths_and_nonofficial_downloads_are_rejected(self):
        for rel in ('../mods/x.jar','/absolute','config\\other.json','config//a','config/aux.txt','config/a:stream'):
            with self.subTest(rel=rel),self.assertRaises(ValueError):p.safe(self.source,rel)
        for address in ('http://edge.forgecdn.net/a','https://evil.invalid/a','https://edge.forgecdn.net@evil.invalid/a'):
            with self.subTest(url=address),self.assertRaises(ValueError):p.url(address)
    def test_live_pack_source_is_refused(self):
        (self.source/'.portable-instance.json').write_text('{}');cache=self.root/'cache';cache.mkdir()
        with self.assertRaisesRegex(ValueError,'independent source'):p.prepare(self.source,cache)

class FabricMetadataTests(unittest.TestCase):
    def test_actual_fabric_inventory_excludes_platform_and_unselected_nested_mods(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'mods').mkdir();payload=jar('selected');(root/'mods/selected.jar').write_bytes(payload)
            p.write_json(root/'portable-pack.json',{'minecraftVersion':'1.21.1','loader':{'type':'fabric','version':'0.19.5'}})
            p.write_json(root/'library/loaded-mods.json',{'schemaVersion':1,'minecraftVersion':'1.21.1','loader':{'type':'fabric','version':'0.19.5'},'sourceJars':{'mods/selected.jar':p.sha(payload)},'mods':[{'id':mid,'name':mid,'version':'1'} for mid in ('selected','minecraft','fabricloader','java')]})
            self.assertEqual(['selected'],list(pack_library.read_pack_mods(root)))
    def test_fabric_literal_newline_description_preserves_identity(self):
        stream=io.BytesIO()
        with zipfile.ZipFile(stream,'w') as z:z.writestr('fabric.mod.json','{"schemaVersion":1,"id":"debugify","name":"Debugify","version":"1.21.1+1.0","description":"Line one\nLine two"}')
        rows=pack_library.read_mods(stream.getvalue(),'debugify.jar',loader='fabric');self.assertEqual(('debugify','Debugify','1.21.1+1.0'),(rows[0]['id'],rows[0]['name'],rows[0]['version']))
    def test_fabric_ignores_dormant_malformed_forge_metadata(self):
        stream=io.BytesIO()
        with zipfile.ZipFile(stream,'w') as z:
            z.writestr('fabric.mod.json','{"schemaVersion":1,"id":"chunkactivitytracker","version":"1.0.1"}');z.writestr('META-INF/mods.toml','broken = "');z.writestr('META-INF/neoforge.mods.toml','broken = "')
        self.assertEqual('chunkactivitytracker',pack_library.read_mods(stream.getvalue(),'fixture-fabric.jar',loader='fabric')[0]['id'])
    def test_lock_parser_remains_strict_for_literal_control_characters(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'bad.json';path.write_bytes(b'{"lock":"line\nbreak"}')
            with self.assertRaises(json.JSONDecodeError):p.read_json(path)

if __name__=='__main__':unittest.main()
