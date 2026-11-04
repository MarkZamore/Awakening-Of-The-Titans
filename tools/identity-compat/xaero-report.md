# Xaero minimap and waypoint compatibility audit

This review concerns the exact author file `xaerominimap-fabric-1.21.1-26.6.0.jar`, SHA256 `86bdc4282a73ed55e6a4903a7ba65940a6aed20404f7ab7c96f8d94078accd1b`. It contains XaeroLib Fabric 1.7.3. The pack does not contain Xaero's World Map or JourneyMap; their pack-specific fixes must not be copied into this pack as substitutes for testing Xaero Minimap.

The launcher source reviewed is published revision `b34419864f1e0a2ec24725ced67b7aecc8fe7bd6` (438). The older, modified launcher checkout is not the production reference.

## Confirmed native behavior

- `MinimapWorldStateUpdater.getAutoRootContainerPath(int)` uses format 4. For a local integrated host it derives its root from the actual save folder. For a multiplayer client it uses `ServerInfo.address`, removing the port at bytecode offsets 222–235. Changing a TCP/LAN port alone therefore does not change the root.
- Steam addresses contain a changing session token, not only a port. The form `s-<host SteamID in base36>-<session token>.steam` does change the native root between sessions. Port removal does not solve that case.
- `getPotentialWorldNode` uses the server's persisted `serverLevelId` when supplied and not explicitly ignored by the player's Xaero settings. `CommonEvents` reads that ID from the server-root `xaeromap.txt`; it does not generate an unrelated ID for each dimension. `LevelMapPropertiesIO` persists `id:<integer>` and sends the same native properties through Xaero's normal packet path.
- `MinimapDimensionHelper` derives a separate directory from the real registry namespace and path. `dannys-aot:paradis`, `dannys-aot:paths`, and vanilla dimensions remain distinct. No replacement dimension ID or forced single map identity is needed.
- Native waypoint output still uses `waypoint:`, `sets:`, and optional `slime_chunk_seed:` records, matching the launcher's `xaero-waypoints-text-1` provider. Local world files use `waypoints` and multiplayer files use the server-ID node; no file format conversion is justified by this audit.

## Existing launcher mechanisms

[`XaeroMultiplayerFolderResolver`](https://github.com/MarkZamore/LANMinecraft/blob/b34419864f1e0a2ec24725ced67b7aecc8fe7bd6/Program/XaeroMultiplayerFolderResolver.cs) identifies every session folder for the exact host SteamID segment. It does not confuse another host whose session token happens to contain the same digits. `WaypointSyncService` exports the same world context across those folders and imports the snapshot into the known session addresses; a stable pending address is used only until the first real join.

[`XaeroMinimapWaypointProvider`](https://github.com/MarkZamore/LANMinecraft/blob/b34419864f1e0a2ec24725ced67b7aecc8fe7bd6/Program/WaypointProviders.cs) reads `xaeromap.txt`, retains dimension subdirectories, validates native text, and rejects traversal/invalid snapshots. The instance service treats `xaero` as personal data; its cleanup only removes an empty/default-only configuration tree, not a tree containing waypoint files. Pack updates must continue to exclude this personal tree from pack-owned files.

The published launcher's eight `XaeroMultiplayerFolderResolverTests` passed with zero skips during this review. This proves the resolver's exercised segment matching, ordering, address round trip, and pending-address behavior. It does not prove a running map, a real Steam peer, or new-format native file round trips.

## Required runtime observations

1. In the actual full client, inspect `HudMod.INSTANCE.getMinimap().getCrashedWith()` and the active MinimapSession. Confirm the current native world path belongs to Paradis and the rendered minimap remains healthy while moving through city and giant forest. A null crash flag is not visual proof by itself.
2. Add a uniquely named sentinel through native `Waypoint`/`WaypointSet`, call native `saveAllWorlds`, save and restart the client, and verify name, coordinates, set and dimension by native getters. Confirm ordinary personal map data survives production instance preparation and pack synchronization.
3. In a guest fixture, repeat the same world with two different Steam-shaped session tokens. Exercise the production resolver/provider/sync service against actual native files and verify the sentinel reappears in the new session with no duplicate or foreign-world marks. A simulation must be reported as a simulation rather than a real Steam guest connection.
4. Visit another dimension and another world: preserve the original Paradis marker in its own map and avoid leaking it into the unrelated context. Verify the persisted server `xaeromap.txt` survives save/reopen.
5. Test a genuine Steam guest connection separately if available; local TCP and native path probes alone do not certify the Steam transport or friendship/discovery flow.

## Native QA entry points for this exact file

All client calls belong on the actual client executor, after a world is loaded:

```text
xaero.hud.minimap.BuiltInHudModules.MINIMAP.getCurrentSession()
  .getWorldState().getCurrentWorldPath()
  .getWorldState().getAutoRootContainerPath()
  .getWorldManager().getCurrentWorld()
  .getWorldManagerIO().saveAllWorlds(session)

currentWorld.getDimId()
currentWorld.getNode()
currentWorld.getCurrentWaypointSet()

new xaero.common.minimap.waypoints.Waypoint(x, y, z, name, symbol, color)
currentSet.add(waypoint)
currentSet.getWaypoints()
```

No author JAR, pack configuration, personal map, or launcher source was changed by this audit. No additional Xaero compatibility patch is presently justified; runtime failures must be addressed from their exact evidence.
