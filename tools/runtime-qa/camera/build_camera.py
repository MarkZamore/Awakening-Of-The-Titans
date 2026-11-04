"""Build plain, deterministic QA probe JARs. Never installs a mod or an agent."""
import argparse, hashlib, os, subprocess, tempfile, zipfile
from pathlib import Path

PINS = {
    "minecraft": "af3d814b0930a9c018c9c839e35859657894d61b51df3a8a0cd588d67350b169",
    "joml": "cac9f22f83a7aa33eebda73c16ff5261e3cb4911b6bafcf4c79ea486099d0c9a",
    "geckolib": "eac4cf55e1cb99b22cb91ea87c774d2a670c1711a80f4aff50e6ad56b6c58e7e",
    "dannys-aot": "eb30a874e618cbf1f356301d672e7b322673babfa46f8212bc0e98f112eb085d",
}
CLASSES = ["AotCameraMeshProbe", "AotCameraScenarioProbeV2", "AotHeadGeometryProbe",
           "AotHeadStaticMeshProbe", "AotResolvedHeadTextureProbe"]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def build(args):
    source = Path(__file__).resolve().parent
    pack = args.pack.resolve()
    out = args.out.resolve()
    if "artifacts" not in [p.lower() for p in out.parts]:
        raise ValueError("QA output must be under an artifacts directory")
    inputs = {"minecraft": args.minecraft.resolve(), "joml": args.joml.resolve(),
              "geckolib": pack / "mods/geckolib-fabric-1.21.1-4.9.2.jar",
              "dannys-aot": pack / "mods/dannys-aot-2.5.2.jar"}
    for key, path in inputs.items():
        if sha(path) != PINS[key]:
            raise ValueError(f"Pinned {key} input differs; re-audit before rebuilding")
    out.mkdir(parents=True, exist_ok=True)
    javac = args.jdk / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    with tempfile.TemporaryDirectory(prefix="camera-qa-", dir=out) as temp:
        classes = Path(temp) / "classes"
        classes.mkdir()
        subprocess.run([str(javac), "--release", "21", "-g:none", "-encoding", "UTF-8",
                        "-classpath", os.pathsep.join(str(inputs[k]) for k in ["minecraft", "geckolib", "joml"]),
                        "-d", str(classes), *[str(source / (c + ".java")) for c in CLASSES]], check=True)
        target = out / "aot-camera-probes.jar"
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as jar:
            for path in sorted(classes.rglob("*.class")):
                info = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                jar.writestr(info, path.read_bytes())
    print(f"{target.name} SHA256 {sha(target)}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--minecraft", type=Path, required=True, help="Pinned intermediary client JAR from helper build")
    parser.add_argument("--joml", type=Path, required=True, help="Official Minecraft JOML 1.10.5 JAR")
    parser.add_argument("--pack", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--out", type=Path, required=True)
    build(parser.parse_args())
