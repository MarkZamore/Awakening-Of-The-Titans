"""Exercise real author inputs through preparation using explicitly synthetic archives.

No author release is invented: only this process intercepts fixture:// archive URLs.
All manifest binaries retain their official URL, size and exact locked SHA256.
The current checkout and the public release are read-only controls.
"""
from __future__ import annotations

import argparse
import copy
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pack_pipeline as p


def source_control(source):
    """Gameplay plus authoritative lock controls; concurrent QA prose is excluded."""
    rows = p.inventory(source)
    for rel in ("tools/upstream-lock.json", "tools/overlay.json",
                "tools/distribution-lock.json", "tools/overlay-receipt.json"):
        rows.append(p.record(source, source / rel))
    return {"files": len(rows), "sha256": p.sha(p.encoded(sorted(rows, key=lambda r: r["path"])))}


def public_control(source):
    identity = p.read_json(source / "tools/catalog-entry.json")["source"]
    endpoint = f'repos/{identity["owner"]}/{identity["repo"]}/releases/tags/{identity["tag"]}'
    result = subprocess.run(["gh", "api", endpoint], capture_output=True, text=True)
    if result.returncode:
        p.require("HTTP 404" in result.stderr, "Could not observe public release control")
        return {"tag": identity["tag"], "state": "absent"}
    release = json.loads(result.stdout)
    assets = [{key: item.get(key) for key in ("id", "name", "size", "digest")}
              for item in release.get("assets", [])]
    return {"tag": identity["tag"], "state": "present", "id": release["id"],
            "assets": sorted(assets, key=lambda item: item["name"])}


def copy_authored(source, target):
    result = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                            cwd=source, capture_output=True, check=True)
    names = {name.decode("utf-8") for name in result.stdout.split(b"\0") if name}
    for rel in sorted(names):
        if rel == "portable-pack.json" or rel.split("/")[0] in p.ROOTS or rel.startswith("minecraft-"):
            continue
        if rel.split("/")[0] not in {"tools", "library", ".github"} and "/" in rel:
            continue
        path = p.safe(source, rel)
        if not path.is_file():
            continue
        destination = p.safe(target, rel)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)


def fixture_archive(original, output, upstream, label, changed_path):
    lock = copy.deepcopy(upstream)
    version = f'{upstream["pack"]["version"]}-synthetic-{label}'
    changed_entry = "overrides/" + changed_path
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as target:
        p.verify_archive_membership(source, upstream)
        for entry in source.infolist():
            raw = source.read(entry)
            if entry.filename == "manifest.json":
                manifest = json.loads(raw)
                manifest["version"] = version
                raw = p.encoded(manifest)
            elif entry.filename == changed_entry:
                # An extra trailing LF is semantically harmless to JSON/JSON5 and
                # FancyMenu. Even harmless targeted drift must demand review.
                raw += b"\n"
                row = next(row for row in lock["overrides"] if row["path"] == changed_path)
                row.update(sizeBytes=len(raw), sha256=p.sha(raw))
            target.writestr(entry, raw)
    lock["pack"].update(version=version, fileId=None,
                        fixtureOnly="Synthetic author-update exercise; not an official release")
    address = f"fixture://modeled-author-update/{label}.zip"
    lock["pack"]["archive"] = {"url": address, **p.fingerprint(output)}
    return lock, address


def run(source, cache, output, observe_public=True):
    source = source.resolve()
    cache = cache.resolve()
    output = output.resolve()
    p.require(not output.exists() and not output.is_relative_to(source),
              "Report output must be a new directory outside the source checkout")
    upstream = p.read_json(source / "tools/upstream-lock.json")
    overlay = p.read_json(source / "tools/overlay.json")
    archive = upstream["pack"]["archive"]
    original = cache / "archives" / archive["sha256"]
    p.require(p.matches(original, archive), "Original verified author archive must already be cached")
    before = source_control(source)
    public_before = public_control(source) if observe_public else None
    output.mkdir(parents=True)
    cases = []
    plans = (("non-target-config", "config/openloader/options.json", True, None),
             ("changed-menu-target", "config/fancymenu/customization/create_world_screen_layout.txt", False,
              "Conflicting upstream overlay target"),
             ("changed-required-spawn", "config/GlobalSpawn.json5", False,
              "Changed required upstream mechanism"))
    normal_download = p.download
    for label, rel, accepted, expected_error in plans:
        case_dir = output / label
        case_dir.mkdir()
        synthetic = case_dir / "synthetic-author-fixture.zip"
        lock, fixture_url = fixture_archive(original, synthetic, upstream, label, rel)
        candidate = case_dir / "candidate"
        candidate.mkdir()
        copy_authored(source, candidate)
        p.write_json(candidate / "tools/upstream-lock.json", lock)

        def fixture_download(value, target, size=None, hash_value=None):
            if value == fixture_url:
                p.require(synthetic.stat().st_size == size and p.digest(synthetic) == hash_value,
                          "Synthetic archive fingerprint mismatch")
                return synthetic
            return normal_download(value, target, size, hash_value)

        error = None
        result = None
        with patch.object(p, "download", side_effect=fixture_download):
            try:
                result = p.prepare(candidate, cache)
            except ValueError as caught:
                error = str(caught)
        p.require((error is None) == accepted, "Unexpected modeled update outcome: " + label)
        if expected_error:
            p.require(expected_error in error, "Wrong conflict guard: " + label)
            p.require(not p.inventory(candidate), "A conflicted candidate received active gameplay files")
        changed = next(row for row in lock["overrides"] if row["path"] == rel)
        case = {"label": label, "fixtureOrigin": fixture_url,
                "syntheticArchive": p.fingerprint(synthetic), "syntheticVersion": lock["pack"]["version"],
                "officialReleaseClaimed": False, "changedPath": rel,
                "change": "One trailing LF; semantics preserved",
                "changedOverride": {key: changed[key] for key in ("sizeBytes", "sha256")},
                "expected": "accept" if accepted else "block", "status": "PASS"}
        if accepted:
            case["preparation"] = result
            case["overlayOutputs"] = [p.record(candidate, p.safe(candidate, operation["path"]))
                                      for operation in overlay["operations"]]
            p.require(p.matches(p.safe(candidate, rel), changed), "Non-target author change was lost")
            p.require(all(p.safe(candidate, op["path"]).read_bytes() == p.projected_payload(candidate, op)
                          for op in overlay["operations"]), "A mandatory derivative operation was lost")
        else:
            case["blockingGuard"] = error
            case["activeCandidateFiles"] = 0
        p.require(source_control(source) == before, "Current source changed during modeled update")
        cases.append(case)
    public_after = public_control(source) if observe_public else None
    p.require(public_before == public_after, "Public release changed during modeled update")
    report = {"schemaVersion": 1, "status": "PASS", "packId": p.PACK_ID,
              "scope": "Real exact upstream inputs and current mandatory overlay; synthetic update archives only",
              "originalOfficialSnapshot": {"projectId": upstream["pack"]["projectId"],
                  "fileId": upstream["pack"]["fileId"], "version": upstream["pack"]["version"],
                  "archive": archive, "manifestFiles": len(upstream["files"]),
                  "overrides": len(upstream["overrides"])},
              "overlayOperations": len(overlay["operations"]), "cases": cases,
              "currentSourceControl": before, "currentSourceUnchanged": True,
              "publicReleaseControl": public_before, "publicReleaseObserved": observe_public,
              "publicReleaseUnchanged": True if observe_public else None,
              "runtimeAcceptanceClaimed": False, "personalWorldBackupClaimed": False}
    p.write_json(output / "report.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--offline", action="store_true", help="Skip the public release control; no public-immutability claim")
    args = parser.parse_args()
    report = run(args.pack, args.cache, args.out, not args.offline)
    print(json.dumps({"status": report["status"], "cases": len(report["cases"]),
                      "overlayOperations": report["overlayOperations"],
                      "publicReleaseObserved": report["publicReleaseObserved"]}, indent=2))


if __name__ == "__main__":
    main()
