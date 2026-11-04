# Native first-person camera QA

This stand observes native rendering in a marked, independent artifacts fixture. It is QA code, has no Fabric entrypoint, and must never enter `mods`, an overlay or a distribution lock. It does not transform classes, capture screen pixels, use physical input, suppress effects or change production files.

`AotCameraMeshProbe` installs zero-draw player and GeckoLib layers and counts real `WorldRenderEvents.END` callbacks. Its player layer survives both animation libraries' first-person feature filters. It records head/hat visibility inside the actual local-player render pass. A temporary third-person control proves that the observer receives real renders; zero self-model calls in ordinary first person are then meaningful. Model-part flags sampled after a frame and the camera's presence inside a player AABB are insufficient tests.

`AotCameraScenarioProbeV2` is a **mutating disposable-world driver**. It requires the fixture marker, exactly one matching server player and `enable-camera-actions.flag` alongside its report. It selects creative mode to prevent unrelated NPC deaths, assigns Eldian/Attack through native commands, replaces the fixture's leggings/main/offhand slots, and uses the real `ODM_HARNESS` plus `OdmHarness.setGear` to carry a gas-filled `ODM_GEAR`. It checks `OdmHarness.getGear`, holds/releases the author's logical hook keys, observes real hooks, sends the normal `TitanShiftPayload`, observes the mounted titan and authored perspective, requests first person, then uses the same payload to open the native dismount state. After native `isDismountAllowed`, it holds/releases the ordinary sneak key and requires actual detachment. Finally it requests native death/respawn. It never teleports, overwrites cooldown/ownership flags or forges a hook/vehicle state. Original perspective/pitch are restored on normal completion; the disposable inventory and power assignment are intentionally not restored.

`AotHeadGeometryProbe` records actual head/mouth/hair bone poses, native cube transforms, camera coordinates and active RenderSystem matrices inside `GeoRenderLayer.renderForBone`. It performs no draw calls or visibility writes. `AotHeadStaticMeshProbe` exports the initialized native baked quad geometry. `AotResolvedHeadTextureProbe` records the real renderer's texture ID/render type and the bytes returned by the initialized native ResourceManager. The exported PNG is a resource asset, never a screen capture.

The native path used here is GeckoLib `createVerticesOfQuad` applying the bone/cube pose to vertex XYZ, then Minecraft BufferRenderer passing RenderSystem ModelView/projection to the active entity-cutout shader. The shader applies `ProjMat * ModelViewMat * Position`. Do not apply a second camera transform. Record the active shader configuration; a different shader backend needs its own path audit.

## Build

Use JDK 25.0.2 targeting Java 21. `build_camera.py` checks exact inputs before compiling and emits a plain deterministic JAR under an artifacts directory. Its pins are:

| Input | SHA256 |
| --- | --- |
| Minecraft 1.21.1 intermediary client from the helper build | `af3d814b0930a9c018c9c839e35859657894d61b51df3a8a0cd588d67350b169` |
| Minecraft JOML 1.10.5 | `cac9f22f83a7aa33eebda73c16ff5261e3cb4911b6bafcf4c79ea486099d0c9a` |
| GeckoLib 4.9.2 | `eac4cf55e1cb99b22cb91ea87c774d2a670c1711a80f4aff50e6ad56b6c58e7e` |
| Danny's AOT author JAR | `eb30a874e618cbf1f356301d672e7b322673babfa46f8212bc0e98f112eb085d` |

From the pack root, pass local tool/cache paths explicitly:

```powershell
python tools/runtime-qa/camera/build_camera.py --jdk $jdk --minecraft $intermediaryClient --joml $joml --out artifacts/camera-probes
```

Use the existing `AotAdditionalProbeAgent` from the lifecycle stand to load each class against the actual Fabric game classloader. Agent arguments are `ABSOLUTE_QA_JAR|CLASS|LABEL`; the bridge invokes `tick(client, integratedServer, clientPlayer, reportPath)` on the client thread. Attach the texture observer before transformation so it can observe the first mounted frame. Use one disposable client, install all passive probes first, then explicitly create the driver's enable file and attach the driver. Keep the native game unpaused. Do not run death/inventory scenarios against a seeded progress fixture.

## Frustum and alpha check

The analyzer requires Pillow and the private reports from the same initialized client. It checks that the actual resolved texture bytes exactly equal the pinned author JAR resource and that the observed native render type is `entity_cutout_no_cull`. It clips each captured native triangle against all six homogeneous frustum planes, retains perspective-correct UV polygons, then tests intersections with **every opaque texture cell** in those visible polygons, including the authored emissive glow mask. UV cells expand by `1e-6` to avoid treating a numerical boundary as transparency. This is stronger than a finite screen sampling grid and does not require image capture.

```powershell
python tools/runtime-qa/camera/analyze_head_geometry.py --geometry-report $geometryReport --mesh-report $meshReport --texture-report $textureReport --author-jar mods/dannys-aot-2.5.2.jar --out artifacts/head-alpha-proof.json
```

In the accepted native run, the actual base resource was `dannys-aot:textures/entity/attacktitan2.png`, from resource pack `dannys-aot`, with SHA256 `c5f558d1fbdf5d1c3b3ba9e85543a7fbfd74102bb532973fa5cc5fd6ec157793`, exactly matching the author JAR. The ordinary Attack first-person head/mouth/hair geometry was outside the viewport. During the native dismount transition some hair planes crossed the near plane and viewport, but the visible UV polygons intersected zero opaque base/glow-mask texture cells. Hiding the head on that evidence would have been an unnecessary content change. No render fix was added.

The native scenario also observed active left/right ODM hooks, actual Attack transformation, authored third-person transition, requested first person, native dismount permission followed by sneak/detachment, and first-person human state after respawn. Native player head/hat visibility and a positive third-person control were checked separately. These results cover the captured poses, current author resources and shader configuration. They do not claim physical visual inspection, every camera angle/titan variant, user resource packs/shaders or actual Steam transport. Reports and exported resources remain private artifacts; do not publish raw fixture paths or logs.
