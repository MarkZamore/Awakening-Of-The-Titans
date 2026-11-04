# Optional native Xaero waypoint probe

This is fixture instrumentation, not a Fabric mod and not a distribution file. It is pinned to the unmodified author Minimap 26.6.0 JAR and refuses a different SHA256. The coordinator calls `dev.lanminecraft.aotqa.xaero.XaeroProbe.tick(Object client, Object server, Object player, String reportPath)` on the real client thread after joining a disposable QA world.

Build with `python tools/identity-compat/xaero/build_probe.py --output artifacts/qa-xaero` (Microsoft JDK 25.0.2, Java 21 bytecode). The reflection-only JAR has no compile dependencies and no transformer or game entrypoint.

Create phase JVM properties:

```text
-Daot.qa.mapsProbe.mode=create
-Daot.qa.mapsProbe.receipt=<absolute private fixture receipt path>
```

Use a new receipt and a disposable integrated QA world. The probe adds one `LANMinecraft_QA_Paradis_Marker` to the native current set, invokes native `saveAllWorlds`, checks the actual serialized file, and records coordinates, dimension and native world identity. Existing waypoints are left untouched.

After a normal save/exit, start the same world again with `mode=verify` and the same receipt. The probe reads the actual native collection, verifies exactly one marker and all its coordinates/set/dimension/world identity, and calls native save again. A fresh report path belongs to each launch. A changed Steam session-root test may additionally set `-Daot.qa.mapsProbe.allowRootChange=true`; this still requires the same native world node and marker. It does not itself run the launcher waypoint synchronization service.

Optional properties: `aot.qa.mapsProbe.sentinel` (letters/digits/underscore), `aot.qa.mapsProbe.dimension` (default `dannys-aot:paradis`). Do not use these to claim another dimension was tested without actually entering it.

The report records minimap crash/FBO/session flags and hashed native path/address identities. It explicitly does not claim visual assessment, a genuine Steam guest connection, or a successful transport test. Compilation is not a runtime PASS.

## Production waypoint synchronization simulation

`XaeroSyncLab.csproj` is a separate console fixture. It references a complete built launcher through `LauncherAssemblyDirectory`; it never builds or edits the launcher. It runs the actual `XaeroMinimapWaypointProvider`, `XaeroMultiplayerFolderResolver`, `WaypointSyncService`, store, metadata service and production frame reader/writer. A loopback TCP transport substitutes for Steam only at `IPeerTransport`.

After the runtime coordinator has confirmed normal exit, copy the native files into a new frozen input directory:

```text
game/saves/<one QA world>/level.dat
game/saves/<one QA world>/xaeromap.txt
game/xaero/minimap/<native world root>/...
maps-receipt.properties
stopped-source.json
```

`maps-receipt.properties` is the native Java probe's receipt, even if the original filename ended in `.json`. The stop receipt must contain `normalExit: true`, `exitCode: 0` and evidence identifying the stopped disposable QA session. Create it only after observing normal shutdown. A directory copied while Minecraft is running is not valid input. Do not copy a personal world or present this small technical fixture as a world backup.

Build into separate artifact directories and run with a fresh output directory:

```powershell
dotnet build tools/identity-compat/xaero/XaeroSyncLab.csproj -c Release `
  -p:LauncherAssemblyDirectory=<complete-launcher-output> `
  -p:BaseIntermediateOutputPath=<separate-absolute-obj-directory>/ `
  -o <separate-lab-output>
dotnet <separate-lab-output>/XaeroSyncLab.dll <frozen-input> <new-fixture-output> mods/xaerominimap-fabric-1.21.1-26.6.0.jar
```

The fixture verifies the pinned JAR SHA and native receipt, copies input into disposable host/guest trees, and imports the exact native marker bytes into a first synthetic Steam session. An empty production pull is followed by a production push to the host's per-player world store. A second guest service instance reloads persistent state, selects a new synthetic session token and pulls the snapshot into both session folders. It requires identical bytes, coordinates, set and dimension paths; separate world-context and other-host control files must stay unchanged. The frozen input's full file hashes must also remain unchanged.

Successful execution writes `report.json` and prints `XAERO_PRODUCTION_SYNC_SIMULATION_PASS`. Reports explicitly distinguish this from an actual Steam connection, guest rendering and additional native dimension visits. The control files are synthetic QA records; the waypoint record under test comes from the native game, without hand-written conversion.

Executed against the stopped `integrated-02` native fixture and the candidate launcher assembly SHA256 `1acd87c9fe286e1340d5919303f57017d2e82c7dec9213d0710b1ecfc5b1f799`: PASS, three protocol exchanges, exact native Paradis snapshot SHA256 `26fe93edcb11cc88879a78136873352bce5788680e70c32cea30de1aebb8c434`. Private final output is `build/identity-compat/xaero-sync-simulation02-final/report.json` outside the pack source. The lab's synthetic `qa-xaero-sync` alias is independent of the real pack's canonical display name.
