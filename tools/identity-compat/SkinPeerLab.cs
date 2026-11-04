using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using Minecraft;

namespace AotQa.Identity;

/// <summary>
/// QA-only peer exchange through the real SkinService protocol. The stream
/// transport is local TCP; this never signs into or impersonates a Steam user.
/// </summary>
public sealed class SkinPeerLab : IAsyncDisposable
{
    private readonly AppPaths _paths;
    private readonly Logger _logger;
    private readonly LabTransport _transport = new();
    private SkinService? _remote;

    public SkinPeerLab(AppPaths paths, Logger logger)
    {
        _paths = paths; _logger = logger;
        if (!File.Exists(Path.Combine(paths.Root, ".aot-runtime-qa.json")))
            throw new IOException("Peer skin lab requires a marked isolated QA fixture.");
    }

    public IPeerTransport Transport => _transport;

    public async Task<PeerSkinResult> RegisterRemoteAsync(
        SkinService local, string referencePng, LocalIdentityContext peer, string evidenceDirectory, CancellationToken token)
    {
        if (_remote is not null || !peer.SteamId64.IsValid || !Guid.TryParse(peer.MinecraftUuid, out var id))
            throw new InvalidOperationException("One valid synthetic peer is required per lab.");
        var evidence = Path.GetFullPath(evidenceDirectory);
        var prefix = Path.GetFullPath(_paths.Root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!evidence.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            throw new IOException("Skin evidence must remain inside the fixture.");
        var copy = Path.Combine(evidence, "peer-selected-skin.png");
        File.Copy(Path.GetFullPath(referencePng), copy, overwrite: false);
        var expected = Convert.ToHexStringLower(SHA256.HashData(await File.ReadAllBytesAsync(copy, token)));
        var peerPaths = new AppPaths(Path.Combine(_paths.Root, "qa-peer-skin-service"));
        peerPaths.Ensure();
        _remote = new SkinService(peerPaths, _logger, new NullPeerTransport());
        var settings = new AppSettings { SkinPath = copy, PlayerName = peer.IdentityName };
        _remote.PrepareRegistry(settings, peer);
        var announcement = _remote.GetAnnouncement(settings, peer.MinecraftUuid);
        if (!announcement.IsAvailable || !announcement.Sha256.Equals(expected, StringComparison.OrdinalIgnoreCase))
            throw new IOException("Production peer SkinService rejected the QA reference.");
        _transport.Configure(peer.SteamId64, _remote.HandleIncomingAsync);
        local.ObservePeer(new PeerViewModel
        {
            SteamId = peer.SteamId64, PlayerName = peer.IdentityName, MinecraftUuid = id.ToString("D"),
            IsSkinAvailable = true, SkinSha256 = announcement.Sha256, SkinModel = announcement.Model
        });
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(TimeSpan.FromSeconds(20));
        string[]? registered = null;
        while (!timeout.IsCancellationRequested)
        {
            if (File.Exists(_paths.SkinRegistryFile))
                registered = File.ReadAllLines(_paths.SkinRegistryFile).Select(line => line.Split('|', 4))
                    .SingleOrDefault(row => row.Length == 4 && row[0].Equals(id.ToString("D"), StringComparison.OrdinalIgnoreCase));
            if (registered is not null) break;
            await Task.Delay(25, timeout.Token);
        }
        if (registered is null || !registered[1].Equals(expected, StringComparison.OrdinalIgnoreCase))
            throw new IOException("Production peer exchange did not register the peer reference.");
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };
        var served = await http.GetByteArrayAsync(registered[3], timeout.Token);
        var referenceBytes = await File.ReadAllBytesAsync(copy, timeout.Token);
        if (!served.AsSpan().SequenceEqual(referenceBytes))
            throw new IOException("Actual peer loopback endpoint serves different PNG bytes.");
        return new PeerSkinResult(id.ToString("D"), expected, announcement.Model, _transport.SuccessfulExchanges,
            ProductionSkinServiceProtocolUsed: true, RegistryAndHttpBytesVerified: true, ActualSteamTransportVerified: false);
    }

    public async ValueTask DisposeAsync()
    {
        await _transport.DisposeAsync();
        if (_remote is not null) await _remote.DisposeAsync();
    }

    public sealed record PeerSkinResult(string PlayerUuid, string Sha256, string Model, int SuccessfulExchanges,
        bool ProductionSkinServiceProtocolUsed, bool RegistryAndHttpBytesVerified, bool ActualSteamTransportVerified);

    private sealed class LabTransport : IPeerTransport
    {
        private readonly CancellationTokenSource _shutdown = new();
        private readonly ConcurrentBag<Task> _workers = [];
        private TcpListener? _listener;
        private SteamId64 _peer;
        private Func<Stream, byte[], CancellationToken, Task>? _handler;
        private int _successful;
        public int SuccessfulExchanges => Volatile.Read(ref _successful);
        public bool IsAvailable => _listener is not null;
        public string UnavailableReason => IsAvailable ? "" : "QA peer skin endpoint is not configured.";
        public IReadOnlyCollection<SteamId64> ConnectedPeers => IsAvailable ? [_peer] : [];
        public event EventHandler<PeerConnection>? ConnectionAccepted { add { } remove { } }

        public void Configure(SteamId64 peer, Func<Stream, byte[], CancellationToken, Task> handler)
        {
            if (_listener is not null) throw new InvalidOperationException("Lab endpoint already configured.");
            _peer = peer; _handler = handler;
            _listener = new TcpListener(IPAddress.Loopback, 0); _listener.Start();
        }

        public async Task<PeerConnection> ConnectAsync(SteamId64 peer, string protocolName, CancellationToken token)
        {
            if (_listener is null || _handler is null || peer != _peer || protocolName != SkinService.ProtocolName)
                throw new InvalidOperationException("Only the configured QA skin peer is reachable.");
            var client = new TcpClient();
            try
            {
                var accept = _listener.AcceptTcpClientAsync(token);
                await client.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)_listener.LocalEndpoint).Port, token);
                var accepted = await accept;
                _workers.Add(Serve(accepted, _handler));
                return new PeerConnection(PeerConnectionContext.ForPeer(peer, "QA local stream"), client.GetStream());
            }
            catch { client.Dispose(); throw; }
        }

        private async Task Serve(TcpClient accepted, Func<Stream, byte[], CancellationToken, Task> handler)
        {
            using (accepted)
            {
                await using var stream = accepted.GetStream();
                var protocol = typeof(SkinService).Assembly.GetType("Minecraft.PortableProtocol", throwOnError: true)!;
                var reader = protocol.GetMethod("ReadFrameAsync", BindingFlags.Public | BindingFlags.Static)!;
                // Use the production frame reader rather than a duplicate protocol implementation.
                var task = (Task<byte[]>)reader.Invoke(null, [stream, _shutdown.Token, 12 * 1024 * 1024])!;
                var initial = await task;
                await handler(stream, initial, _shutdown.Token);
                Interlocked.Increment(ref _successful);
            }
        }
        public Task StartListeningAsync(CancellationToken token) => Task.CompletedTask;
        public Task StopListeningAsync() { _listener?.Stop(); return Task.CompletedTask; }
        public async ValueTask DisposeAsync()
        {
            await _shutdown.CancelAsync(); _listener?.Stop();
            try { await Task.WhenAll(_workers.ToArray()).WaitAsync(TimeSpan.FromSeconds(5)); }
            catch (OperationCanceledException) { }
            _shutdown.Dispose();
        }
    }
}
