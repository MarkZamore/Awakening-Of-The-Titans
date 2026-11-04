using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Minecraft;

// QA only. Real production services and framing, copied native waypoint bytes,
// synthetic account IDs/session tokens and local TCP. No Steam API or game launch.
internal static class XaeroSyncLab
{
    private const string ExpectedJar = "86bdc4282a73ed55e6a4903a7ba65940a6aed20404f7ab7c96f8d94078accd1b";
    private const string Pack = "qa-xaero-sync";
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    public static async Task<int> Main(string[] arguments)
    {
        string? output = null;
        try
        {
            Require(arguments.Length == 3, "Usage: XaeroSyncLab <frozen-input-directory> <new-output-directory> <pinned-Xaero-jar>");
            var input = Path.GetFullPath(arguments[0]);
            output = Path.GetFullPath(arguments[1]);
            Require(!Directory.Exists(output) && !File.Exists(output), "Output must be a new directory.");
            Require(!output.StartsWith(input.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar,
                StringComparison.OrdinalIgnoreCase), "Output cannot be inside the frozen native input.");
            using (var proof = JsonDocument.Parse(File.ReadAllText(Path.Combine(input, "stopped-source.json"))))
                Require(proof.RootElement.GetProperty("normalExit").GetBoolean() &&
                    proof.RootElement.GetProperty("exitCode").GetInt32() == 0, "A confirmed normal-stop source receipt is required.");
            var jar = Path.GetFullPath(arguments[2]);
            Require(Hash(File.ReadAllBytes(jar)) == ExpectedJar, "Xaero JAR SHA differs from the native probe pin.");
            var receipt = File.ReadAllLines(Path.Combine(input, "maps-receipt.properties"))
                .Where(line => !line.StartsWith('#') && line.Contains('='))
                .Select(line => line.Split('=', 2)).ToDictionary(row => row[0], row => row[1], StringComparer.Ordinal);
            Require(receipt["jarSha256"] == ExpectedJar && receipt["dimension"] == "dannys-aot:paradis", "Wrong native receipt.");
            var frozenWorld = Directory.GetDirectories(Path.Combine(input, "game", "saves")).Single();
            WorldAccessGuard.EnsureClosed(frozenWorld);
            var inputHashes = TreeHashes(input);
            Directory.CreateDirectory(output);

            var hostPaths = Setup(Path.Combine(output, "host"), jar);
            var guestPaths = Setup(Path.Combine(output, "guest"), jar);
            var hostGame = hostPaths.CombineUnderInstances(Pack);
            var guestGame = guestPaths.CombineUnderInstances(Pack);
            var hostWorld = Path.Combine(hostPaths.Worlds, Path.GetFileName(frozenWorld));
            Directory.CreateDirectory(hostWorld);
            foreach (var name in new[] { "level.dat", "xaeromap.txt" })
                File.Copy(Path.Combine(frozenWorld, name), Path.Combine(hostWorld, name));
            CopyTree(Path.Combine(input, "game", "xaero"), Path.Combine(hostGame, "xaero"));
            var provider = new XaeroMinimapWaypointProvider();
            var contextId = provider.ReadWorldContextId(hostWorld) ?? throw new IOException("Native Xaero world ID is missing.");
            var native = provider.Export(new(hostGame, hostWorld, Guid.NewGuid().ToString("D"), contextId, "26.6.0", true, null));
            provider.Validate(native);
            AssertMarker(native, receipt);
            Require(native.Files.Any(file => file.RelativePath.StartsWith("dim%dannys-aot$paradis/", StringComparison.Ordinal)),
                "Actual native snapshot does not retain Paradis' dimension directory.");

            var host = Identity("76561197960265729", "11111111-1111-4111-8111-111111111111", "QaHost");
            var guest = Identity("76561197960265730", "22222222-2222-4222-8222-222222222222", "QaGuest");
            var other = SteamId64.Parse("76561197960265731");
            var hostMarker = XaeroMultiplayerFolderResolver.ToBase36(host.SteamId64.Value);
            var addressA = $"s-{hostMarker}-qa-session-a.steam";
            var addressB = $"s-{hostMarker}-qa-session-b.steam";
            var otherAddress = $"s-{XaeroMultiplayerFolderResolver.ToBase36(other.Value)}-qa-session.steam";
            var worldContextB = contextId == "42" ? "43" : "42";
            var aContext = new WaypointNativeContext(guestGame, "", "", contextId, "26.6.0", false, addressA);
            provider.Import(aContext, native, []);
            var aFolder = XaeroMultiplayerFolderResolver.FindCurrentHostFolder(guestGame, host.SteamId64)!;
            var otherFolder = Path.Combine(XaeroMultiplayerFolderResolver.GetMinimapRoot(guestGame), "Multiplayer_" + otherAddress);
            var worldGuard = Path.Combine(aFolder, "dim%dannys-aot$paradis", $"mw${worldContextB}.txt");
            var hostGuard = Path.Combine(otherFolder, "dim%dannys-aot$paradis", $"mw${contextId}.txt");
            WriteGuard(worldGuard); WriteGuard(hostGuard);
            var guards = new Dictionary<string, string> { [worldGuard] = Hash(File.ReadAllBytes(worldGuard)), [hostGuard] = Hash(File.ReadAllBytes(hostGuard)) };
            var hostMetadata = new WorldMetadataService();
            await using var hostTransport = new NullPeerTransport();
            await using var hostSync = new WaypointSyncService(hostPaths, new Logger(hostPaths.LogFile), hostMetadata, hostTransport);
            await hostSync.PrepareForLaunchAsync(Pack, host, CancellationToken.None);
            using var worldLock = new FileStream(Path.Combine(hostWorld, "session.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
            hostSync.UpdateHostingState(true, Pack, "qa-pack", host);
            var advertisement = hostSync.GetHostAdvertisement() ?? throw new IOException("Production host advertisement is missing.");
            Require(advertisement.Providers.Single().WorldContextId == contextId, "Production host announced the wrong native context.");
            Require(hostSync.Store.ReadSnapshot(hostWorld, guest.MinecraftUuid, provider.ProviderId) is null, "Guest store must begin empty.");
            await using var transport = new LocalTransport(host.SteamId64, hostSync.HandleIncomingAsync);
            var presence = new SteamPeerPresence
            {
                SteamId = host.SteamId64, HostedWorldId = advertisement.WorldId,
                WaypointProtocolVersion = WaypointSyncService.ProtocolVersion, WaypointProviders = advertisement.Providers
            };

            // Initial empty pull is followed by a real production push from A.
            await using (var guestSync = new WaypointSyncService(guestPaths, new Logger(guestPaths.LogFile), new WorldMetadataService(), transport))
            {
                await guestSync.PrepareForLaunchAsync(Pack, guest, CancellationToken.None);
                guestSync.ObservePeer(presence);
                await guestSync.FlushAsync();
                var stored = hostSync.Store.ReadSnapshot(hostWorld, guest.MinecraftUuid, provider.ProviderId)
                    ?? throw new IOException("Production native waypoint push did not reach the world store.");
                Same(native, stored.Snapshot);
            }
            var bFolder = Path.Combine(XaeroMultiplayerFolderResolver.GetMinimapRoot(guestGame), "Multiplayer_" + addressB);
            Directory.CreateDirectory(bFolder);
            Directory.SetLastWriteTimeUtc(aFolder, DateTime.UtcNow.AddMinutes(-5));
            Directory.SetLastWriteTimeUtc(bFolder, DateTime.UtcNow.AddMinutes(5));
            Require(XaeroMultiplayerFolderResolver.FindCurrentHostAddress(guestGame, host.SteamId64) == addressB,
                "Production resolver did not select the new session token.");
            Require(XaeroMultiplayerFolderResolver.FindHostFolders(guestGame, host.SteamId64).Length == 2,
                "Production resolver failed exact-host isolation.");

            // A new service instance reloads persisted per-player sync state.
            await using (var guestSync = new WaypointSyncService(guestPaths, new Logger(guestPaths.LogFile), new WorldMetadataService(), transport))
            {
                await guestSync.PrepareForLaunchAsync(Pack, guest, CancellationToken.None);
                guestSync.ObservePeer(presence);
                await guestSync.FlushAsync();
                foreach (var address in new[] { addressA, addressB })
                {
                    var restored = provider.Export(aContext with { WorldId = advertisement.WorldId, RemoteAddress = address });
                    Same(native, restored); AssertMarker(restored, receipt);
                }
            }
            foreach (var (path, hash) in guards) Require(Hash(File.ReadAllBytes(path)) == hash, "Synchronization changed an unrelated world or host control.");
            Require(TreeHashes(input).OrderBy(pair => pair.Key).SequenceEqual(inputHashes.OrderBy(pair => pair.Key)), "Frozen native input was modified.");
            Require(transport.Exchanges >= 3, "Expected a complete pull/push/restart-pull protocol sequence.");
            var report = new
            {
                status = "PASS", actualSteamTransportVerified = false, realGuestRenderingVerified = false,
                productionProviderResolverAndSync = true, localFramedTcp = true,
                nativeWaypointBytesPreserved = true, sessionTokenChangeAndServiceRestart = true,
                nativeDimensionDirectoryPreserved = true, otherWorldAndHostUnchanged = true,
                additionalNativeDimensionVisitsVerified = false, syntheticNegativeControls = true,
                nativeSourceJarSha256 = ExpectedJar, launcherAssemblySha256 = Hash(File.ReadAllBytes(typeof(WaypointSyncService).Assembly.Location)),
                nativeSnapshotSha256 = WaypointStoreService.ComputeSnapshotHash(native), snapshotFiles = native.Files.Select(file => file.RelativePath),
                nativeInputFiles = inputHashes, transportExchanges = transport.Exchanges, completedAtUtc = DateTimeOffset.UtcNow
            };
            File.WriteAllText(Path.Combine(output, "report.json"), JsonSerializer.Serialize(report, Json));
            Console.WriteLine("XAERO_PRODUCTION_SYNC_SIMULATION_PASS");
            return 0;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine(exception);
            if (output is not null && Directory.Exists(output)) File.WriteAllText(Path.Combine(output, "failure.txt"), exception.ToString());
            return 1;
        }
    }

    private static AppPaths Setup(string root, string jar)
    {
        var paths = new AppPaths(root); paths.Ensure();
        Directory.CreateDirectory(Path.Combine(paths.CombineUnderPacks(Pack), "mods"));
        File.Copy(jar, Path.Combine(paths.CombineUnderPacks(Pack), "mods", Path.GetFileName(jar)));
        Directory.CreateDirectory(paths.CombineUnderInstances(Pack));
        return paths;
    }

    private static LocalIdentityContext Identity(string steam, string uuid, string name) => new()
        { SteamId64 = SteamId64.Parse(steam), MinecraftUuid = uuid, PlayerUuid = Guid.Parse(uuid), IdentityName = name };

    private static void AssertMarker(WaypointSnapshot snapshot, Dictionary<string, string> receipt)
    {
        var rows = snapshot.Files.SelectMany(file => Encoding.UTF8.GetString(file.Content).Split('\n'))
            .Where(line => line.StartsWith("waypoint:" + receipt["sentinel"] + ":", StringComparison.Ordinal)).ToArray();
        Require(rows.Length == 1, "Native marker is missing or duplicated.");
        var row = rows[0].TrimEnd('\r').Split(':');
        Require(row[3] == receipt["x"] && row[4] == receipt["y"] && row[5] == receipt["z"] && row[9] == receipt["setName"],
            "Native waypoint coordinates or set changed.");
    }

    private static void Same(WaypointSnapshot expected, WaypointSnapshot actual)
    {
        Require(expected.WorldContextId == actual.WorldContextId &&
            WaypointStoreService.ComputeSnapshotHash(expected) == WaypointStoreService.ComputeSnapshotHash(actual),
            "Native snapshot paths, dimensions or bytes changed.");
    }

    private static void WriteGuard(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, "# Synthetic QA isolation control, not a native waypoint\n");
    }
    private static string Hash(byte[] bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));
    private static void Require(bool condition, string message) { if (!condition) throw new IOException(message); }
    private static Dictionary<string, string> TreeHashes(string root) => Directory.GetFiles(root, "*", SearchOption.AllDirectories)
        .Order(StringComparer.Ordinal).ToDictionary(path => Path.GetRelativePath(root, path).Replace('\\', '/'), path => Hash(File.ReadAllBytes(path)));
    private static void CopyTree(string source, string destination)
    {
        foreach (var path in Directory.GetFiles(source, "*", SearchOption.AllDirectories))
        {
            var target = Path.Combine(destination, Path.GetRelativePath(source, path));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!); File.Copy(path, target);
        }
    }

    private sealed class LocalTransport(SteamId64 peer, Func<Stream, byte[], CancellationToken, Task> handler) : IPeerTransport
    {
        private readonly TcpListener _listener = Start();
        private readonly CancellationTokenSource _shutdown = new();
        private readonly ConcurrentBag<Task> _workers = [];
        private int _exchanges;
        public int Exchanges => Volatile.Read(ref _exchanges);
        public bool IsAvailable => true;
        public string UnavailableReason => "";
        public IReadOnlyCollection<SteamId64> ConnectedPeers => [peer];
        public event EventHandler<PeerConnection>? ConnectionAccepted { add { } remove { } }
        private static TcpListener Start() { var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); return listener; }
        public async Task<PeerConnection> ConnectAsync(SteamId64 target, string protocol, CancellationToken token)
        {
            Require(target == peer && protocol == WaypointSyncService.ProtocolName, "Unexpected synthetic peer or protocol.");
            var client = new TcpClient();
            try
            {
                var accept = _listener.AcceptTcpClientAsync(token);
                await client.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)_listener.LocalEndpoint).Port, token);
                _workers.Add(Serve(await accept));
                return new PeerConnection(PeerConnectionContext.ForPeer(peer, "QA local stream"), client.GetStream());
            }
            catch { client.Dispose(); throw; }
        }
        private async Task Serve(TcpClient client)
        {
            using (client)
            {
                var protocol = typeof(WaypointSyncService).Assembly.GetType("Minecraft.PortableProtocol", true)!;
                var read = protocol.GetMethod("ReadFrameAsync", BindingFlags.Public | BindingFlags.Static)!;
                var limit = (int)protocol.GetField("MaxJsonFrameBytes", BindingFlags.Public | BindingFlags.Static)!.GetRawConstantValue()!;
                var initial = await (Task<byte[]>)read.Invoke(null, [client.GetStream(), _shutdown.Token, limit])!;
                await handler(client.GetStream(), initial, _shutdown.Token);
                Interlocked.Increment(ref _exchanges);
            }
        }
        public Task StartListeningAsync(CancellationToken token) => Task.CompletedTask;
        public Task StopListeningAsync() => Task.CompletedTask;
        public async ValueTask DisposeAsync()
        {
            await _shutdown.CancelAsync(); _listener.Stop();
            try { await Task.WhenAll(_workers.ToArray()).WaitAsync(TimeSpan.FromSeconds(5)); }
            catch (OperationCanceledException) { }
            _shutdown.Dispose();
        }
    }
}
