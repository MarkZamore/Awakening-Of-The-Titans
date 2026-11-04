# Native gameplay probe

`AotGameplayProbe` is the current public default disposable-world grave QA code, never a shipped mod. Its implementation matches the separately named `AotGameplayProbeV3` used during validation. Historical compiled V2 reports used a raw Gear item in slot 37 and did not prove worn harness nesting; those private receipts remain distinct from the current source. No probe transforms classes or patches native guards. The runtime coordinator invokes `tick(client, integratedServer, clientPlayer, reportPath)` on the client thread every three seconds after explicitly enabling gameplay. It uses `server.execute` for server mutations and the normal client respawn/network requests for client actions.

The process must expose the coordinator's `lanminecraft.qa.root` property, point at an artifacts fixture marked `.aot-runtime-qa.json`, and contain exactly one online fixture player. Reports must stay below that fixture. The first phase clears that fixture player's inventory; therefore this code must never be attached to a personal world.

V3 equips the real `ODM_HARNESS` in LEGS, puts the Gear stack with 321 gas inside it through `OdmHarness.setGear`, equips paired loaded grips through `BladeItem.reloadBlade`, a canister with 73 gas and 16 spare blade components. It requires actual `OdmHarness.getHarness(player)` and `getGear(player)` plus actual main/off-hand grips before death and after restoration. It invokes native player death, requires exactly one new You're in Grave Danger grave, compares full native serialized item components/counts including nested Gear, checks for matching ground duplicates including loose nested Gear, requests native respawn, then invokes the normal grave click handler and verifies restoration and claimed status. YIGD deliberately retains historical admin backups; backup contents alone are not counted as playable duplicates. An optional `graves-only.flag` next to the report stops after both grave scenarios and does not rerun the independently proven Burnt phase.

The transformed death scenario runs first in a fresh fixture, before a previous death can legitimately trigger the author's defeat recovery. It uses Danny's native `/daot bloodline set` and `/daot shifter set` command paths, verifies the native power authority record, and sends the same `TitanShiftPayload` used by the author's client key handler. Commands target the validated real profile name: the native players-only argument rejects raw UUID literals. Brigadier execution propagates rejections and records actual return values. It requires an observed native Titan vehicle before transformed death. Failure to observe transformation within the bound is explicitly `NOT_TESTED`, never PASS. The ordinary equipped-player death follows independently.

The fire phase queries the initialized runtime BiomeSource with its actual RandomState sampler before generating any chunks, then verifies the loaded giant-forest biome, finds an exposed naturally generated log and ignites it with native fire. The bounded read-only search extends to 131072 blocks because the author's city exclusion region can exceed 8192. It observes normal server ticks and block changes for 120РІР‚вЂњ1200 ticks, capped at 120 wall-clock seconds. The immediate ignition snapshot is separate; delayed-spread PASS requires at least two newly converted Burnt positions outside ignition after 120 real ticks. It does not place substitute trees, force random ticks, alter Burnt settings or disable effects. A bounded observation without proven Burnt conversion/spread is reported as unproven.

Compile with JDK 25.0.2, targeting Java 21:

```powershell
javac --release 21 -g:none -encoding UTF-8 -d artifacts/gameplay-probes tools/runtime-qa/probes/AotGameplayProbe.java
jar --create --file artifacts/aot-gameplay-probes.jar -C artifacts/gameplay-probes .
```

Load the resulting plain class JAR with a fresh `URLClassLoader` whose parent is the actual Fabric game loader. It has no Fabric entrypoint and must not be installed in the published `mods` folder. Keep original failed observations when correcting a probe API assumption and rerunning it.

## Creation, datapack reload and recreate audit

`AotWorldFlowAudit` is a codec hook for the existing lifecycle QA agent. It requires a stopped, marked artifacts fixture, a fresh native creation screen, `evidence/hold-create.flag`, and `-Dlanminecraft.qa.recreateWorld=AotQaRecreate`. Copy a previously closed disposable QA save into that fixture's `saves/AotQaRecreate`; never copy an active/personal world. Use `--codecClass AotWorldFlowAudit` and the plain audit JAR containing this class plus the existing `NativeResourcesTest` classes. Production mods/configs/locks remain unchanged.

The negative case clones the real frozen native registry layers, omits exactly `attack_on_titan:paradis`, constructs a real transformed `WorldCreationUiState`, then temporarily supplies it to the actual creation screen. It invokes native `onCreate` and requires the helper AlertScreen, no integrated server and no newly allocated save. The original creation state is restored immediately. This proves fail-closed behavior when the mandatory preset is absent from a loaded registry; it does not claim that startup with an on-disk corrupted resource pack was tested.

The reload case calls native `CreateWorldScreen.applyNewPackConfig`, waits for the asynchronous native WorldLoader to replace its creation context and return to the actual creation screen, then checks the preset, ordinary/Shift lists and seed/structures/bonus chest. The native consumer argument handles validation-failure choices only; it is deliberately not treated as a successful completion callback.

The recreate case opens native SelectWorldScreen, locates the explicitly named QA source save and invokes that real world-list entry's `recreateWorld`. It requires the actual recreated-screen flag and restricted lists/selection, records the recreated options, verifies source `level.dat` bytes unchanged and verifies no new save/server allocation. It does not infer recreate success from constructor tests. Results are written to `native-world-flow-report.json`; only actual completed stages receive true fields.

`AotRespawnObservationProbe` independently requests native death/respawn and observes the final server dimension for 200 real END_SERVER_TICK callbacks. It never teleports, clears inventory or changes configuration. Run it in its own fresh disposable world, because death must not invalidate a seeded persistence test. Its report compares raw GlobalSpawn position validation with GlobalSpawn's own safe resolver and records dimension/coordinates at ticks 1, 5, 20, 40, 100 and 200.

`AotRespawnNegativeProbe` exercises the real native respawn-target method with a valid personal bed in Overworld, a charged anchor in Nether, and disabled global spawning. It compares the actual target to native personal-position validation and requires the author's guard to return false. The anchor's charge must remain unchanged. Tiny disposable platforms refuse to overwrite existing BlockEntities and are restored afterward. The disabled branch uses the author's own unset/set API on the fixture; original player spawn fields, blocks and exact fixture GlobalSpawn JSON bytes are restored in `finally`. It never changes production configuration or substitutes a permissive guard.

`AotNativePortalProbe` places the actual registered `dannys-aot:paradis_portal` and its native BlockEntity only in the fixture player's position, invokes the original `ParadisPortalBlock.onEntityCollision`, observes the real Overworld destination, waits for the native cooldown to expire and invokes the portal again to return to Paradis. The original source block is restored after each invocation. No teleport command, destination override, tracker fabrication or cooldown bypass is used. This proves those collision callbacks and actual transitions; it does not claim physical walking through every naturally generated portal or coverage of all Paths abilities.

Build the complete plain QA JAR reproducibly with the locked compiler and native audit class:

```powershell
python tools/runtime-qa/probes/build_probes.py --output artifacts/aot-runtime-probes.jar --native-audit-jar artifacts/world-type-lock/aot-world-type-lock-native-audit.jar
```

The default `AotAdditionalProbeAgent` manifest can attach a named probe from that same JAR. Rebuild the current `AttachQa.java` before passing agent arguments: an earlier compiled helper silently omitted the third argument and cannot load parameterized agents. Failed earlier observations remain in their private evidence directories; a harness correction is not a production failure or a retroactive PASS.
