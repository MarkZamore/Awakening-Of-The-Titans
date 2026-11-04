# Isolated Fabric runtime QA

This tool uses only marked, independent fixtures below an `artifacts` directory. It never opens a personal world or uses a playing instance as the source. The Java lifecycle/probe JARs are test instrumentation and must never enter published `mods` or distribution locks. No OS input is required. Use `--qa-window hidden` for background native tests; this does not establish a visual verdict.

`baseline` launches the copied exact pack. `integrated` invokes the real launcher `PackRuntimeService`, `PackInstanceService`, `ManagedComponentService`, `PortableIdentityAdapterService`, `CompleteJvmArguments`, `GameNoticeOverlayService` and optional `SkinService`. Both production JVM agents remain enabled for the full game lifetime. Build against one complete clean launcher build with both embedded agent resources; the assembly SHA256, prepared adapter, active JAR hashes and original argument tokens are recorded. An offline QA identity and loopback peer protocol are not an actual Steam account connection.

## Build and prepare

From the pack source root, set `$launcherBuild` to the complete clean launcher DLL directory, `$env:JAVA_HOME` to a real JDK21+, and `$cacheRoot` to an independent prepared fixture or a read-only installation cache source. Runtime cache files are independently copied and hashed; no cache links are created.

```powershell
dotnet build tools/runtime-qa/AotRuntimeQa.csproj -p:LauncherAssemblyDirectory=$launcherBuild -o artifacts/qa-host
python tools/runtime-qa/build_agent.py --jdk $env:JAVA_HOME --output artifacts/qa-agent
dotnet artifacts/qa-host/AotRuntimeQa.dll prepare artifacts/qa --source . --cache-root $cacheRoot --mode integrated --pack-name 'Awakening Of The Titans'
```

The descriptor must be exact Minecraft1.21.1/Fabric. Only the copied pack is a game input. Nested `config/fancymenu/options.txt` and `config/yosbr/options.txt` are preserved; the author baseline and derivative intentionally have different handling of the personal root `options.txt`. No render, simulation or graphics settings are forced by this harness.

## Native client/world lifecycle

```powershell
dotnet artifacts/qa-host/AotRuntimeQa.dll launch artifacts/qa --label fresh-01 --agent artifacts/qa-agent/aot-qa-agent.jar --heap 8192 --player AotQaHost --player-uuid fb63cfc0-aed5-596c-91e0-ebe7e8052c92 --qa-steam-id 76561198000001234 --fresh-world AotQaWorld
```

The synthetic Steam64 value is only a local input to the real portable-identity registry writer; no account login is claimed. The fixed UUID is retained when testing a changed display name. Skin testing is explicitly enabled with `--skin-path` or `--skin-from-install-root`; only the selected PNG/path is read, then an independent PNG is served through the real loopback SkinService for the complete process lifetime. Optional `--peer-skin-path`, `--peer-uuid`, `--peer-name` and `--peer-steam-id` use `SkinPeerLab` and production framed peer protocol over loopback TCP. They exercise actual remote `SkinService` fetching and registry ownership; they do not emulate Steam authentication or discovery. Raw PNGs, registry files, argument files and logs stay private.

After the resource reload completes, write the coordinator marker with `ready <fixture> --label <label>`. The no-transform Java agent observes the actual initialized client, completes vanilla accessibility onboarding in QA options, opens the native CreateWorldScreen and invokes the real create button. New QA worlds are creative with commands enabled; the pack's actual preset/generator remains in effect. Experimental-world acknowledgement is native. Repeated player, level, integrated-server and dimension observations plus save logs and a normal exit establish world entry/save; merely returning from a creation request does not.

`--codecJar`, `--codecResources`, `--codecAuthorJar` and optional `--codecClass` invoke the helper's real Minecraft serializer and policy audit on the actual creation state before creation. `--world AotQaWorld` runs the real `WorldPlayerProfileService.PrepareWorldForLaunch` and reopens an existing disposable world using ordinary QuickPlay; `--server 127.0.0.1:PORT` uses native multiplayer QuickPlay. These paths must be mutually exclusive. The native backup warning may be acknowledged without creating a backup only for the disposable fixture; this never substitutes for a personal-world backup.

`stop <fixture> --label <label>` requests native scheduled game shutdown. `audit` produces bounded exact error families and observations, not an automatic visual, Steam, peer or quality verdict. `refresh-source` requires a stopped marked fixture, archives only its previous canonical pack, copies the new source, and lets the real instance service apply pack-owned changes on the next integrated launch. Existing QA playerdata/options/worlds are retained.

## Explicit opt-in actions and probes

Write one UTF-8 `action.txt` under the current private evidence directory, then wait for its action/result event before writing another. Recognized actions include `identity`, `maps`, `gameplay`, `probe progress|ABSOLUTE_JAR|CLASS`, `probe paired|ABSOLUTE_JAR|CLASS`, `oneclick-lan`, native `respawn` and `command COMMAND`. The one-click action opens the actual pause menu and presses its native Open to LAN button; it does not press the Share screen's start button. Direct `publish` is a separate private test action and does not certify the one-click UI. `local-tcp-fixture` requires an already published native integrated server and temporarily uses offline authentication only within that disposable process; it writes no production config and cannot establish actual Steam authentication. A separate visual inspection is required to claim visual behavior.

Probe JAR/class options are `--probeJar/--probeClass` for the gameplay probe and `--identityProbeJar/--identityProbeClass`, `--mapsProbeJar/--mapsProbeClass`, `--progressProbeJar/--progressProbeClass`, `--pairedProbeJar/--pairedProbeClass` for independent checks. All run against actual client/server objects. Mutating probes enforce their own marked-fixture, player-count and UUID guards. Gameplay death/inventory tests must finish before progress seeding. Map receipts live inside the same private fixture and are reused across normal save/restart. A loopback two-client test must remain distinct from an actual Steam transport/discovery test.

`AttachQa` plus `aot-additional-probe.jar` can attach a separate QA-only probe with `JAR|CLASS|LABEL`; no game classes are transformed. Its tick callback runs on the native client executor with actual client/server/player objects, including null player/server after normal disconnect. Each probe must tolerate that lifecycle. `AotNativeGuestDisconnectProbe` uses ordinary native guest disconnect and title-screen methods; it never terminates or saves a remote host. The separate world-transfer lab under `tools/identity-compat` requires both matching normal-exit receipts and closed worlds, copies into a new destination, and applies the real production outgoing/received profile services before a native host-role-swap check.

`copy_native_cache.py` is a diagnostic closed-fixture cache copy with per-file SHA256 checks. It does not fix an external MCEF download or establish a fresh-install menu pass. In particular, copying under `mods` before instance preparation may be relocated by the launcher's ordinary unmanaged-mod handling; acceptance requires the final production configuration and a real subsequent prepare/start.

After a normal final-client exit, `verify_prepare_retention.py QA_HOST FIXTURE LABEL` hashes native libraries and actual browser state below `launcher-runtime/mcef`, runs the real `prepare-integration`, and requires identical bytes and modification times, unchanged personal `options.txt`, and no new MCEF quarantine. `AotNativeSaveAndQuitProbe` checks that the real pause screen became active before invoking its native Save & Quit method; it waits for an author MCEF screen instead of bypassing it.

`AotGpuAtlasProbe` reads actual render-thread GL limits, uploaded atlas texture sizes and committed native sprite rectangles. It restores texture binding and changes no quality settings. The report summarizes atlas dimensions and geometry without dumping all sprites; it does not conflate packing with shader-link or depth-blit checks.

Inspect the real `live_logging_configuration` observation to verify that the pack's `config/log4j2.xml` selector won, the effective Connection logger is `net.minecraft.class_2535`, and its bounded caught-Throwable filters are live.

## Dedicated server

`prepare_server.py SOURCE NEW_ARTIFACTS_DIRECTORY` copies only pack server roots, excludes top-level JARs explicitly marked Fabric `environment=client`, records every retained/excluded hash, and downloads the pinned official Fabric installer1.1.2 with SHA256 verification. Run its server install command for MC1.21.1/loader0.19.5, then start `fabric-server-launch.jar nogui` with Java21. The private fixture is loopback-only/offline with a fresh disposable world; this is auxiliary QA, not an assigned production role.

Use a persistent console and native `stop`, or the guarded test-only `ServerStopAgent` for ordinary server save/shutdown. Dedicated `Done` is recorded separately from full-client/world acceptance. Exact outcomes and unresolved checks belong in the pack TESTING-STATUS, and raw local logs must not be published.
