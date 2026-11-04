using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text.Json;
using Minecraft;

if (args.Length != 10) throw new ArgumentException("sourceRoot sourceWorld sourceExitJson destinationRoot destinationWorld destinationExitJson oldName oldSteamID newName newSteamID");
var sourceRoot = Fixture(args[0]);
var destinationRoot = Fixture(args[3]);
if (sourceRoot.Equals(destinationRoot, StringComparison.OrdinalIgnoreCase)) throw new IOException("Host swap requires separate marked fixtures.");
if (sourceRoot.StartsWith(destinationRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)
    || destinationRoot.StartsWith(sourceRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new IOException("Fixture roots must not overlap.");
var sourceWorld = Under(sourceRoot, args[1]);
var destinationWorld = Under(destinationRoot, args[4]);
RequireWorld(sourceWorld);
if (Directory.Exists(destinationWorld) || File.Exists(destinationWorld)) throw new IOException("Destination world already exists; nothing is overwritten.");
RequireStopped(sourceRoot, args[2]);
RequireStopped(destinationRoot, args[5]);
RequireNoLiveQaRuns(sourceRoot); RequireNoLiveQaRuns(destinationRoot);
WorldAccessGuard.EnsureClosed(sourceWorld);
var oldIdentity = Identity(args[6], args[7]);
var newIdentity = Identity(args[8], args[9]);
if (oldIdentity.PlayerUuid == newIdentity.PlayerUuid) throw new IOException("Both host roles must belong to distinct accounts.");
var before = Snapshot(sourceWorld);
var paths = new AppPaths(destinationRoot); paths.Ensure();
var operation = Under(destinationRoot, Path.Combine("qa-world-transfer", Guid.NewGuid().ToString("N")));
Directory.CreateDirectory(operation);
var staging = Under(destinationRoot, Path.Combine(operation, "AotQaTransferStaging"));
Directory.CreateDirectory(staging);
var report = new Dictionary<string, object?> { ["actualSteamTransportVerified"] = false, ["fixtureOnly"] = true,
    ["sourceWorldName"] = Path.GetFileName(sourceWorld), ["destinationWorldName"] = Path.GetFileName(destinationWorld),
    ["oldHostUuid"] = oldIdentity.MinecraftUuid, ["newHostUuid"] = newIdentity.MinecraftUuid,
    ["launcherAssemblySha256"] = Hash(typeof(WorldPlayerProfileService).Assembly.Location) };
try
{
    foreach (var relative in before.Keys)
    {
        if (relative.Equals("session.lock", StringComparison.OrdinalIgnoreCase)) continue;
        var target = Under(staging, relative);
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        File.Copy(Under(sourceWorld, relative), target, overwrite: false);
    }
    WorldAccessGuard.EnsureClosed(sourceWorld);
    Equal(before, Snapshot(sourceWorld), "Source changed during the stopped-world copy");
    var profiles = new WorldPlayerProfileService(paths, new Logger(Path.Combine(operation, "profile-preparation.log")));
    var exported = profiles.PrepareWorldForOutgoingTransfer(staging, oldIdentity);
    profiles.PrepareReceivedWorldForIdentity(staging, newIdentity);
    new WorldPlayerManifestService().Validate(staging);
    var prepared = Snapshot(staging);
    foreach (var pair in before.Where(pair => pair.Key.StartsWith("ftbquests/", StringComparison.OrdinalIgnoreCase)
        || pair.Key.StartsWith("ftbteams/", StringComparison.OrdinalIgnoreCase) || pair.Key.StartsWith("data/", StringComparison.OrdinalIgnoreCase)))
        if (!prepared.TryGetValue(pair.Key, out var value) || value != pair.Value) throw new IOException("Native mod progress changed during profile preparation: " + pair.Key);
    var receipts = new Dictionary<string, string>();
    foreach (var name in new[] { "identity-paired-progress-expected.json", "identity-quests-progress-expected.json" })
    {
        var source = Under(sourceRoot, name);
        if (!File.Exists(source))
        {
            if (name.StartsWith("identity-paired", StringComparison.Ordinal)) throw new IOException("Required paired native progress receipt is absent.");
            continue;
        }
        var target = Under(destinationRoot, name);
        if (File.Exists(target)) throw new IOException("Destination progress receipt already exists.");
        receipts[name] = Hash(source);
    }
    // Both absolute move targets are resolved and bounded inside destinationRoot.
    staging = Under(destinationRoot, staging);
    destinationWorld = Under(destinationRoot, destinationWorld);
    Directory.CreateDirectory(Path.GetDirectoryName(destinationWorld)!);
    Directory.Move(staging, destinationWorld);
    foreach (var pair in receipts)
    {
        File.Copy(Under(sourceRoot, pair.Key), Under(destinationRoot, pair.Key), overwrite: false);
        if (Hash(Under(destinationRoot, pair.Key)) != pair.Value) throw new IOException("Transferred receipt changed.");
    }
    Equal(before, Snapshot(sourceWorld), "Source changed after profile preparation");
    report["status"] = "PREPARED_AWAITING_NATIVE_HOST_ROLE_SWAP";
    report["sourceUnchanged"] = true; report["nativeModProgressFilesUnchanged"] = true;
    report["productionOutgoingAndReceivedProfilePreparation"] = true;
    report["exportedPreviousLevelDatPlayer"] = exported is not null;
    report["sourceFiles"] = before; report["preparedFiles"] = Snapshot(destinationWorld); report["receipts"] = receipts;
    report["nativeReopenOrProgressPassClaimed"] = false;
}
catch (Exception failure)
{
    report["status"] = "FAIL"; report["exception"] = failure.ToString();
    File.WriteAllText(Path.Combine(operation, "report.json"), JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true }));
    throw;
}
File.WriteAllText(Path.Combine(operation, "report.json"), JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true }));
Console.WriteLine("QA_WORLD_TRANSFER_PREPARED " + Path.Combine(operation, "report.json"));

static string Fixture(string path)
{
    var full = Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar);
    if (!File.Exists(Path.Combine(full, ".aot-runtime-qa.json")) || !full.Split(Path.DirectorySeparatorChar).Any(part => part.Equals("artifacts", StringComparison.OrdinalIgnoreCase)))
        throw new IOException("Only marked isolated artifact fixtures are permitted.");
    RejectLinks(full); return full;
}
static string Under(string root, string path)
{
    var full = Path.GetFullPath(Path.IsPathRooted(path) ? path : Path.Combine(root, path));
    if (!full.StartsWith(root.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
        throw new IOException("Path escapes its fixture root.");
    RejectLinks(full); return full;
}
static void RejectLinks(string path)
{
    for (var current = new DirectoryInfo(path); current is not null; current = current.Parent)
        if (current.Exists && (current.Attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("Linked fixture paths are not permitted.");
    if (File.Exists(path) && (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) throw new IOException("Linked fixture files are not permitted.");
}
static void RequireWorld(string path)
{
    if (!Path.GetFileName(path).StartsWith("AotQa", StringComparison.Ordinal) || !File.Exists(Path.Combine(path, "level.dat")))
        throw new IOException("Only an existing disposable AotQa world is permitted.");
}
static void RequireStopped(string root, string receipt)
{
    var path = Under(root, receipt);
    using var json = JsonDocument.Parse(File.ReadAllBytes(path));
    if (json.RootElement.GetProperty("exitCode").GetInt32() != 0) throw new IOException("Normal exitCode0 is required.");
    var id = json.RootElement.GetProperty("processId").GetInt32();
    try { using var process = Process.GetProcessById(id); if (!process.HasExited) throw new IOException("The recorded QA process is still alive."); }
    catch (ArgumentException) { }
    _ = json.RootElement.GetProperty("exitedAtUtc").GetDateTimeOffset();
    var run = Path.Combine(Path.GetDirectoryName(path)!, "run.json");
    using var launch = JsonDocument.Parse(File.ReadAllBytes(run));
    if (launch.RootElement.GetProperty("ProcessId").GetInt32() != id) throw new IOException("Exit receipt does not match its launch.");
    _ = Under(root, launch.RootElement.GetProperty("GameDirectory").GetString()!);
}
static LocalIdentityContext Identity(string name, string steam)
{
    if (name.Length is < 1 or > 16 || name.Any(character => !char.IsAsciiLetterOrDigit(character) && character != '_')) throw new IOException("Unsafe synthetic player name.");
    var account = SteamId64.Parse(steam); var uuid = SteamIdentityDerivation.DeriveMinecraftUuid(account);
    return new LocalIdentityContext { IdentityName = name, SteamId64 = account, MinecraftUuid = uuid.ToString("D"), PlayerUuid = uuid, SessionAccessToken = "QA-only" };
}
static void RequireNoLiveQaRuns(string root)
{
    var evidence = Path.Combine(root, "evidence");
    foreach (var path in Directory.EnumerateFiles(evidence, "run.json", SearchOption.AllDirectories))
    {
        RejectLinks(path); using var json = JsonDocument.Parse(File.ReadAllBytes(path));
        var id = json.RootElement.GetProperty("ProcessId").GetInt32();
        try { using var process = Process.GetProcessById(id); if (!process.HasExited) throw new IOException("A recorded QA game process is still alive; stop both fixtures first."); }
        catch (ArgumentException) { }
    }
}
static SortedDictionary<string, string> Snapshot(string root)
{
    var result = new SortedDictionary<string, string>(StringComparer.Ordinal);
    foreach (var directory in Directory.EnumerateDirectories(root, "*", SearchOption.AllDirectories)) RejectLinks(directory);
    foreach (var path in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
    { RejectLinks(path); result.Add(Path.GetRelativePath(root, path).Replace('\\', '/'), Hash(path)); }
    return result;
}
static string Hash(string path) { using var stream = File.OpenRead(path); return Convert.ToHexStringLower(SHA256.HashData(stream)); }
static void Equal(SortedDictionary<string, string> left, SortedDictionary<string, string> right, string reason)
{ if (left.Count != right.Count || left.Any(pair => !right.TryGetValue(pair.Key, out var value) || value != pair.Value)) throw new IOException(reason); }
