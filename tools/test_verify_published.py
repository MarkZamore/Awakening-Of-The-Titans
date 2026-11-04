import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import verify_published as published


class Response(io.BytesIO):
    status = 200

    def __init__(self, raw):
        super().__init__(raw)
        self.headers = {"Content-Length": str(len(raw))}


class PublicReleaseBoundaryTests(unittest.TestCase):
    def asset(self, raw=b"verified payload"):
        digest = hashlib.sha256(raw).hexdigest()
        return {"name": "payload.jar", "id": 1, "size": len(raw), "state": "uploaded",
                "digest": "sha256:" + digest,
                "browser_download_url": "https://github.com/Owner/Pack/releases/download/pack-latest/payload.jar"}

    def test_missing_uploaded_asset_blocks_release(self):
        with self.assertRaisesRegex(published.VerificationError, "asset names"):
            published.validate_uploaded_assets({}, {"payload.jar": {"sizeBytes": 1, "sha256": "1" * 64}})

    def test_server_digest_mismatch_blocks_before_payload_download(self):
        with self.assertRaisesRegex(published.VerificationError, "server SHA256"):
            published.validate_uploaded_assets({"payload.jar": self.asset()},
                {"payload.jar": {"sizeBytes": len(b"verified payload"), "sha256": "0" * 64}})

    def test_streamed_payload_must_match_local_expected_hash(self):
        asset = self.asset(b"wrong upload")
        with tempfile.TemporaryDirectory() as temp, patch.object(published, "open_public", return_value=Response(b"wrong upload")):
            target = Path(temp) / asset["name"]
            with self.assertRaisesRegex(published.VerificationError, "Downloaded SHA256"):
                published.download_asset(asset, target, {"sizeBytes": asset["size"], "sha256": "0" * 64})
            self.assertFalse(target.exists())
            self.assertEqual(b"wrong upload", target.with_name(target.name + ".part").read_bytes())

    def test_complete_stream_checks_both_source_and_public_server_digest(self):
        raw = b"verified payload"
        asset = self.asset(raw)
        with tempfile.TemporaryDirectory() as temp, patch.object(published, "open_public", return_value=Response(raw)):
            target = Path(temp) / asset["name"]
            result = published.download_asset(asset, target,
                {"sizeBytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
            self.assertTrue(result["serverDigestVerified"])
            self.assertEqual(raw, target.read_bytes())

    def test_wrong_source_revision_is_rejected_before_manifest_inventory_use(self):
        with patch.object(published, "identity", return_value={"id": "fixture", "name": "Fixture", "relativePath": "Fixture"}), \
                patch.object(published, "read_json", return_value={"minecraftVersion": "1.21.1", "loader": {}}):
            with self.assertRaisesRegex(published.VerificationError, "revision differs"):
                published.manifest_inventory(Path("unused"), {"revision": "1" * 40}, "2" * 40)

    def test_redirect_cannot_leave_official_https_hosts(self):
        for url in ("http://github.com/Owner/Pack", "https://github.com.evil.invalid/file",
                    "https://example.invalid/file", "https://token@github.com/file"):
            with self.subTest(url=url), self.assertRaises(published.VerificationError):
                published.official_url(url)
        published.official_url("https://release-assets.githubusercontent.com/official-cdn?signature=private")

    def test_existing_artifact_directory_and_its_files_are_not_replaced(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp) / "artifacts" / "previous"
            out.mkdir(parents=True)
            sentinel = out / "keep.txt"
            sentinel.write_text("previous verified run", encoding="utf-8")
            with patch.object(published, "verify_local") as local, self.assertRaisesRegex(published.VerificationError, "new directory"):
                published.verify_published(Path(temp), out, "1" * 40)
            local.assert_not_called()
            self.assertEqual("previous verified run", sentinel.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
