"""Build the opt-in controls preset from a native Fabric Options capture."""

import argparse
import json
from pathlib import Path

from check_preset import CAPTURE, PAYLOAD, platform_and_pins, read_json, render


def capture(options_path):
    keys = {}
    for line in options_path.read_text(encoding="utf-8-sig").splitlines():
        if not line.startswith("key_"):
            continue
        name, separator, value = line.partition(":")
        if not separator or not value or name in keys:
            raise ValueError(f"Malformed or duplicate native Options mapping: {name}")
        keys[name] = value
    if not keys:
        raise ValueError("The native Options capture contains no bindings.")
    platform, pins = platform_and_pins()
    snapshot = {"platform": platform, "sourceSha256": pins, "keys": keys}
    CAPTURE.write_bytes((json.dumps(snapshot, indent=2, ensure_ascii=False) + "\n").encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--capture", type=Path, help="Import Options saved by the exact native client, then rebuild.")
    args = parser.parse_args()
    if args.capture:
        capture(args.capture)
    expected = render(read_json(CAPTURE)["keys"])
    if args.check:
        if PAYLOAD.read_bytes() != expected:
            parser.exit(1, "Preset differs from the reviewed layout.\n")
        return
    PAYLOAD.parent.mkdir(parents=True, exist_ok=True)
    PAYLOAD.write_bytes(expected)


if __name__ == "__main__":
    main()
