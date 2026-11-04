# Optional native Multiplayer Steam-row fixture

This is QA instrumentation, not a mod, javaagent or distribution file. It runs only inside the marked disposable runtime and requires Minecraft 1.21.1 with the production Steam-discovery adapter enabled. It does not transform classes or change the managed transport JAR.

Build against the actual intermediary Minecraft 1.21.1 JAR and its Brigadier dependency:

```powershell
python tools/identity-compat/native-multiplayer/build_probe.py `
  --minecraft-jar <mapped-native-Minecraft-1.21.1.jar> `
  --brigadier-jar <runtime-brigadier.jar> `
  --output <private-artifact-directory>
```

The build records input and output SHA256 values. The JAR exposes `dev.lanminecraft.aotqa.discovery.NativeMultiplayerSteamProbe.tick(Object client, Object server, Object player, String reportPath)`. The existing optional additional-probe bridge calls this on the real client thread, including after `player` becomes null. It waits for a normal disconnect before opening Multiplayer, then restores the previous native screen in a finally block. Do not force a disconnect while another probe is saving or inspecting a world.

The precise synthetic boundary is `PortableSteamDiscoveryHooks.Poll.apply`. The fixture constructs a sanitized QA world description and passes it through that actual production method. It does not write native LAN rows directly, replace Steam classes, inject a fake Steam API or call a synthetic lobby join. Real friendship, lobby ownership, protocol/version validation and Steam connection remain unverified by this fixture.

The actual native `class_500` screen initializes normally. Its tick creates an actual `class_4267$class_4269` entry in the native server-list widget from the production provider's `class_1131` record. The real native row renderer executes with a recording DrawContext which captures its text arguments instead of issuing GPU text draws. The fixture requires the world title, host line and Steam label, respecting an existing hidden-address option; native narration must also use the world name. It verifies that the managed list suppresses a UDP entry while an unrelated native list retains ordinary UDP behavior. Ordinary manual addresses must pass through the hook, and a stale synthetic address must be consumed by the actual native join method before entering the normal connection screen. `servers.dat` must remain unchanged.

This tests production provider-to-native-UI integration in a running Minecraft process. It does **not** certify GPU appearance, a genuine Steam friend/discovery result, or an actual Steam transport session. Those limitations are explicit fields in every report. Compilation is not a runtime PASS.

Temporary screen state is removed through the native screen lifecycle and production `end` hook. No saved server or player setting is intentionally changed, and no physical input or computer-use tool is involved.

The current probe also waits for the author's MCEF initialization before touching a screen; a failed download is a failure, never a reason to bypass the MCEF menu. It records actual native/browser-profile paths as outside-`mods` booleans for relocation QA. The first immutable probe's failed screen attempt remains historical evidence. See [mcef-audit.md](mcef-audit.md) for the two distinct factories, exact JAR SHA and executed Java network diagnostic.

The optional standalone checksum diagnostic can be compiled with Java 21 and run as `McefDownloadTransportProbe <new-private-output-directory>`. It makes two read-only HTTP requests to the pinned author's checksum URL, records status/length/hash and keeps the response in that private output. It does not launch Minecraft, download the native archive or alter mod settings.
