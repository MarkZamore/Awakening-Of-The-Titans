# Attack on Titan world type lock

Pack-owned Fabric helper for Minecraft 1.21.1, Fabric Loader 0.19.5 and Danny's AOT 2.5.2. The author JAR declares the stale internal version 2.4.1; both metadata values are accepted. The release is pinned by the pack's SHA256 lock. This helper does not modify that JAR or its generator classes.

The named preset is `attack_on_titan:paradis`, displayed as Paradis / Парадиз. It contains unchanged vanilla Overworld, Nether and End definitions plus exact author Paradis and Paths definitions. GlobalSpawn, configured by this pack, starts players in the existing `dannys-aot:paradis` dimension. Keeping that dimension ID preserves Danny's native portal and gameplay checks. This is the author's terrain, cities and giant forest, including its hills and highlands; it is not a new superflat generator.

The mandatory client mixins restrict the ordinary and Shift preset lists after construction and after settings reload. They select the required preset only after the native constructor has initialized seed, structures and bonus-chest values. A cancellable HEAD gate runs before native dimension baking and save allocation. A missing preset produces an unavailable placeholder and refuses creation. The creation policy does not hook existing-world loading, write configs or impose render-distance restrictions.

## Native safe respawn repair in 1.0.1

GlobalSpawn 2.0.0+1.21 uses its safe criterion for initial spawning, but its respawn getters return raw configured position `(0,60,0)`. An actual fresh-world test found stone at that position and above; native forced-position validation rejected it and the player remained in Overworld for 200 genuine server ticks after death/respawn. GlobalSpawn's own `getFinalSpawnPos(server)` selected `(0,71,0)` and passed that same native validation. The helper does not hardcode either height.

Version 1.0.1 adds a mandatory common-side `ModifyArgs` at the sole call from `net.minecraft.class_3222.method_60590(ZLnet/minecraft/class_5454$class_9823;)Lnet/minecraft/class_5454;` to `method_60588(Lnet/minecraft/class_3218;Lnet/minecraft/class_2338;FZZ)Ljava/util/Optional;`. Only position argument 1 can change. The actual merged GlobalSpawn `globalspawn$shouldOverrideRespawn()` guard must first return true, so valid personal beds/anchors and disabled global spawning retain their original path. The original position/dimension must also match GlobalSpawn's point. The replacement position comes exclusively from its unchanged native `GlobalSpawnPoint.getFinalSpawnPos(server)`. Vanilla validation and its result handling remain in place. There is no delayed teleport, new terrain/height rule, personal spawn replacement, inventory write, destruction flag or permission bypass.

This changes BASE GlobalSpawn runtime behavior despite leaving its author JAR and JSON bytes unchanged. Startup checks the actual Fabric mod container with ID `globalspawn`, requires one original regular JAR and SHA256 `8c9085326528aa1b13d4a110065a80bc6abd2dfe49fd353618b23648e593f9a9`; another base fails initialization and requires review/rebuild. Exact untransformed Minecraft ServerPlayer class SHA256 is `5e273a05155e48ae753b22e364a646a0f2ee70930528aecf9b2a0760bf76ff07`. The native transformation audit requires exactly one injected argument handler before the preserved single validation call. Offline placement alone is not runtime PASS: corrected natural respawn, personal bed/anchor and disabled-global negative cases require native QA after installing the candidate.

## Native MCEF cache roots in 1.0.2

Client-only exact-base hooks relocate the two mutable MCEF data roots to `GameDir/launcher-runtime/mcef/libraries` and `GameDir/launcher-runtime/mcef/browser-cache`. The launcher's existing generic instance ownership already preserves `launcher-runtime`; no launcher MCEF rule is needed. MCEF's original JAR remains unchanged, but its BASE runtime path factories are modified explicitly. Fabric metadata describes the creation policy, native safe respawn repair and these cache paths.

The mandatory client Mixin plugin verifies the original Fabric `mcef` mod container JAR SHA256 `b5270016b85a6c3c26cc88c11ca3c128b937e6b98d3c86605d48502693f81553` before applying either marker. It does not add a common-side MCEF dependency: a dedicated server without client MCEF can still load the creation/respawn helper. The plugin audits the real merged native class after Mixin application. `CefDownloadMixin.setupLibraryPath()V` actually merges into **ClientPackSource `net.minecraft.class_1065`**, not Minecraft. Immediately before the sole native `File.mkdirs()`, the root File local is replaced with `McefCachePaths.librariesDirectory()`. Both original development/normal branch shapes must be present exactly once, and native canonical path assignment for both `mcef.libraries.path` and platform-specific `jcef.path` stays intact.

The second exact factory is `com.cinemamod.mcef.CefUtil.<clinit>()V`, which still resolves the actual Minecraft game directory and assigns its single `CACHE_PATH` field. Only its `mods` and `mcef-cache` path segment constants change to `launcher-runtime` and `mcef/browser-cache`. All other methods are verified unchanged by the native factory audit. There is no bootstrap flag, alternate downloader, downloaded binary replacement, skipped checksum, disabled native guard, changed cache setting, browser sandbox/quality change or graphics fallback. `-Dmcef.libraries.path` alone cannot solve the issue because MCEF overwrites it in its own factory; its native browser-cache factory is separate. Mutable native/browser files are deliberately absent from managed source files under `mods`.

Use `--mcef-jar PATH_TO_LOCKED_MCEF_JAR` with the native build audit. That test first applies the author's actual CefDownloadMixin to real native ClientPackSource, then executes the production factory patch and verifies both factories plus unchanged surrounding methods. This offline proof does not establish plugin ordering, full CEF initialization or retained caches across prepare/relaunch; those require the final loaded-client fixture.

## Rebuild

Use JDK 25.0.2 to compile Java 21 bytecode. Dependencies and their SHA256 values are in `build-dependencies.json`. Output must be under an ignored `artifacts` directory:

```powershell
python tools/world-type-lock/build_helper.py --output artifacts/world-type-lock
```

To audit exact native Minecraft bytecode and metadata, point `--native-shared` at a prepared launcher Shared directory containing the official 1.21.1 client and descriptor libraries:

```powershell
python tools/world-type-lock/build_helper.py --output artifacts/world-type-lock --native-shared PATH_TO_SHARED --mcef-jar PATH_TO_LOCKED_MCEF_JAR
```

The native audit verifies official input hashes, uses pinned intermediary mappings/remapper, transforms four exact native classes with the actual Fabric 0.19.5 Mixin/ASM dependencies, and checks injection placement. It also parses `pack.mcmeta` with the real Minecraft serializer: resource format 34 with supported formats 34–48 also covers the 1.21.1 data format 48. The output ZIP uses sorted entries, fixed timestamps, permissions and stored compression for reproducible bytes. `--verify-installed PATH_TO_JAR` compares rebuilt and installed bytes without installing anything.

## Loaded-client audit

Offline transformation and metadata tests do not establish full-client or custom-generator success. The optional native audit JAR contains only a QA verifier, not a mod, and must never enter the published `mods` directory. A QA bridge running in a loaded Fabric client can invoke:

```java
NativeResourcesTest.verify(actualWorldCreationUiState, resourceRoot, lockedAuthorJar, reportJson);
```

This uses the real initialized registry context and Danny's codecs, round-trips the preset, compares Paradis/Paths against the exact author JAR, compares vanilla dimensions against the actual normal preset, and checks selected/normal/Shift lists. Client world creation, settings reload, missing-resource refusal, save/reload and LAN require separate runtime evidence.

## Upstream updates

This source is a maintained pack overlay, independent of an author archive. Reapply it when refreshing the base pack, rebuild it and update the delivery lock sequentially. Changed Minecraft/loader/author target hashes require a new native/runtime audit. Never silently weaken mandatory injections to accommodate an upstream change.
