using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using CmlLib.Core;
using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;
using Minecraft;

// All mutations, including native world creation and shutdown, are confined to
// a marked independent fixture. No personal instance/world is an input.
if (args.Length < 2) throw new ArgumentException(
    "Usage: prepare <fixture-root> --source <pack> [--cache-root <installation>] [--mode baseline|integrated]; " +
    "launch <fixture-root> --label <new-label> --agent <qa-agent.jar> [--heap 10240] [--world AotQa...] [--fresh-world AotQa...]; " +
    "bind|bind-derived <fixture-root> --source <pack>; ready|stop|audit <fixture-root> --label <label>");
var command = args[0];
var root = FixturePath(args[1]);
var options = ParseOptions(args.Skip(2).ToArray());
var json = new JsonSerializerOptions { WriteIndented = true, PropertyNameCaseInsensitive = true };
var marker = Path.Combine(root, ".aot-runtime-qa.json");
var paths = new AppPaths(root);
var packName = options.GetValueOrDefault("pack-name", "Awakening Of The Titans");
RequireComponent(packName);
var label = options.GetValueOrDefault("label", "run-01");
if (!System.Text.RegularExpressions.Regex.IsMatch(label, "^[A-Za-z0-9_-]{1,64}$"))
    throw new ArgumentException("Invalid evidence label.");

if(command=="fresh-profile") {
    if(!File.Exists(marker)||HasLiveQaClient())throw new IOException("Fresh profile requires a stopped marked QA fixture.");
    var f=Read<Fixture>(marker);var game=paths.CombineUnderInstances(f.PackName);
    var archived=Path.Combine(root,"archived-instances",DateTime.UtcNow.ToString("yyyyMMdd-HHmmssfff"));
    if(!IsWithin(game,root)||!IsWithin(archived,root))throw new IOException("Profile archive escaped fixture.");
    RejectLinks(game);Directory.CreateDirectory(Path.GetDirectoryName(archived)!);Directory.Move(game,archived);
    Write(Path.Combine(root,"profile-archive-"+Path.GetFileName(archived)+".json"),new{archived,game,purpose="Fresh disposable QA profile for native first-launch defaults",personalInstallModified=false});
    Console.WriteLine("QA_PROFILE_ARCHIVED");return;
}

if(command=="refresh-source") {
    if(!File.Exists(marker)||HasLiveQaClient())throw new IOException("Refresh requires a stopped marked QA fixture.");
    var f=Read<Fixture>(marker);var source=Path.GetFullPath(Required("source"));RejectLinks(source);
    var canonical=paths.CombineUnderPacks(f.PackName);var archive=Path.Combine(root,"canonical-revisions",DateTime.UtcNow.ToString("yyyyMMdd-HHmmssfff"));
    if(!IsWithin(canonical,root)||!IsWithin(archive,root))throw new IOException("Refresh escaped fixture.");
    Directory.CreateDirectory(Path.GetDirectoryName(archive)!);Directory.Move(canonical,archive);
    var records=CopyTree(source,canonical,skipInfrastructure:true);
    Write(Path.Combine(root,"source-refresh-"+Path.GetFileName(archive)+".json"),new{source,archivedPreviousCanonical=archive,files=records,treeSha256=TreeHash(records),personalInstanceWritten=false});
    Console.WriteLine("SOURCE_REFRESHED files="+records.Count);return;
}

if (command == "bind-derived")
{
    if(!File.Exists(marker)||HasLiveQaClient())throw new IOException("Derived bind requires a stopped marked fixture.");
    var source=Path.GetFullPath(Required("source"));RejectLinks(source);
    var target=paths.CombineUnderPacks(packName);
    if(Directory.Exists(target))throw new IOException("Use a new pack alias for this QA phase.");
    var records=CopyTree(source,target,skipInfrastructure:true);
    Write(Path.Combine(root,"derived-source-snapshot.json"),new{schemaVersion=1,source,files=records,treeSha256=TreeHash(records),personalWorldsCopied=false});
    File.Copy(marker,Path.Combine(root,"baseline-fixture.json"),overwrite:false);
    Write(marker,new Fixture("baseline",packName,DateTimeOffset.UtcNow));
    Console.WriteLine("DERIVED_BOUND files="+records.Count);return;
}

if (command == "bind")
{
    if (!File.Exists(marker) || HasLiveQaClient()) throw new IOException("Binding requires a prepared stopped QA fixture.");
    var source=Path.GetFullPath(Required("source")); RejectLinks(source);
    var descriptor=PackManifestService.Load(source);
    if(descriptor.MinecraftVersion!="1.21.1" || descriptor.Loader.Type!=PackLoaderKind.Fabric) throw new IOException("Unexpected platform.");
    var oldSnapshot=Path.Combine(root,"source-snapshot.json");
    if(File.Exists(oldSnapshot)) File.Move(oldSnapshot, Path.Combine(root,"platform-snapshot.json"),overwrite:false);
    var canonical=paths.CombineUnderPacks(packName);
    var archived=Path.Combine(root,"platform-canonical-before-bind");
    if(Directory.Exists(archived)) throw new IOException("Already bound");
    if(!IsWithin(canonical,root)||!IsWithin(archived,root))throw new IOException("Bind path escaped fixture");
    Directory.Move(canonical,archived);
    var records=CopyTree(source,paths.CombineUnderPacks(packName),skipInfrastructure:true);
    Write(oldSnapshot,new{schemaVersion=1,source,files=records,treeSha256=TreeHash(records),personalWorldsCopied=false,bindAfterPlatformCachePreparation=true});
    Console.WriteLine("BOUND files="+records.Count);return;
}

if (command == "prepare-public")
{
    if (Directory.Exists(root)) throw new IOException("Public installation requires a new empty fixture root.");
    var revision = Required("revision");
    if (!System.Text.RegularExpressions.Regex.IsMatch(revision, "^[0-9a-f]{40}$"))
        throw new ArgumentException("Expected the full published pack revision.");
    using var catalog = JsonDocument.Parse(File.ReadAllText(Required("catalog-entry")));
    var entry = catalog.RootElement;
    packName = entry.GetProperty("relativePath").GetString()!;
    RequireComponent(packName);
    var source = entry.GetProperty("source");
    if (source.GetProperty("tag").GetString() != "pack-latest")
        throw new InvalidDataException("The fixture must install the public rolling release.");

    paths.Ensure();
    var canonical = paths.CombineUnderPacks(packName);
    Directory.CreateDirectory(canonical);
    Write(Path.Combine(canonical, "portable-pack-source.json"), new
    {
        schemaVersion = 1,
        owner = source.GetProperty("owner").GetString(),
        repo = source.GetProperty("repo").GetString(),
        tag = "pack-latest"
    });
    Write(marker, new Fixture("integrated", packName, DateTimeOffset.UtcNow));
    var logger = new Logger(paths.LogFile);
    using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(45));
    var sync = new PortablePackSyncService(paths, logger);
    var result = await sync.SyncAsync(packName, Progress(), timeout.Token);
    if (result.Outcome != PackSyncOutcome.Installed || result.Revision != revision || result.BytesDownloaded <= 0)
        throw new InvalidDataException($"Unexpected clean sync result: {result.Outcome} {result.Revision}");
    Write(Path.Combine(root, "public-sync.json"), result);

    using var publicRuntimeService = new PackRuntimeService(paths, logger);
    var runtime = await publicRuntimeService.PrepareAsync(packName, Progress(), timeout.Token);
    if (runtime.Descriptor.ClientJar.Length != 0 || !File.Exists(runtime.ClientJarPath))
        throw new InvalidDataException("Public pack did not use the official-client installation path.");
    if (Directory.Exists(paths.SharedRuntime) &&
        Directory.EnumerateFiles(paths.SharedRuntime, "javaw.exe", SearchOption.AllDirectories).Any())
        throw new InvalidDataException("The clean Fabric runtime downloaded a second Java.");
    using var pins = JsonDocument.Parse(File.ReadAllText(Path.Combine(canonical, "launcher", "runtime-libraries.json")));
    var runtimeLog = File.ReadAllText(paths.LogFile);
    var libraries = new List<object>();
    foreach (var item in pins.RootElement.EnumerateObject())
    {
        var pin = item.Value;
        var coordinate = pin.GetProperty("coordinate").GetString()!;
        var name = Path.GetFileName(new Uri(pin.GetProperty("url").GetString()!).AbsolutePath);
        RequireComponent(name);
        var matches = Directory.GetFiles(runtime.LibrariesRoot, name, SearchOption.AllDirectories);
        var sha256 = pin.GetProperty("sha256").GetString()!;
        if (matches.Length != 1 || Hash(matches[0]) != sha256 ||
            !runtimeLog.Contains($"Verified runtime library from pack delivery: {coordinate}", StringComparison.Ordinal))
            throw new InvalidDataException($"Missing or unverified library in the shared cache: {coordinate}");
        libraries.Add(new { coordinate, sha256, path = Path.GetRelativePath(runtime.LibrariesRoot, matches[0]) });
    }
    if (libraries.Count != 4) throw new InvalidDataException("Expected four reviewed Awakening runtime libraries.");
    Write(Path.Combine(root, "public-runtime-proof.json"), new
    {
        revision, officialClientSha256 = Hash(runtime.ClientJarPath),
        launcherAssemblySha256 = Hash(typeof(PackRuntimeService).Assembly.Location), libraries
    });
    Write(Path.Combine(root, "runtime.json"), runtime);
    Console.WriteLine($"PUBLIC_PREPARED revision={result.Revision} downloaded={result.BytesDownloaded}");
    return;
}

if (command == "prepare")
{
    if (Directory.Exists(root)) throw new IOException("Use a new independent fixture root.");
    var source = Path.GetFullPath(Required("source"));
    RejectLinks(source);
    var mode = options.GetValueOrDefault("mode", "baseline");
    if (mode is not ("baseline" or "integrated")) throw new ArgumentException("Unknown mode.");
    var descriptor = PackManifestService.Load(source);
    if (descriptor.MinecraftVersion != "1.21.1" || descriptor.Loader.Type != PackLoaderKind.Fabric)
        throw new ArgumentException("This native QA agent is limited to Minecraft 1.21.1 Fabric.");
    if (IsWithin(root, source) || IsWithin(source, root)) throw new IOException("Source and fixture must be disjoint.");
    paths.Ensure();
    var destination = paths.CombineUnderPacks(packName);
    var files = CopyTree(source, destination, skipInfrastructure: true);
    Write(Path.Combine(root, "source-snapshot.json"), new
    {
        schemaVersion = 1, copiedAtUtc = DateTimeOffset.UtcNow, source, mode,
        treeSha256 = TreeHash(files), files,
        authorFilesChangedByHarness = false, personalWorldsCopied = false
    });
    var cacheCopies = new List<object>();
    if (options.TryGetValue("cache-root", out var cacheRoot))
    {
        var cachePaths = new AppPaths(Path.GetFullPath(cacheRoot));
        foreach (var pair in new[]
        {
            (cachePaths.SharedRuntime, paths.SharedRuntime),
            (cachePaths.JavaRuntimes, paths.JavaRuntimes),
            (Path.Combine(cachePaths.Launcher, "ManagedComponents"), Path.Combine(paths.Launcher, "ManagedComponents")),
            (cachePaths.CombineUnderRuntimes(packName), paths.CombineUnderRuntimes(packName))
        })
        {
            if (!Directory.Exists(pair.Item1)) continue;
            var records = CopyTree(pair.Item1, pair.Item2, skipInfrastructure: false);
            cacheCopies.Add(new { source = pair.Item1, target = pair.Item2, count = records.Count, treeSha256 = TreeHash(records) });
        }
    }
    Write(marker, new Fixture(mode, packName, DateTimeOffset.UtcNow));
    Write(Path.Combine(root, "cache-copy.json"), cacheCopies);
    var logger = new Logger(paths.LogFile);
    using var runtimes = new PackRuntimeService(paths, logger);
    using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(45));
    var runtime = await runtimes.PrepareAsync(packName, Progress(), timeout.Token);
    Write(Path.Combine(root, "runtime.json"), runtime);
    Console.WriteLine($"PREPARED mode={mode} minecraft={descriptor.MinecraftVersion} loader={descriptor.Loader.Version} files={files.Count}");
    return;
}

if (!File.Exists(marker)) throw new IOException("Not a marked Attack on Titan QA fixture.");
var fixture = Read<Fixture>(marker);
packName = fixture.PackName;
if (command == "prepare-integration")
{
    if (fixture.Mode != "integrated") throw new IOException("Production integration preparation requires an integrated fixture.");
    if (HasLiveQaClient()) throw new IOException("Stop the recorded QA client before preparing its instance.");
    var logger = new Logger(paths.LogFile);
    using var stageRuntimeService = new PackRuntimeService(paths, logger);
    using var stageInstances = new PackInstanceService(paths, logger);
    using var identity = new PortableIdentityAdapterService(paths, logger);
    using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(45));
    var runtime = await stageRuntimeService.PrepareAsync(packName, Progress(), timeout.Token);
    var stageInstance = await stageInstances.PrepareAsync(packName, timeout.Token);
    await new ManagedComponentService(paths, logger).EnsureSteamTransportModAsync(stageInstance, runtime.Descriptor, timeout.Token);
    var adapterArguments = await identity.PrepareJvmArgumentsAsync(runtime, stageInstance.GameDirectory, timeout.Token);
    if (!adapterArguments.Any(argument => argument.StartsWith("-javaagent:", StringComparison.Ordinal)))
        throw new IOException("Production identity/LAN adapter was not prepared.");
    Write(Path.Combine(root, "integration-preparation.json"), new
    {
        preparedAtUtc = DateTimeOffset.UtcNow, runtime.Descriptor, runtime.ProfileId,
        launcherAssemblySha256 = Hash(typeof(PackRuntimeService).Assembly.Location),
        productionAdapterPrepared = true, e4steamPrepared = true, gameDirectory = stageInstance.GameDirectory,
        mods = Snapshot(stageInstance.GameDirectory).Where(file => file.Path.StartsWith("mods/", StringComparison.OrdinalIgnoreCase)),
        gameStarted = false
    });
    Console.WriteLine("INTEGRATION_PREPARED gameStarted=false");
    return;
}
var evidence = Path.Combine(root, "evidence", label);
if (command is "ready" or "stop" or "audit")
{
    var run = Read<Run>(Path.Combine(evidence, "run.json"));
    if (command == "audit")
    {
        // Keep full logs local. The bounded report contains exact error families,
        // timestamps and evidence flags; it does not assert visual/Steam PASS.
        var errors = new List<object>();
        var errorFamilies = new Dictionary<string, ErrorFamily>(StringComparer.Ordinal);
        long errorEventCount = 0;
        var log = Path.Combine(run.GameDirectory, "logs", "latest.log");
        if (File.Exists(log))
            foreach (var line in ReadSharedLines(log))
                if (line.Contains("/ERROR]") || line.Contains("/FATAL]"))
                {
                    errorEventCount++;
                    if (errors.Count < 300) errors.Add(new { line = line.Length > 1200 ? line[..1200] : line });
                    var firstBracket = line.IndexOf(']');
                    var family = firstBracket >= 0 ? line[(firstBracket + 1)..].TrimStart() : line;
                    // Thread names vary; retain severity/logger/message verbatim.
                    var threadEnd = family.IndexOf("]: ", StringComparison.Ordinal);
                    if (threadEnd >= 0)
                    {
                        var severityStart = family.LastIndexOf('/', threadEnd);
                        if (severityStart >= 0) family = family[severityStart..];
                    }
                    var time = firstBracket >= 0 ? line[..(firstBracket + 1)] : "unknown";
                    if (errorFamilies.TryGetValue(family, out var previous))
                        errorFamilies[family] = previous with { Count = previous.Count + 1, LastTime = time };
                    else if (errorFamilies.Count < 1000)
                        errorFamilies[family] = new ErrorFamily(family, 1, time, time);
                }
        var result = new
        {
            mode = fixture.Mode, label, checkedAtUtc = DateTimeOffset.UtcNow,
            processStillRunning = IsRunning(run.ProcessId), latestLogExists = File.Exists(log),
            errorEvents = errors, errorEventCount, errorFamilies = errorFamilies.Values,
            errorReportLimit = 300, uniqueErrorFamilyLimit = 1000,
            observations = ReadJsonLines(Path.Combine(evidence, "observations.jsonl")),
            exit = File.Exists(Path.Combine(evidence, "exit.json")) ? JsonDocument.Parse(File.ReadAllText(Path.Combine(evidence, "exit.json"))).RootElement.Clone() : (JsonElement?)null,
            actualSteamGuestConnectionVerified = false, overlayVerified = false, visualQualityVerified = false
        };
        Write(Path.Combine(evidence, "audit.json"), result);
        Console.WriteLine($"AUDIT running={result.processStillRunning} errorsCaptured={errors.Count}");
    }
    else
    {
        if (!IsRunning(run.ProcessId)) throw new IOException("The recorded QA process has exited.");
        File.WriteAllText(Path.Combine(evidence, command == "ready" ? "ready.flag" : "stop.flag"), DateTimeOffset.UtcNow.ToString("O"));
        Console.WriteLine(command == "ready" ? "READY_MARKER_WRITTEN" : "NATIVE_SHUTDOWN_REQUESTED");
    }
    return;
}
if (command != "launch") throw new ArgumentException("Unknown command.");
if (Directory.Exists(evidence)) throw new IOException("Use a new evidence label.");
Directory.CreateDirectory(Path.Combine(root, "evidence"));
if (HasLiveQaClient())
    throw new IOException("This fixture already has a live QA process.");
var heap = int.Parse(options.GetValueOrDefault("heap", "10240"), CultureInfo.InvariantCulture);
if (heap is < 4096 or > 24576) throw new ArgumentException("Heap must be 4096..24576 MiB.");
var player = options.GetValueOrDefault("player", "AotQa");
if (!System.Text.RegularExpressions.Regex.IsMatch(player, "^[A-Za-z0-9_]{3,16}$")) throw new ArgumentException("Invalid QA player name.");
if(options.TryGetValue("server",out var requestedServer) && !System.Text.RegularExpressions.Regex.IsMatch(requestedServer,@"^127\.0\.0\.1:[0-9]{1,5}$"))
    throw new ArgumentException("Private multiplayer QA accepts only an explicit loopback target.");
var world = options.GetValueOrDefault("world");
var freshWorld = options.GetValueOrDefault("fresh-world");
if (world is not null && freshWorld is not null) throw new ArgumentException("Use --world or --fresh-world.");
if(options.ContainsKey("server")&&(world is not null||freshWorld is not null))throw new ArgumentException("Use a world or a loopback guest target, never both.");
foreach (var name in new[] { world, freshWorld }.OfType<string>())
    if (!System.Text.RegularExpressions.Regex.IsMatch(name, "^AotQa[A-Za-z0-9_-]{0,48}$")) throw new ArgumentException("Only reserved disposable AotQa worlds are accepted.");
var agent = Path.GetFullPath(Required("agent"));
RejectLinks(agent);
if (!File.Exists(agent)) throw new FileNotFoundException("Build the test-only QA agent first.", agent);
Directory.CreateDirectory(evidence);
var launchLogger = new Logger(paths.LogFile);
using var runtimeService = new PackRuntimeService(paths, launchLogger);
using var preparing = new CancellationTokenSource(TimeSpan.FromMinutes(45));
var prepared = await runtimeService.PrepareAsync(packName, Progress(), preparing.Token);
using var instances = new PackInstanceService(paths, launchLogger);
PackInstanceContext instance;
var integrationArguments = new List<string>();
if (fixture.Mode == "integrated")
{
    instance = await instances.PrepareAsync(packName, preparing.Token);
    await new ManagedComponentService(paths, launchLogger).EnsureSteamTransportModAsync(instance, prepared.Descriptor, preparing.Token);
    using var identity = new PortableIdentityAdapterService(paths, launchLogger);
    integrationArguments.AddRange(await identity.PrepareJvmArgumentsAsync(prepared, instance.GameDirectory, preparing.Token));
    if (!integrationArguments.Any(arg => arg.StartsWith("-javaagent:", StringComparison.Ordinal)))
        throw new IOException("Integrated fixture did not prepare the production identity/LAN adapter.");
}
else
{
    var game = paths.CombineUnderInstances(packName);
    if (!Directory.Exists(game)) CopyTree(paths.CombineUnderPacks(packName), game, skipInfrastructure: true);
    instance = new PackInstanceContext(paths.CombineUnderPacks(packName), game, prepared.ClientJarPath);
}
if (world is not null && !File.Exists(Path.Combine(instance.GameDirectory, "saves", world, "level.dat")))
    throw new IOException("QuickPlay requires an existing disposable QA world created and saved in this fixture.");
if (freshWorld is not null && Directory.Exists(Path.Combine(instance.GameDirectory, "saves", freshWorld)))
    throw new IOException("Fresh QA world already exists.");
Directory.CreateDirectory(Path.Combine(instance.GameDirectory, "saves"));
Write(Path.Combine(evidence, "game-before-launch.json"), Snapshot(instance.GameDirectory));
var launcher = runtimeService.CreateLocalLauncher(prepared);
var profile = await launcher.GetVersionAsync(prepared.ProfileId, preparing.Token);
var launchPath = new MinecraftPath(instance.GameDirectory)
{
    Library = launcher.MinecraftPath.Library, Versions = launcher.MinecraftPath.Versions,
    Assets = launcher.MinecraftPath.Assets, Resource = launcher.MinecraftPath.Resource, Runtime = launcher.MinecraftPath.Runtime
};
launchPath.CreateDirs();
var home = Path.Combine(root, "java-home");
var temporary = Path.Combine(root, "java-temp", label);
Directory.CreateDirectory(home); Directory.CreateDirectory(temporary);
var session = MSession.CreateOfflineSession(player);
var uuidBytes = MD5.HashData(Encoding.UTF8.GetBytes("OfflinePlayer:" + player));
uuidBytes[6] = (byte)((uuidBytes[6] & 15) | 48); uuidBytes[8] = (byte)((uuidBytes[8] & 63) | 128);
session.UUID = new Guid(uuidBytes, bigEndian: true).ToString("N");
if(options.TryGetValue("player-uuid",out var fixedUuid)) {
    if(!Guid.TryParse(fixedUuid,out var parsed))throw new ArgumentException("Invalid explicit QA UUID.");
    session.UUID=parsed.ToString("N");
}

if(fixture.Mode=="integrated" && world is not null){
    var id=Guid.Parse(session.UUID);
    var launchIdentity=new LocalIdentityContext{IdentityName=player,MinecraftUuid=id.ToString("D"),PlayerUuid=id,
        SteamId64=options.TryGetValue("qa-steam-id",out var idText)?SteamId64.Parse(idText):default,SessionAccessToken=session.AccessToken??""};
    new WorldPlayerProfileService(paths,launchLogger).PrepareWorldForLaunch(Path.Combine(instance.GameDirectory,"saves",world),launchIdentity);
}
var useSelectedSkin = options.ContainsKey("skin-from-install-root") || options.ContainsKey("skin-path");
if (options.ContainsKey("skin-from-install-root") && options.ContainsKey("skin-path"))
    throw new ArgumentException("Use --skin-from-install-root or --skin-path, not both.");
if(options.ContainsKey("peer-skin-path")&&!useSelectedSkin)throw new ArgumentException("Peer skin lab requires an explicit own reference.");
await using var skinPeerLab = options.ContainsKey("peer-skin-path") ? new AotQa.Identity.SkinPeerLab(paths,launchLogger) : null;
await using var skinService = useSelectedSkin ? new SkinService(paths, launchLogger, skinPeerLab?.Transport ?? new NullPeerTransport()) : null;
string? skinRegistryPath = null;
if (skinService is not null)
{
    // Read no personal settings by default. With explicit opt-in only SkinPath
    // is consumed; no source settings, identity, or chosen PNG is written.
    string selectedSkin;
    if (options.TryGetValue("skin-path", out var explicitSkin)) selectedSkin = Path.GetFullPath(explicitSkin);
    else
    {
        var selectedInstall = new AppPaths(Path.GetFullPath(Required("skin-from-install-root")));
        using var selectedSettings = JsonDocument.Parse(File.ReadAllText(Path.Combine(selectedInstall.Personal, "settings.json")));
        var skinPathSetting = selectedSettings.RootElement.EnumerateObject().FirstOrDefault(property =>
            property.Name.Equals("skinPath", StringComparison.OrdinalIgnoreCase));
        var value = skinPathSetting.Value.ValueKind == JsonValueKind.String ? skinPathSetting.Value.GetString() : null;
        if (string.IsNullOrWhiteSpace(value)) throw new IOException("The opted-in installation has no selected skin.");
        selectedSkin = Path.GetFullPath(value, selectedInstall.Root);
    }
    if (!File.Exists(selectedSkin)) throw new IOException("The opted-in selected skin is unavailable.");
    var selectedHash = Hash(selectedSkin);
    var copiedSkin = Path.Combine(evidence, "selected-skin.png");
    File.Copy(selectedSkin, copiedSkin, overwrite: false);
    if (Hash(copiedSkin) != selectedHash || Hash(selectedSkin) != selectedHash)
        throw new IOException("The selected skin changed during its independent copy.");
    await skinService.StartAsync(preparing.Token);
    var skinSettings = new AppSettings { SkinPath = copiedSkin, PlayerName = player };
    var skinIdentity = new LocalIdentityContext
    {
        IdentityName = player, MinecraftUuid = MinecraftProcessService.PlayingHere(session.UUID),
        PlayerUuid = Guid.Parse(session.UUID), SessionAccessToken = session.AccessToken ?? ""
    };
    skinRegistryPath = skinService.PrepareRegistry(skinSettings, skinIdentity);
    var announcement = skinService.GetAnnouncement(skinSettings, skinIdentity.MinecraftUuid);
    if (!announcement.IsAvailable || !File.Exists(skinRegistryPath))
        throw new IOException("The production skin service did not register the selected skin.");
    var entry = File.ReadAllLines(skinRegistryPath).Single(line =>
        line.StartsWith(skinIdentity.MinecraftUuid + "|", StringComparison.OrdinalIgnoreCase)).Split('|');
    if (entry.Length != 4 || !Uri.TryCreate(entry[3], UriKind.Absolute, out var textureUri) ||
        textureUri.Scheme != "http" || textureUri.Host != "127.0.0.1")
        throw new IOException("The production skin registry is not the expected loopback endpoint.");
    using var skinHttp = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
    var servedSkin = await skinHttp.GetByteArrayAsync(textureUri, preparing.Token);
    var servedHash = Convert.ToHexString(SHA256.HashData(servedSkin)).ToLowerInvariant();
    if (servedHash != selectedHash || !announcement.Sha256.Equals(selectedHash, StringComparison.OrdinalIgnoreCase) ||
        !entry[1].Equals(selectedHash, StringComparison.OrdinalIgnoreCase) || entry[2] != announcement.Model ||
        !servedSkin.AsSpan().SequenceEqual(File.ReadAllBytes(copiedSkin)))
        throw new IOException("Production loopback skin bytes/hash/model differ from the selected PNG.");
    Write(Path.Combine(evidence, "skin-evidence.json"), new
    {
        exists = true, sha256 = selectedHash, model = announcement.Model, sizeBytes = servedSkin.Length,
        loopbackExactBytesVerified = true, sourcePngUnchanged = Hash(selectedSkin) == selectedHash,
        sourceSettingsWritten = false, peerTransport = "NullPeerTransport", actualPeerSkinVerified = false,
        qaOfflineIdentity = true, registryPassedToGame = true,
        serviceLifetime = "Started before launch and held until native game exit by await using."
    });
    if(skinPeerLab is not null){
        var peerUuid=Guid.Parse(Required("peer-uuid"));
        var peerIdentity=new LocalIdentityContext{IdentityName=Required("peer-name"),MinecraftUuid=peerUuid.ToString("D"),PlayerUuid=peerUuid,
            SteamId64=SteamId64.Parse(Required("peer-steam-id")),SessionAccessToken="QA-only"};
        var peerResult=await skinPeerLab.RegisterRemoteAsync(skinService,Path.GetFullPath(Required("peer-skin-path")),peerIdentity,evidence,preparing.Token);
        Write(Path.Combine(evidence,"peer-skin-protocol.json"),peerResult);
    }
    Console.WriteLine($"SKIN_READY exists=true sha256={selectedHash} model={announcement.Model} sizeBytes={servedSkin.Length}");
}
object? preparedNotice=null;
GameNoticeOverlayService? noticeService=null;
if(fixture.Mode=="integrated"){
    noticeService=new GameNoticeOverlayService(paths,launchLogger);
    preparedNotice=typeof(GameNoticeOverlayService).GetMethod("PrepareSession",System.Reflection.BindingFlags.NonPublic|System.Reflection.BindingFlags.Instance)!.Invoke(noticeService,null);
}
using var noticeLifetime=preparedNotice as IDisposable;
var arguments = new List<string>
{
    "-Dfile.encoding=UTF-8", "-Duser.home=" + home, "-Djava.io.tmpdir=" + temporary,
    "-XX:ErrorFile=" + Path.Combine(evidence, "hs_err_pid%p.log"),
    "-Dlanminecraft.qa.root=" + root, "-Dlanminecraft.qa.game=" + instance.GameDirectory,
    "-Dlanminecraft.qa.evidence=" + evidence, "-javaagent:" + agent
};
if (freshWorld is not null) arguments.Add("-Dlanminecraft.qa.freshWorld=" + freshWorld);
if(fixture.Mode=="integrated" && options.TryGetValue("qa-steam-id",out var qaSteamText)) {
    if(options.ContainsKey("identity-registry"))throw new ArgumentException("Use generated or explicit QA identity registry.");
    var portableUuid=Guid.Parse(session.UUID);
    var qaIdentity=new LocalIdentityContext{IdentityName=player,MinecraftUuid=portableUuid.ToString("D"),PlayerUuid=portableUuid,
        SteamId64=SteamId64.Parse(qaSteamText),SessionAccessToken=session.AccessToken??""};
    var registryWriter=new PortableIdentityRegistryService(paths,launchLogger);
    var generatedRegistry=registryWriter.Prepare(qaIdentity);
    if(options.TryGetValue("peer-uuid",out var peerUuidText))registryWriter.ObservePeer(new PeerViewModel{
        PlayerName=Required("peer-name"),MinecraftUuid=Guid.Parse(peerUuidText).ToString("D"),SteamId=SteamId64.Parse(Required("peer-steam-id"))});
    arguments.Add("-Dminecraft.portable.identity.registry="+generatedRegistry);
}
if(options.TryGetValue("identity-registry",out var registry)) {
    registry=Path.GetFullPath(registry);RejectLinks(registry);
    if(!IsWithin(registry,root)||!File.Exists(registry))throw new IOException("Identity registry must be a real private QA fixture file.");
    arguments.Add("-Dminecraft.portable.identity.registry="+registry);
}

foreach(var key in new[]{"codecJar","codecResources","codecAuthorJar","codecClass","probeJar","probeClass","identityProbeJar","identityProbeClass","mapsProbeJar","mapsProbeClass","progressProbeJar","progressProbeClass","pairedProbeJar","pairedProbeClass"})
    if(options.TryGetValue(key,out var value)) arguments.Add("-Dlanminecraft.qa."+key+"="+value);

if (skinRegistryPath is not null) arguments.Add("-Dminecraft.portable.skin.registry=" + skinRegistryPath);
foreach(var key in new[]{"mapsProbe.mode","mapsProbe.receipt","mapsProbe.sentinel"})
    if(options.TryGetValue(key,out var value))arguments.Add("-Daot.qa."+key+"="+value);

if (fixture.Mode == "integrated")
{
    arguments.Add("-Dminecraft.portable.identity.localPlayer=" + MinecraftProcessService.PlayingHere(session.UUID));
    arguments.AddRange(new PackJvmArgumentsService(launchLogger).Load(instance.PackDirectory));
    arguments.AddRange(MinecraftProcessService.HeapTuningArgumentsFor(heap));
    arguments.AddRange(MinecraftProcessService.JavaCompatibilityArguments);
}
arguments.AddRange(integrationArguments);
if(preparedNotice is not null){
    var overlayArguments=(IReadOnlyList<string>)preparedNotice.GetType().GetProperty("JvmArguments",System.Reflection.BindingFlags.NonPublic|System.Reflection.BindingFlags.Instance)!.GetValue(preparedNotice)!;
    arguments.AddRange(overlayArguments);
}

var launchOption = new MLaunchOption
{
    Path = launchPath, JavaPath = prepared.JavaPath, Session = session,
    MaximumRamMb = heap, MinimumRamMb = fixture.Mode == "integrated" ? heap : 1024, GameLauncherName = "LANMinecraft",
    GameLauncherVersion = "1", VersionType = $"{prepared.Descriptor.Loader.Type} {prepared.Descriptor.Loader.Version}",
    FullScreen = false, ScreenWidth = 960, ScreenHeight = 540, QuickPlaySingleplayer = world,
    QuickPlayPath = Path.Combine(evidence, "quickplay.json"), ExtraJvmArguments = arguments.Select(value => new MArgument(value)).ToList(),
    ExtraGameArguments = options.TryGetValue("server",out var serverAddress) ? new[]{new MArgument("--quickPlayMultiplayer"),new MArgument(serverAddress)} : Array.Empty<MArgument>()
};
using var process = launcher.BuildProcess(profile, launchOption);
if(fixture.Mode=="integrated"){
    var complete=typeof(MinecraftProcessService).GetMethod("CompleteJvmArguments",System.Reflection.BindingFlags.NonPublic|System.Reflection.BindingFlags.Static)
        ?? throw new MissingMethodException("Production CompleteJvmArguments is unavailable.");
    object?[] common = [process.StartInfo, profile, instance.PackDirectory, new PackJvmArgumentsService(launchLogger)];
    var parameters = complete.GetParameters();
    complete.Invoke(null, parameters.Length switch
    {
        4 => common,
        5 when parameters[4].ParameterType == typeof(bool) => [..common, true],
        _ => throw new MissingMethodException("Unsupported production CompleteJvmArguments signature.")
    });
}

// Deep independent migration fixtures otherwise exceed CreateProcess' 32767
// character limit. Java 17 reads the same tokens from an ordinary local argfile.
var javaArguments = process.StartInfo.ArgumentList.Count > 0
    ? process.StartInfo.ArgumentList.ToArray() : WindowsArguments.Split(process.StartInfo.Arguments);
var argumentFile = Path.Combine(evidence, "java-arguments.txt");
File.WriteAllLines(argumentFile, javaArguments.Select(JavaArgument), new UTF8Encoding(false));
process.StartInfo.Arguments = "";
process.StartInfo.ArgumentList.Clear();
process.StartInfo.ArgumentList.Add("@" + argumentFile);
process.StartInfo.WorkingDirectory = instance.GameDirectory;
process.StartInfo.UseShellExecute = false; process.StartInfo.CreateNoWindow = true; process.StartInfo.WindowStyle = options.GetValueOrDefault("qa-window","normal")=="hidden" ? ProcessWindowStyle.Hidden : ProcessWindowStyle.Normal;
process.StartInfo.RedirectStandardOutput = true; process.StartInfo.RedirectStandardError = true;
process.StartInfo.StandardOutputEncoding = Encoding.UTF8; process.StartInfo.StandardErrorEncoding = Encoding.UTF8;
process.StartInfo.Environment.Remove("SteamNoOverlayUIDrawing");
if (fixture.Mode == "integrated")
{
    // SteamworksApiFacade.Initialize sets these on the production launcher;
    // reproduce exactly the environment its game child inherits.
    var appId = SteamworksApiFacade.SharedAppId.ToString(CultureInfo.InvariantCulture);
    process.StartInfo.Environment["SteamAppId"] = appId;
    process.StartInfo.Environment["SteamGameId"] = appId;
}
foreach (var pair in new Dictionary<string, string>
{
    ["TEMP"] = temporary, ["TMP"] = temporary,
    ["APPDATA"] = Path.Combine(home, "AppData", "Roaming"), ["LOCALAPPDATA"] = Path.Combine(home, "AppData", "Local")
})
{
    Directory.CreateDirectory(pair.Value); process.StartInfo.Environment[pair.Key] = pair.Value;
}
using var output = new StreamWriter(Path.Combine(evidence, "console.log"), false, new UTF8Encoding(false)) { AutoFlush = true };
var outputGate = new object();
void Record(string? value) { if (value is not null) lock (outputGate) output.WriteLine(value); }
process.OutputDataReceived += (_, e) => Record(e.Data); process.ErrorDataReceived += (_, e) => Record(e.Data);
var started = DateTimeOffset.UtcNow;
process.Start(); process.BeginOutputReadLine(); process.BeginErrorReadLine();
if(preparedNotice is not null)
    preparedNotice.GetType().GetMethod("BindProcess",System.Reflection.BindingFlags.NonPublic|System.Reflection.BindingFlags.Instance,null,new[]{typeof(Process)},null)!.Invoke(preparedNotice,new object[]{process});

Write(Path.Combine(evidence, "run.json"), new Run(process.Id, started, instance.GameDirectory, fixture.Mode, label));
Write(Path.Combine(evidence, "launch-evidence.json"), new
{
    fixture.Mode, player, session.UUID, heapMiB = heap, world, freshWorld,
    localPlayerUuid = fixture.Mode == "integrated" ? MinecraftProcessService.PlayingHere(session.UUID) : null,
    prepared.Descriptor, prepared.ProfileId, qaAgentSha256 = Hash(agent),
    launcherAssemblySha256 = Hash(typeof(PackRuntimeService).Assembly.Location),
    productionAdapterPrepared = fixture.Mode == "integrated", e4steamPrepared = fixture.Mode == "integrated",
    productionNoticeOverlayPrepared = preparedNotice is not null, productionCompleteJvmArgumentsApplied = fixture.Mode=="integrated",
    authorOptionsEditedByHarness = false, ramAdvisoryDisabled = false,
    steamAccountIdentity = false, fullProductionUiLaunchClaimed = false,
    steamAppId = fixture.Mode == "integrated" ? SteamworksApiFacade.SharedAppId : (uint?)null,
    steamGameId = fixture.Mode == "integrated" ? SteamworksApiFacade.SharedAppId : (uint?)null,
    steamOverlayDisabled = process.StartInfo.Environment.ContainsKey("SteamNoOverlayUIDrawing"),
    javaArgumentFileUsed = true,
    selectedSkinPrepared = skinRegistryPath is not null,
    note = "Isolated offline QA identity. Native observation/creation/stop agent is test instrumentation, never a shipped mod."
});
Console.WriteLine($"CLIENT_STARTED pid={process.Id} mode={fixture.Mode} label={label}");
await process.WaitForExitAsync();
if(preparedNotice is not null)
    preparedNotice.GetType().GetMethod("ProcessExited",System.Reflection.BindingFlags.NonPublic|System.Reflection.BindingFlags.Instance)!.Invoke(preparedNotice,null);

Write(Path.Combine(evidence, "exit.json"), new { processId = process.Id, exitCode = process.ExitCode, exitedAtUtc = DateTimeOffset.UtcNow });
var latestNativeLog = Path.Combine(instance.GameDirectory, "logs", "latest.log");
if (File.Exists(latestNativeLog)) File.Copy(latestNativeLog, Path.Combine(evidence, "latest.log"), overwrite: false);
Write(Path.Combine(evidence, "game-after-exit.json"), Snapshot(instance.GameDirectory));
Console.WriteLine("CLIENT_EXIT code=" + process.ExitCode);
Environment.ExitCode = process.ExitCode;

string Required(string key) => options.TryGetValue(key, out var value) ? value : throw new ArgumentException("Missing --" + key);
void Write(string path, object value) { Directory.CreateDirectory(Path.GetDirectoryName(path)!); File.WriteAllText(path, JsonSerializer.Serialize(value, json)); }
T Read<T>(string path) => JsonSerializer.Deserialize<T>(File.ReadAllText(path), json) ?? throw new IOException("Empty evidence.");
bool HasLiveQaClient() => Directory.Exists(Path.Combine(root, "evidence")) &&
    Directory.EnumerateFiles(Path.Combine(root, "evidence"), "run.json", SearchOption.AllDirectories)
        .Select(Read<Run>).Any(run => IsRunning(run.ProcessId));
static IProgress<RuntimePreparationProgress> Progress() => new Progress<RuntimePreparationProgress>(p => Console.WriteLine(p.Stage + ": " + p.Message));
static Dictionary<string, string> ParseOptions(string[] values)
{
    var result = new Dictionary<string, string>(StringComparer.Ordinal);
    for (var i = 0; i < values.Length; i += 2)
    {
        if (!values[i].StartsWith("--", StringComparison.Ordinal) || i + 1 >= values.Length || !result.TryAdd(values[i][2..], values[i + 1]))
            throw new ArgumentException("Options must be unique --name value pairs.");
    }
    return result;
}
static string FixturePath(string path)
{
    var full = Path.GetFullPath(path);
    if (!full.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar).Contains("artifacts", StringComparer.OrdinalIgnoreCase))
        throw new IOException("Fixture must be below an artifacts directory.");
    RejectLinks(full);
    return full;
}
static void RequireComponent(string value)
{
    if (string.IsNullOrWhiteSpace(value) || value is "." or ".." || Path.GetFileName(value) != value || value.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
        throw new ArgumentException("Expected one safe path component.");
}
static void RejectLinks(string path)
{
    for (string? current = Path.GetFullPath(path); current is not null; current = Path.GetDirectoryName(current))
        if ((Directory.Exists(current) || File.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("Linked paths are forbidden in the QA fixture.");
}
static bool IsWithin(string path, string parent) => Path.GetFullPath(path).StartsWith(Path.GetFullPath(parent).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) || string.Equals(Path.GetFullPath(path), Path.GetFullPath(parent), StringComparison.OrdinalIgnoreCase);
static bool IsRunning(int pid) { try { return !Process.GetProcessById(pid).HasExited; } catch (ArgumentException) { return false; } }
static string Hash(string file) { using var stream = File.OpenRead(file); return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant(); }
static List<FileRecord> CopyTree(string source, string destination, bool skipInfrastructure)
{
    RejectLinks(source); RejectLinks(destination); Directory.CreateDirectory(destination);
    var records = new List<FileRecord>();
    var skipped = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
    { ".git", "artifacts", "tools", "library", "logs", "crash-reports", "screenshots", "saves", "playerdata", "xaero", "local", ".mixin.out" };
    void CopyDirectory(string input, string output, bool first)
    {
        foreach (var item in Directory.EnumerateFileSystemEntries(input).Order(StringComparer.Ordinal))
        {
            RejectLinks(item); var name = Path.GetFileName(item);
            if (skipInfrastructure && first && (skipped.Contains(name) || name.StartsWith(".portable-", StringComparison.Ordinal))) continue;
            var target = Path.Combine(output, name);
            if (Directory.Exists(item)) { Directory.CreateDirectory(target); CopyDirectory(item, target, false); }
            else
            {
                File.Copy(item, target, overwrite: false); File.SetLastWriteTimeUtc(target, File.GetLastWriteTimeUtc(item));
                var expected = Hash(item); if (Hash(target) != expected) throw new IOException("Independent copy checksum mismatch.");
                records.Add(new FileRecord(Path.GetRelativePath(source, item).Replace('\\', '/'), new FileInfo(item).Length, expected));
            }
        }
    }
    CopyDirectory(source, destination, true);
    return records;
}
static List<FileRecord> Snapshot(string root) => Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories)
    .Where(file => !Path.GetRelativePath(root, file).StartsWith("logs" + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
    .Select(file => { RejectLinks(file); return new FileRecord(Path.GetRelativePath(root, file).Replace('\\', '/'), new FileInfo(file).Length, Hash(file)); }).ToList();
static string TreeHash(IEnumerable<FileRecord> records) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("\n", records.OrderBy(row => row.Path, StringComparer.Ordinal).Select(row => $"{row.Path}\t{row.SizeBytes}\t{row.Sha256}"))))).ToLowerInvariant();
static string JavaArgument(string value) => "\"" + value.Replace("\\", "\\\\", StringComparison.Ordinal)
    .Replace("\"", "\\\"", StringComparison.Ordinal).Replace("\n", "\\n", StringComparison.Ordinal)
    .Replace("\r", "\\r", StringComparison.Ordinal).Replace("\t", "\\t", StringComparison.Ordinal) + "\"";
static List<JsonElement> ReadJsonLines(string path)
{
    var rows = new List<JsonElement>();
    if (!File.Exists(path)) return rows;
    foreach (var line in ReadSharedLines(path).TakeLast(100))
        try { rows.Add(JsonDocument.Parse(line).RootElement.Clone()); } catch (JsonException) { }
    return rows;
}
static IEnumerable<string> ReadSharedLines(string path)
{
    using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
    using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
    while (reader.ReadLine() is { } line) yield return line;
}
record Fixture(string Mode, string PackName, DateTimeOffset CreatedAtUtc);
record FileRecord(string Path, long SizeBytes, string Sha256);
record Run(int ProcessId, DateTimeOffset StartedAtUtc, string GameDirectory, string Mode, string Label);
record ErrorFamily(string Family, long Count, string FirstTime, string LastTime);
static class WindowsArguments
{
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CommandLineToArgvW(string commandLine, out int count);
    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr handle);
    public static string[] Split(string arguments)
    {
        var argv = CommandLineToArgvW("java " + arguments, out var count);
        if (argv == IntPtr.Zero) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        try
        {
            return Enumerable.Range(1, count - 1)
                .Select(index => Marshal.PtrToStringUni(Marshal.ReadIntPtr(argv, index * IntPtr.Size))!).ToArray();
        }
        finally { LocalFree(argv); }
    }
}
