"""Check the controls layout, native registry pins and reviewed overlaps."""

from collections import defaultdict
from hashlib import sha256
import argparse
import json
from pathlib import Path
import re


HERE = Path(__file__).resolve().parent
PACK = HERE.parents[1]
CAPTURE = HERE / "registered-keys.json"
LAYOUT = HERE / "layout.json"
TARGET = "launcher/controls-preset.txt"
SOURCE = "tools/overlay/" + TARGET
PAYLOAD = PACK / SOURCE
UNKNOWN = "key.keyboard.unknown"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def render(registered):
    layout = read_json(LAYOUT)
    if layout["assignments"]:
        raise ValueError("The preset must reproduce the author controls without overrides.")
    keys = registered
    raw_keys = "".join(f"{name}:{value}\n" for name, value in sorted(keys.items())).encode("utf-8")
    if sha256(raw_keys).hexdigest() != layout["authorKeysSha256"]:
        raise ValueError("Native key capture differs from the pinned author controls.")
    upstream = read_json(PACK / "tools/upstream-lock.json")
    options = next(row for row in upstream["overrides"] if row["path"] == "options.txt")
    if options["sha256"] != layout["authorOptionsSha256"]:
        raise ValueError("Author options changed. Review and recapture the author controls.")
    header = "# Awakening controls for Minecraft 1.21.1 / Fabric\n"
    return (header + "".join(f"{name}:{value}\n" for name, value in keys.items())).encode("utf-8")


def collisions(keys):
    groups = defaultdict(list)
    for name, value in keys.items():
        physical_key = value.partition(":")[0]
        if physical_key != UNKNOWN:
            groups[physical_key].append(name)
    return {key: sorted(names) for key, names in groups.items() if len(names) > 1}


def platform_and_pins():
    descriptor = read_json(PACK / "portable-pack.json")
    platform = {key: descriptor[key] for key in ("minecraftVersion", "loader")}
    lock = read_json(PACK / "tools/distribution-lock.json")
    client = read_json(PACK / "tools/upstream-lock.json")["client"]
    pins = {
        row["path"]: row["sha256"]
        for row in lock["files"]
        if row["path"].startswith("mods/")
    }
    pins[client["path"]] = client["sha256"]
    return platform, pins


def check(author_options=None):
    snapshot = read_json(CAPTURE)
    layout = read_json(LAYOUT)
    platform, pins = platform_and_pins()
    if snapshot["platform"] != platform:
        raise ValueError("Platform changed. Recapture the native registry.")
    if pins != snapshot["sourceSha256"]:
        raise ValueError("Client or mod inventory changed. Recapture the native registry.")
    data = render(snapshot["keys"])
    if PAYLOAD.read_bytes() != data or (PACK / TARGET).read_bytes() != data:
        raise ValueError("Authored or staged preset differs from the reviewed layout.")
    actual = dict(line.split(":", 1) for line in data.decode().splitlines()[1:])
    if author_options is not None:
        raw = author_options.read_bytes()
        if sha256(raw).hexdigest() != layout["authorOptionsSha256"]:
            raise ValueError("Provided author options do not match the upstream lock.")
        author_lines = [line for line in raw.decode("utf-8-sig").splitlines() if line.startswith("key_")]
        if len(author_lines) != len(actual) or actual != dict(line.split(":", 1) for line in author_lines):
            raise ValueError("Preset differs from the original author assignments.")
    for name, value in actual.items():
        if not re.fullmatch(r"key_[^:\s]+", name) or not re.fullmatch(
            r"key\.(?:keyboard|mouse)\.[a-z0-9.]+(?::[^\s]+)?", value
        ):
            raise ValueError(f"Invalid options mapping: {name}={value}")
    expected = {key: sorted(row["bindings"]) for key, row in layout["sharedKeys"].items()}
    if collisions(actual) != expected:
        raise ValueError(f"Physical key overlaps differ: {collisions(actual)}")
    if any(not row["context"].strip() for row in layout["sharedKeys"].values()):
        raise ValueError("Each shared key needs a reviewed context.")
    digest = sha256(data).hexdigest()
    lock = read_json(PACK / "tools/distribution-lock.json")
    records = [
        next(row for row in lock["files"] if row["path"] == TARGET),
        next(row for row in read_json(PACK / "tools/overlay.json")["operations"] if row["path"] == TARGET),
    ]
    if any(row["sha256"] != digest or row["sizeBytes"] != len(data) for row in records):
        raise ValueError("Overlay or distribution pin differs from the preset bytes.")
    print(f"PASS: {len(actual)} registered bindings, {len(expected)} reviewed shared keys, SHA256 {digest}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--author-options", type=Path, help="Compare with the exact upstream options file.")
    check(parser.parse_args().author_options)
