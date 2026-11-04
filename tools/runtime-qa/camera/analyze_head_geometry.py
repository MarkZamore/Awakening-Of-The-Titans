"""Clip native head quads and test every opaque texture cell touched by visible UV polygons.

Uses numerical draw evidence and resource bytes, never screen captures. Requires Pillow.
"""
import argparse, hashlib, json, math, zipfile
from io import BytesIO
from pathlib import Path
from PIL import Image

AUTHOR_SHA = "eb30a874e618cbf1f356301d672e7b322673babfa46f8212bc0e98f112eb085d"

def matrix(flat):
    return [[flat[c * 4 + r] for c in range(4)] for r in range(4)]

def mul(a, b):
    return [[sum(a[r][k] * b[k][c] for k in range(4)) for c in range(4)] for r in range(4)]

def transform(m, v):
    return [sum(m[r][k] * v[k] for k in range(4)) for r in range(4)]

def clip(poly, planes):
    for signed_distance in planes:
        if not poly:
            break
        result, a = [], poly[-1]
        da = signed_distance(a)
        for b in poly:
            db = signed_distance(b)
            if (da >= 0) != (db >= 0):
                t = da / (da - db)
                result.append([a[k] + t * (b[k] - a[k]) for k in range(len(a))])
            if db >= 0:
                result.append(b)
            a, da = b, db
        poly = result
    return poly

def area(poly):
    return abs(sum(poly[i][0] * poly[(i + 1) % len(poly)][1] -
                   poly[(i + 1) % len(poly)][0] * poly[i][1] for i in range(len(poly)))) / 2 if len(poly) >= 3 else 0

FRUSTUM = [lambda v, axis=axis, sign=sign: v[3] + sign * v[axis]
           for axis, sign in [(0, 1), (0, -1), (1, 1), (1, -1), (2, 1), (2, -1)]]

def opaque_intersections(uv, alpha, epsilon=1e-6):
    width, height = alpha.size
    pixels = alpha.load()
    lo_x = max(0, math.floor(min(v[0] for v in uv) * width) - 1)
    hi_x = min(width - 1, math.ceil(max(v[0] for v in uv) * width) + 1)
    lo_y = max(0, math.floor(min(v[1] for v in uv) * height) - 1)
    hi_y = min(height - 1, math.ceil(max(v[1] for v in uv) * height) + 1)
    count = 0
    for y in range(lo_y, hi_y + 1):
        for x in range(lo_x, hi_x + 1):
            if pixels[x, y] < 26:  # native entity-cutout alpha threshold 0.1
                continue
            x0, x1 = x / width - epsilon, (x + 1) / width + epsilon
            y0, y1 = y / height - epsilon, (y + 1) / height + epsilon
            overlap = clip(uv, [lambda v: v[0] - x0, lambda v: x1 - v[0],
                                lambda v: v[1] - y0, lambda v: y1 - v[1]])
            if area(overlap) > 1e-15:
                count += 1
    return count

def analyze(args):
    author_bytes = args.author_jar.read_bytes()
    if hashlib.sha256(author_bytes).hexdigest() != AUTHOR_SHA:
        raise ValueError("Author JAR differs; repeat exact-JAR audit")
    texture_rows = [json.loads(line) for line in args.texture_report.read_text("utf-8").splitlines() if line.strip()]
    resolution = next(r for r in texture_rows if r.get("nativeRenderer") == "daot.AttackTitanRenderer")
    if not resolution["nativeRenderType"].startswith("RenderType[entity_cutout_no_cull:"):
        raise ValueError("Unexpected native renderer; review shader/MVP path")
    resource_file = resolution["resourceFile"]
    if Path(resource_file).name != resource_file:
        raise ValueError("Resource export must be alongside private report")
    native_bytes = (args.texture_report.parent / resource_file).read_bytes()
    actual_hash = hashlib.sha256(native_bytes).hexdigest()
    texture_id = resolution["textureId"]
    namespace, path = texture_id.split(":", 1)
    with zipfile.ZipFile(BytesIO(author_bytes)) as author:
        official_bytes = author.read(f"assets/{namespace}/{path}")
        if actual_hash != resolution["resolvedSha256"] or native_bytes != official_bytes:
            raise ValueError("Actual ResourceManager texture differs from pinned author resource")
        image = Image.open(BytesIO(native_bytes)).convert("RGBA")
        alphas = {"resolvedBase": image.getchannel("A")}
        # The real renderer has AutoGlowingGeoLayer. Conservatively include every
        # opaque pixel of its authored glow mask, including pixels outside base alpha.
        glow = f"assets/{namespace}/{path[:-4]}_glowmask.png"
        if glow in author.namelist():
            alphas["authorGlowMask"] = Image.open(BytesIO(author.read(glow))).convert("RGBA").getchannel("A")
    geometry = [json.loads(line) for line in args.geometry_report.read_text("utf-8").splitlines() if line.strip()]
    mesh = json.loads(args.mesh_report.read_text("utf-8"))
    results = []
    for row in geometry:
        if row.get("event") != "native_head_geometry" or "AttackTitanEntity" not in row["phase"]:
            continue
        combined = mul(matrix(row["projectionMatrix"]), mul(matrix(row["modelViewMatrix"]), matrix(row["nativePoseMatrix"])))
        visible, coverage, intersections = 0, 0.0, {key: 0 for key in alphas}
        for cube in mesh["bones"][row["bone"]]:
            m = mul(combined, matrix(cube["localCubeMatrix"]))
            for quad in cube["quads"]:
                vertices = [transform(m, v[:3] + [1]) + v[3:] for v in quad]
                for indices in [(0, 1, 2), (0, 2, 3)]:
                    poly = clip([vertices[i] for i in indices], FRUSTUM)
                    if len(poly) < 3:
                        continue
                    screen = [[v[0] / v[3], v[1] / v[3]] for v in poly]
                    if area(screen) <= 1e-10:
                        continue
                    visible += 1
                    coverage += area(screen) / 4
                    uv = [[v[4], v[5]] for v in poly]
                    for key, alpha in alphas.items():
                        intersections[key] += opaque_intersections(uv, alpha)
        results.append({"time": row["time"], "phase": row["phase"], "bone": row["bone"],
                        "hidden": row["hidden"], "visibleClippedTriangles": visible,
                        "viewportAreaSumWithOverlap": coverage, "opaqueTextureCellIntersections": intersections})
    output = {"schema": 1, "method": "Native pose -> vertex XYZ -> RenderSystem ModelView -> projection; six-plane homogeneous clipping; exact visible UV polygons intersect alpha>=0.1 texel cells with 1e-6 UV expansion",
              "actualResolvedTextureSha256": actual_hash, "officialTextureExactMatch": True,
              "screenCapture": False, "physicalVisualClaim": False, "results": results,
              "scope": "Only captured poses, active native shader path and resolved texture. Other perspectives, shaders, resource packs and titan variants require their own observations."}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(output, indent=2) + "\n", "utf-8")
    print(json.dumps({"samples": len(results), "output": args.out.name}))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ["geometry-report", "mesh-report", "texture-report", "author-jar", "out"]:
        parser.add_argument("--" + name, type=Path, required=True)
    analyze(parser.parse_args())
