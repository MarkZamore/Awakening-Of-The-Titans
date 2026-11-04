import json
import io
import hashlib
import pathlib
import tempfile
import unittest
import zipfile
from pack_library import build_library, stage_library, verify_library


class LibraryPublisherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.pack = pathlib.Path(self.temp.name) / "Example Pack"
        (self.pack / "mods").mkdir(parents=True)
        (self.pack / "library").mkdir()
        (self.pack / "portable-pack.json").write_text(json.dumps({"minecraftVersion": "1.21.1", "loader": {"type": "fabric", "version": "0.19.5"}}))
        self.content = {"id": "example", "name": "Example", "relativePath": "Stable Folder", "summary": "A short story", "description": "A longer story",
                        "parents": {"addon": "base"}, "libraryIds": ["helper"]}
        self.pages = {mid: "https://www.curseforge.com/minecraft/mc-mods/" + mid
                      for mid in ("base", "addon", "helper", "integration")}
        self.write_pages()
        self.write_content()
        (self.pack / "library/preview.jpg").write_bytes(b"test preview bytes")
        for mod_id, name in (("base", "Base Mod"), ("addon", "An Addon"), ("helper", "A Helper Library"), ("integration", "Independent integration")):
            with zipfile.ZipFile(self.pack / "mods" / (mod_id + ".jar"), "w") as archive:
                archive.writestr("fabric.mod.json", json.dumps({"schemaVersion": 1, "id": mod_id, "name": name,
                                                               "version": "1.2.3", "depends": {"base": "*"}}))

    def tearDown(self):
        self.temp.cleanup()

    def write_pages(self):
        (self.pack / "library/mod-pages.json").write_text(json.dumps(self.pages))

    def write_content(self):
        (self.pack / "library/content.json").write_text(json.dumps(self.content))

    def test_current_card_keeps_its_complete_list_without_addition_filters(self):
        with zipfile.ZipFile(io.BytesIO(build_library(self.pack, "r1"))) as archive:
            data = json.loads(archive.read("library.json"))
        self.assertNotIn("visibleModIds", data)
        self.assertNotIn("modsTitle", data)
        self.assertNotIn("baseModIds", data)
        self.assertEqual(4, len(data["mods"]))

    def test_actual_names_curated_addons_and_libraries_not_the_whole_dependency_graph(self):
        payload = build_library(self.pack, "revision-one")
        target = pathlib.Path(self.temp.name) / "bundle.zip"
        target.write_bytes(payload)
        with zipfile.ZipFile(target) as archive:
            data = json.loads(archive.read("library.json"))
        mods = {m["id"]: m for m in data["mods"]}
        self.assertEqual("An Addon", mods["addon"]["name"])
        self.assertEqual(self.pages["addon"], mods["addon"]["websiteUrl"])
        self.assertEqual(self.pages["base"], mods["base"]["websiteUrl"])
        self.assertEqual(self.pages["helper"], mods["helper"]["websiteUrl"])
        self.assertEqual("base", mods["addon"]["parentId"])
        self.assertIsNone(mods["integration"]["parentId"])
        self.assertTrue(mods["helper"]["isLibrary"])
        self.assertEqual("1.21.1", data["minecraftVersion"])
        self.assertEqual("fabric", data["loader"])
        self.assertEqual("revision-one", data["revision"])
        self.assertEqual("Stable Folder", data["relativePath"])

    def test_bundle_is_deterministic_and_tampering_fails_verification(self):
        self.assertEqual(build_library(self.pack, "r1"), build_library(self.pack, "r1"))
        out = pathlib.Path(self.temp.name) / "out"
        descriptor = stage_library(self.pack, out, "r1")
        verify_library(out, descriptor)
        (out / "assets" / descriptor["name"]).write_bytes(b"tampered")
        with self.assertRaises(AssertionError):
            verify_library(out, descriptor)

    def test_new_mod_requires_an_explicit_official_project_page(self):
        del self.pages["addon"]
        self.write_pages()
        with self.assertRaisesRegex(ValueError, "official project page for mod: addon"):
            build_library(self.pack, "r1")

    def test_only_https_project_pages_are_published(self):
        for value in ("javascript:alert(1)", "file:///C:/mod.jar", "https://user:secret@example.com/mod", ""):
            self.pages["addon"] = value
            self.write_pages()
            with self.subTest(value=value), self.assertRaises(ValueError):
                build_library(self.pack, "r1")
        self.pages["addon"] = "https://modrinth.com/mod/an-addon"
        self.write_pages()
        self.assertTrue(build_library(self.pack, "r1"))

    def test_unavailable_author_page_is_preserved_but_not_clickable(self):
        self.pages["addon"] = {"url": "https://github.com/author/unavailable", "available": False}
        self.write_pages()
        payload = build_library(self.pack, "r1")
        import io
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            mods = json.loads(archive.read("library.json"))["mods"]
        addon = next(mod for mod in mods if mod["id"] == "addon")
        self.assertFalse(addon["websiteAvailable"])
        self.assertEqual(self.pages["addon"]["url"], addon["websiteUrl"])

    def test_cyclic_addon_map_cannot_be_published(self):
        self.content["parents"] = {"addon": "base", "base": "addon"}
        self.write_content()
        with self.assertRaisesRegex(ValueError, "Cyclic"):
            build_library(self.pack, "r1")

    def test_embedded_mods_are_published_once_including_dependencies_of_normal_mods(self):
        self.pages["nested_helper"] = "https://www.curseforge.com/minecraft/mc-mods/nested-helper"
        self.write_pages()
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as archive:
            archive.writestr("fabric.mod.json", json.dumps({"schemaVersion": 1, "id": "nested_helper",
                                                           "name": "Nested Helper", "version": "1.0"}))
        unregistered = io.BytesIO()
        with zipfile.ZipFile(unregistered, "w") as archive:
            archive.writestr("fabric.mod.json", json.dumps({"schemaVersion": 1, "id": "unregistered_helper", "version": "1.0"}))
        for carrier in ("base", "addon"):
            with zipfile.ZipFile(self.pack / "mods" / (carrier + ".jar")) as archive:
                # Match Fabric's registered nested JARs, not another loader's JarJar table.
                metadata_path = "fabric.mod.json"
                metadata = json.loads(archive.read(metadata_path))
                metadata["jars"] = [{"file": "META-INF/jars/helper.jar"}]
                entries = {name: archive.read(name) for name in archive.namelist() if name != metadata_path}
            with zipfile.ZipFile(self.pack / "mods" / (carrier + ".jar"), "w") as archive:
                for name, data in entries.items():
                    archive.writestr(name, data)
                archive.writestr(metadata_path, json.dumps(metadata))
                archive.writestr("META-INF/jars/helper.jar", nested.getvalue())
                archive.writestr("META-INF/jars/not-registered.jar", unregistered.getvalue())
        with zipfile.ZipFile(io.BytesIO(build_library(self.pack, "r1"))) as archive:
            mods = json.loads(archive.read("library.json"))["mods"]
        self.assertEqual(5, len(mods))
        self.assertEqual(1, sum(mod["id"] == "nested_helper" for mod in mods))
        self.assertTrue(all(mod["isLoadedMod"] for mod in mods))

    def test_actual_loader_inventory_resolves_selected_ids_and_versions_and_rejects_stale_jars(self):
        inventory = {"schemaVersion": 1,
                     "minecraftVersion": "1.21.1", "loader": {"type": "fabric", "version": "0.19.5"},
                     "sourceJars": {jar.relative_to(self.pack).as_posix(): hashlib.sha256(jar.read_bytes()).hexdigest()
                                    for jar in (self.pack / "mods").glob("*.jar")},
                     "mods": [{"id": mid, "name": mid.title(), "version": "selected-2.0"}
                              for mid in ("base", "addon", "helper")]}
        (self.pack / "library/loaded-mods.json").write_text(json.dumps(inventory))
        with zipfile.ZipFile(io.BytesIO(build_library(self.pack, "r1"))) as archive:
            mods = json.loads(archive.read("library.json"))["mods"]
        self.assertEqual({"base", "addon", "helper"}, {mod["id"] for mod in mods})
        self.assertEqual({"selected-2.0"}, {mod["version"] for mod in mods})
        with zipfile.ZipFile(self.pack / "mods/base.jar", "a") as archive:
            archive.writestr("changed.txt", "new author version")
        with self.assertRaisesRegex(ValueError, "inventory does not match"):
            build_library(self.pack, "r2")


if __name__ == "__main__":
    unittest.main()
