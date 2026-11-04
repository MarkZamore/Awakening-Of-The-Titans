# MCEF native-menu and cache-path audit

Pinned author JAR: `mcef-fabric-2.1.6-1.21.1.jar`, SHA256 `b5270016b85a6c3c26cc88c11ca3c128b937e6b98d3c86605d48502693f81553`. Its manifest pins Java CEF commit `a78e832f9f13c2c688caea3d04d8b84fcd238d94`. This is a read-only audit; implementation of a pack-owned relocation belongs to the gameplay/helper owner.

## Concrete cause and limits

The first native Multiplayer fixture failed because the author `MCEFDownloaderMenu` intercepted the ordinary screen change while MCEF was not initialized. Setting the screen field directly would bypass the author lifecycle and is not acceptable proof. The second probe waits for normal MCEF initialization, reports its actual downloader/init flags, and attempts the native screen only afterwards.

The launcher mirrors all source `mods/**` files, not only JARs. Its `PackInstanceService.MirrorMods` moves unknown destination files into PackConflicts and removes them from the active mod directory. Actual preserved conflict files include MCEF's previously downloaded native DLLs/resources. The author's default native and browser-cache directories both sit under `mods`, so an ordinary later preparation can evict them. A helper may relocate these two path factories into personal instance storage; adding launcher rules for the mod is unnecessary.

The warning `Failed to download JCEF hash` is misleading on a first launch. The actual method first downloads the temporary checksum, then emits that warning if the old cached checksum does not exist. The 314-byte response is a valid author-generated PowerShell hash table, not evidence of HTML corruption. It contains archive SHA256 `E98C385542620F31A594D6FC3C38ED6BCA8A547E24CED982A1339BB3333668BE`. The downloader compares old/new checksum-file bytes rather than parsing this table. These branches are visible in the [official downloader](https://raw.githubusercontent.com/CinemaMod/mcef/1.21.1/common/src/main/java/com/cinemamod/mcef/MCEFDownloader.java) and were confirmed against the pinned JAR bytecode.

MCEF requests that checksum on every startup. A matching cached checksum and an existing library root skip the large archive download. An HTTP exception during checksum retrieval still fails initialization; neither preseeded binaries nor `skip-download=true` provide a supported offline bypass. Preserve the author's checksum, native-loading and initialization behavior.

## Two distinct exact targets

1. Native binaries: `com/cinemamod/mcef/mixins/CefDownloadMixin`, private static `setupLibraryPath()V`. The actual JAR annotation targets `net/minecraft/class_1065` (official `ClientPackSource`, raw `grc`), **not** Minecraft/class_310. The `@Unique` helper is merged into that target; its injection runs at the head of `<clinit>`. It chooses `../build/mcef-libraries/` if the sibling build directory already exists, otherwise `mods/mcef-libraries/`. It creates the root and sets both `mcef.libraries.path` and `jcef.path` (root plus normalized platform). Consequently a JVM property alone is overwritten. An exact helper must bind to this merged factory/site and preserve downstream platform-path construction. See the [official mixin](https://raw.githubusercontent.com/CinemaMod/mcef/1.21.1/common/src/main/java/com/cinemamod/mcef/mixins/CefDownloadMixin.java).

2. Mutable browser profile: package-private final `com/cinemamod/mcef/CefUtil`, `<clinit>()V`. It gets Minecraft's game directory, converts it to Path, resolves `mods`, then `mcef-cache`, and writes `CACHE_PATH:Ljava/nio/file/Path;`. Later `init()Z` converts that field to an absolute string and assigns `org/cef/CefSettings.cache_path:Ljava/lang/String;` when the author's `use-cache` is true. This is a separate factory. Relocating native binaries alone does not preserve cookies/profile state. Do not ship or freeze the browser profile. See the [official CefUtil](https://raw.githubusercontent.com/CinemaMod/mcef/1.21.1/common/src/main/java/com/cinemamod/mcef/CefUtil.java).

The original author JAR must remain byte-identical. Any helper injections into these two sites must be disclosed separately, tied to its exact SHA and verified during the real client lifecycle. Preserve `skip-download=false`, `use-cache=true`, browser settings and native platform guards.

## Executed network check

`McefDownloadTransportProbe.java` reproduced the downloader's two-GET HttpURLConnection pattern using the actual game runtime `21.0.12.1+1-LTS`. Both checksum requests succeeded: HTTP 200, content length 314, 314 body bytes, response SHA256 `78996b1d5e0a43d0b81182049a238a7dedba2d4e625dd4718790cf22230a2c79`, expected archive hash present. An earlier Python request returned 403 and does not represent the game's Java network route. Do not describe the official Java endpoint as broken from that Python result.

Private evidence: `build/identity-compat/mcef-network-probe/java21-direct/transport-report.txt`. The standalone probe has diagnostic timeouts only; it does not change game timeouts or author code.

Required acceptance after relocation: two real preparations/launches retain the native root and browser profile outside `mods`, the second startup skips a full archive download, MCEF initializes normally, and the native Multiplayer fixture passes without bypassing a menu. This audit does not mark those later checks complete.
