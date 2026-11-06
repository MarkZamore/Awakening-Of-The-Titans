"""Read-only public pack-latest verification before changing the launcher catalog.

Requires a restored, locally verified source and a new artifacts output directory.
Uses public GitHub GET requests only; no credentials or GitHub mutations.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import zipfile

sys.dont_write_bytecode = True
import generate_manifest
import pack_library
from pack_identity import identity
from pack_pipeline import read_json, unique, verify as verify_local

USER_AGENT = "LANMinecraft-public-release-verifier/1"
JSON_LIMIT = 8 * 1024 * 1024
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,239}\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
REVISION = re.compile(r"[0-9a-f]{40}\Z")


class VerificationError(ValueError):
    pass


def check(condition, message):
    if not condition:
        raise VerificationError(message)


def official_url(value):
    parsed = urlsplit(value)
    host = (parsed.hostname or "").lower()
    allowed = host in {"github.com", "api.github.com"} or any(
        host == domain or host.endswith("." + domain)
        for domain in ("githubusercontent.com", "githubusercontent.net"))
    check(parsed.scheme == "https" and allowed and not parsed.username and not parsed.password
          and parsed.port in (None, 443) and not parsed.fragment,
          "Download redirect is outside the official HTTPS GitHub hosts")
    return value


class OfficialRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        official_url(new_url)
        return super().redirect_request(request, response, code, message, headers, new_url)


def open_public(value, api=False):
    official_url(value)
    headers = {"User-Agent": USER_AGENT, "Accept-Encoding": "identity"}
    if api:
        headers.update({"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"})
    try:
        return build_opener(OfficialRedirects()).open(Request(value, headers=headers), timeout=90)
    except HTTPError as failure:
        raise VerificationError("Public GitHub request failed with HTTP " + str(failure.code)) from None
    except (URLError, TimeoutError, OSError):
        raise VerificationError("Public GitHub request failed before a complete response") from None


def public_json(value):
    with open_public(value, api=True) as response:
        raw = response.read(JSON_LIMIT + 1)
    check(len(raw) <= JSON_LIMIT, "Public GitHub JSON exceeds the verification limit")
    return json.loads(raw.decode("utf-8-sig"), object_pairs_hook=unique)


def server_digest(asset):
    value = asset.get("digest")
    if value is None or value == "":
        return None
    check(isinstance(value, str), "Invalid GitHub server digest")
    algorithm, separator, expected = value.lower().partition(":")
    check(separator and algorithm in {"sha256", "sha512"}
          and re.fullmatch(r"[0-9a-f]{%d}" % (64 if algorithm == "sha256" else 128), expected),
          "Unsupported or malformed GitHub server digest")
    return algorithm, expected


def asset_index(rows, owner, repo, tag):
    check(isinstance(rows, list), "GitHub assets response is not a list")
    result, ids = {}, set()
    for row in rows:
        check(isinstance(row, dict), "Malformed GitHub asset entry")
        name = row.get("name")
        check(isinstance(name, str) and NAME.fullmatch(name), "Unsafe GitHub asset name")
        check(name not in result, "Duplicate GitHub asset name: " + name)
        check(type(row.get("id")) is int and row["id"] > 0 and row["id"] not in ids,
              "Invalid or duplicate GitHub asset ID")
        check(row.get("state") == "uploaded", "GitHub asset is not fully uploaded: " + name)
        check(type(row.get("size")) is int and row["size"] >= 0, "Invalid GitHub asset size: " + name)
        expected_url = f"https://github.com/{owner}/{repo}/releases/download/{quote(tag, safe='')}/{quote(name, safe='')}"
        check(row.get("browser_download_url") == expected_url, "Asset URL differs from the canonical release: " + name)
        server_digest(row)
        result[name] = row
        ids.add(row["id"])
    return result


def validate_uploaded_assets(uploaded, expected):
    check(set(uploaded) == set(expected), "Uploaded asset names differ from the authoritative manifest")
    for name, wanted in expected.items():
        actual = uploaded[name]
        check(actual["size"] == wanted["sizeBytes"], "GitHub asset size differs: " + name)
        digest = server_digest(actual)
        if digest and digest[0] == "sha256":
            check(digest[1] == wanted["sha256"], "GitHub server SHA256 differs: " + name)


def public_tag_commit(base, tag):
    reference = public_json(base + "/git/ref/tags/" + quote(tag, safe=""))["object"]
    for _ in range(5):
        if reference.get("type") != "tag":
            break
        check(REVISION.fullmatch(reference.get("sha", "")), "Malformed annotated release tag")
        reference = public_json(base + "/git/tags/" + reference["sha"])["object"]
    check(reference.get("type") == "commit", "Public release tag does not resolve to a commit")
    return reference.get("sha")


def public_asset_rows(base, release_id):
    rows = []
    for page in range(1, 21):
        batch = public_json(base + f"/releases/{release_id}/assets?per_page=100&page={page}")
        check(isinstance(batch, list), "Invalid paged release assets response")
        rows.extend(batch)
        if len(batch) < 100:
            return rows
    raise VerificationError("Public asset pagination exceeds the bounded release inventory")


def download_asset(asset, target, expected=None):
    name = asset["name"]
    size = asset["size"]
    server = server_digest(asset)
    if expected:
        check(size == expected["sizeBytes"], "Asset size differs before download: " + name)
    hashes = {"sha256": hashlib.sha256()}
    if server and server[0] not in hashes:
        hashes[server[0]] = hashlib.new(server[0])
    partial = target.with_name(target.name + ".part")
    count = 0
    try:
        with open_public(asset["browser_download_url"]) as response, partial.open("xb") as output:
            check(response.status == 200, "Expected a complete HTTP200 asset: " + name)
            header = response.headers.get("Content-Length")
            if header is not None:
                check(header.isdecimal() and int(header) == size, "HTTP content length differs: " + name)
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                check(count <= size, "Asset stream is larger than its public metadata: " + name)
                output.write(chunk)
                for digest in hashes.values():
                    digest.update(chunk)
        check(count == size, "Asset stream ended before its declared size: " + name)
        actual = hashes["sha256"].hexdigest()
        if expected:
            check(actual == expected["sha256"], "Downloaded SHA256 differs: " + name)
        if server:
            check(hashes[server[0]].hexdigest() == server[1], "Downloaded bytes differ from GitHub server digest: " + name)
        check(not target.exists(), "Output asset already exists: " + name)
        partial.rename(target)
        return {"name": name, "sizeBytes": count, "sha256": actual, "serverDigestVerified": server is not None}
    except VerificationError:
        raise
    except (HTTPError, URLError, TimeoutError, OSError):
        raise VerificationError("Asset download did not complete: " + name) from None


def manifest_inventory(pack, manifest, revision):
    info = identity(pack)
    runtime = read_json(pack / "portable-pack.json")
    check(manifest.get("revision") == revision, "Published manifest revision differs from the requested source commit")
    check(all(manifest.get(key) == value for key, value in (
        ("packId", info["id"]), ("name", info["name"]), ("relativePath", info["relativePath"]),
        ("schemaVersion", 2), ("requiredLauncherFeatures", generate_manifest.launcher_features(runtime)),
        ("minecraftVersion", runtime["minecraftVersion"]), ("loader", runtime["loader"]),
        ("clientJar", runtime.get("clientJar")),
        ("legacyFiles", generate_manifest.client_fields(pack,runtime).get("legacyFiles")))), "Published manifest identity, platform or launcher contract differs")
    files = read_json(pack / "tools/distribution-lock.json")["files"]
    check(manifest.get("files") == files, "Published file inventory differs from the verified source lock")
    expected_files = {row["path"]: row for row in files}
    check(len(expected_files) == len(files), "Local file inventory has duplicate paths")
    assets, covers = {}, []
    check(isinstance(manifest.get("assets"), list), "Published payload list is absent")
    for row in manifest["assets"]:
        name = row.get("name")
        check(isinstance(name, str) and NAME.fullmatch(name) and name not in assets and name != "pack-manifest.json",
              "Unsafe or duplicate payload asset name")
        check(type(row.get("sizeBytes")) is int and row["sizeBytes"] >= 0
              and isinstance(row.get("sha256"), str) and SHA256.fullmatch(row["sha256"]), "Invalid payload fingerprint")
        paths = row.get("covers")
        check(isinstance(paths, list) and paths and all(path in expected_files for path in paths), "Payload covers unknown files")
        check(row.get("kind") in {"jar", "zipRoot"}, "Unknown payload kind")
        if row["kind"] == "jar":
            check(len(paths) == 1 and all(row[key] == expected_files[paths[0]][key] for key in ("sizeBytes", "sha256")),
                  "Single-file payload differs from the local lock")
        else:
            check(row["sizeBytes"] <= sum(expected_files[path]["sizeBytes"] for path in paths) + 1024 * len(paths) + 65536,
                  "Compressed payload exceeds its bounded local inventory")
        assets[name] = row
        covers.extend(paths)
    check(len(covers) == len(set(covers)) and set(covers) == set(expected_files), "Manifest does not cover every local file exactly once")
    library = manifest.get("library")
    check(isinstance(library, dict) and library.get("revision") == revision and isinstance(library.get("name"), str)
          and NAME.fullmatch(library["name"]) and library["name"] not in assets and library["name"] != "pack-manifest.json"
          and type(library.get("sizeBytes")) is int and 0 < library["sizeBytes"] <= 64 * 1024 * 1024
          and isinstance(library.get("sha256"), str) and SHA256.fullmatch(library["sha256"]), "Invalid library descriptor")
    loaded = sum(bool(mod["isLoadedMod"]) for mod in pack_library.read_pack_mods(pack).values())
    check(manifest.get("modCount") == loaded, "Published mod counter differs from the actual loaded source inventory")
    return assets, library, expected_files, loaded


def verify_library_source(pack, out, descriptor, revision, loaded):
    with zipfile.ZipFile(out / "assets" / descriptor["name"]) as actual, zipfile.ZipFile(io.BytesIO(pack_library.build_library(pack, revision))) as expected:
        check(sorted(actual.namelist()) == ["library.json", "preview.jpg"], "Published library ZIP has missing, duplicate or extra members")
        check(sum(row.file_size for row in actual.infolist()) <= 64 * 1024 * 1024, "Published library uncompressed size exceeds its bound")
        document = json.loads(actual.read("library.json").decode("utf-8-sig"), object_pairs_hook=unique)
        wanted = json.loads(expected.read("library.json").decode("utf-8-sig"), object_pairs_hook=unique)
        check(document == wanted, "Published library metadata/pages/relations differ from canonical source")
        check(actual.read("preview.jpg") == expected.read("preview.jpg"), "Published preview differs from the source")
        mods = document["mods"]
        check(len({mod["id"] for mod in mods}) == len(mods) and sum(bool(mod["isLoadedMod"]) for mod in mods) == loaded,
              "Published library has duplicate mod IDs or the wrong loaded counter")
    return len(mods)


def verify_published(pack, out, revision):
    pack, out = Path(pack).resolve(), Path(out).resolve()
    check(REVISION.fullmatch(revision) is not None, "Use the full lowercase 40-character source commit SHA")
    check("artifacts" in {part.lower() for part in out.parts} and not out.exists() and out != pack,
          "Output must be a new directory under artifacts; existing files are never removed")
    for parent in [out, *out.parents]:
        check(not parent.is_symlink() and not (hasattr(parent, "is_junction") and parent.is_junction()), "Linked output paths are not permitted")
    verify_local(pack)
    canonical = read_json(pack / "tools/catalog-entry.json")
    source = canonical["source"]
    owner, repo, tag = (source[key] for key in ("owner", "repo", "tag"))
    check(all(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,99}", value)
              and value not in {".", ".."} for value in (owner, repo)) and tag == "pack-latest", "Invalid canonical public repository/tag")
    base = f"https://api.github.com/repos/{owner}/{repo}"
    release = public_json(base + "/releases/tags/" + quote(tag, safe=""))
    check(release.get("tag_name") == tag and release.get("name") == identity(pack)["name"]
          and release.get("draft") is False and release.get("prerelease") is False
          and type(release.get("id")) is int and release["id"] > 0, "Public release is not the canonical published rolling release")
    check(public_tag_commit(base, tag) == revision, "Public pack-latest tag points to a different source commit")
    rows = public_asset_rows(base, release["id"])
    uploaded = asset_index(rows, owner, repo, tag)
    check("pack-manifest.json" in uploaded and uploaded["pack-manifest.json"]["size"] <= JSON_LIMIT, "Public authoritative manifest is absent or too large")
    out.mkdir(parents=True, exist_ok=False)
    (out / "assets").mkdir()
    manifest_result = download_asset(uploaded["pack-manifest.json"], out / "pack-manifest.json")
    manifest = read_json(out / "pack-manifest.json")
    assets, library, files, loaded = manifest_inventory(pack, manifest, revision)
    wanted = {**assets, library["name"]: library, "pack-manifest.json": manifest_result}
    validate_uploaded_assets(uploaded, wanted)
    public_metadata = {"repository": owner + "/" + repo, "tag": tag, "revision": revision, "releaseId": release["id"],
                       "assets": [{key: row.get(key) for key in ("name", "id", "size", "digest", "state")} for row in rows]}
    (out / "public-release.json").write_text(json.dumps(public_metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    results = [manifest_result]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        pending = {executor.submit(download_asset, uploaded[name], out / "assets" / name, row): name
                   for name, row in wanted.items() if name != "pack-manifest.json"}
        for future in concurrent.futures.as_completed(pending):
            results.append(future.result())
            if (len(results) - 1) % 10 == 0:
                print(f"Verified {len(results) - 1}/{len(pending)} public payload/library assets", file=sys.stderr, flush=True)
    for row in assets.values():
        if row["kind"] == "zipRoot":
            with zipfile.ZipFile(out / "assets" / row["name"]) as archive:
                check(sorted(archive.namelist()) == sorted(row["covers"]), "Published ZIP member list differs")
                for entry in archive.infolist():
                    check(entry.file_size == files[entry.filename]["sizeBytes"], "Published ZIP member size differs from the local lock")
    generate_manifest.verify_staged(pack, out)
    metadata_rows = verify_library_source(pack, out, library, revision, loaded)
    # pack-latest is mutable: fail closed if publication changes during the run.
    check(public_tag_commit(base, tag) == revision, "Public release tag changed during verification")
    final_uploaded = asset_index(public_asset_rows(base, release["id"]), owner, repo, tag)
    validate_uploaded_assets(final_uploaded, wanted)
    check(all(final_uploaded[name]["id"] == uploaded[name]["id"] for name in wanted), "Public assets were replaced during verification")
    for result in results:
        server = server_digest(final_uploaded[result["name"]])
        if server:
            path = out / "pack-manifest.json" if result["name"] == "pack-manifest.json" else out / "assets" / result["name"]
            if server[0] == "sha256":
                check(result["sha256"] == server[1], "Final public server digest differs: " + result["name"])
            else:
                with path.open("rb") as stream:
                    check(hashlib.file_digest(stream, server[0]).hexdigest() == server[1], "Final public server digest differs: " + result["name"])
            result["serverDigestVerified"] = True
    report = {"status": "PASS", "repository": owner + "/" + repo, "tag": tag, "revision": revision,
              "name": identity(pack)["name"], "activeFiles": len(files), "payloadAssets": len(assets),
              "allUploadedAssetsVerified": len(results), "libraryMetadataRows": metadata_rows, "modCount": loaded,
              "githubServerDigestsVerified": sum(row["serverDigestVerified"] for row in results),
              "githubServerDigestsUnavailable": sum(not row["serverDigestVerified"] for row in results),
              "downloadedBytes": sum(row["sizeBytes"] for row in results),
              "localSourceAndEveryStagedFileVerified": True, "canonicalLibraryPagesRelationsAndPreviewVerified": True,
              "requiredLauncherFeatures": manifest["requiredLauncherFeatures"], "parallelDownloadsMaximum": 4,
              "releaseTagAndAssetIdsUnchangedDuringVerification": True}
    (out / "verification-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    try:
        report = verify_published(args.pack, args.out, args.revision)
    except Exception as failure:
        reason = str(failure).replace(str(args.pack.resolve()), "<pack>").replace(str(args.out.resolve()), "<artifacts>")
        reason = re.sub(r"[A-Za-z]:[\\/][^\s]+", "<local-path>", reason)
        print(json.dumps({"status": "FAIL", "reason": reason or type(failure).__name__}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
