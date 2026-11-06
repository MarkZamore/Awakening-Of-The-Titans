"""Check the controls layout, native registry pins and reviewed overlaps."""

from collections import defaultdict
from hashlib import sha256
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
    changes = read_json(LAYOUT)["assignments"]
    missing = changes.keys() - registered.keys()
    if missing:
        raise ValueError(f"Unregistered bindings: {sorted(missing)}")
    keys = registered | changes
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
    pins = {
        row["path"]: row["sha256"]
        for row in lock["files"]
        if row["path"] == descriptor["clientJar"] or row["path"].startswith("mods/")
    }
    return platform, pins


def check():
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
    check()
