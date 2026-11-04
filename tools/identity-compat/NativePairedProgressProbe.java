package dev.lanminecraft.aotqa.identity;

import static dev.lanminecraft.aotqa.identity.NativeProgressProbe.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native server-side host/guest progress proof in an explicitly armed QA world. */
public final class NativePairedProgressProbe {
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static volatile boolean complete;
    private NativePairedProgressProbe() { }

    public static void tick(Object client, Object server, Object player, String report) throws Exception {
        if (complete || server == null || !busy.compareAndSet(false, true)) return;
        Path root = Path.of(System.getProperty("lanminecraft.qa.root")).toAbsolutePath().normalize();
        Path evidence = Path.of(System.getProperty("lanminecraft.qa.evidence")).toAbsolutePath().normalize();
        require(Files.isRegularFile(root.resolve(".aot-runtime-qa.json")), "Unmarked QA fixture");
        require(evidence.startsWith(root) && Path.of(report).toAbsolutePath().normalize().getParent().equals(evidence), "Unexpected report path");
        boolean seed = Files.isRegularFile(evidence.resolve("paired-progress-seed.flag"));
        boolean verify = Files.isRegularFile(evidence.resolve("paired-progress-verify.flag"));
        boolean swap = Files.isRegularFile(evidence.resolve("paired-progress-swap.flag"));
        if (seed == verify) { busy.set(false); return; }
        require(!swap || verify, "Host swap is a verification phase");
        ((Executor) server).execute(() -> {
            var setup = new LinkedHashMap<String, Object>();
            try {
                ClassLoader loader = server.getClass().getClassLoader();
                List<?> players = (List<?>) call(call(server, "method_3760"), "method_14571");
                if (players.size() == 1) { busy.set(false); return; } // Wait for the real guest.
                require(players.size() == 2, "Exactly two actual QA ServerPlayers are required");
                UUID local = UUID.fromString(System.getProperty("minecraft.portable.identity.localPlayer"));
                require(local.equals(call(player, "method_5667")), "Client ownership differs from launch UUID");
                var registered = new TreeMap<String, String>();
                for (String line : Files.readAllLines(Path.of(System.getProperty("minecraft.portable.identity.registry")), StandardCharsets.UTF_8)) {
                    String[] fields = line.split("\\|", 3);
                    if (fields.length >= 2) {
                        String id = UUID.fromString(fields[1]).toString();
                        require(registered.put(id, fields[0]) == null, "Duplicate portable UUID in registry");
                    }
                }
                require(registered.size() == 2 && registered.containsKey(local.toString()), "Two distinct registered portable identities required");
                var actual = new TreeMap<String, Object>();
                var names = new TreeMap<String, String>();
                for (Object target : players) {
                    String id = String.valueOf(call(target, "method_5667"));
                    String name = String.valueOf(call(call(target, "method_7334"), "getName"));
                    require(name.matches("[A-Za-z0-9_]{1,16}"), "Unsafe native fixture player name");
                    require(name.equals(registered.get(id)), "Native player does not match its registered account");
                    require(actual.put(id, target) == null, "Native players share a UUID");
                    names.put(id, name);
                }
                require(actual.keySet().equals(registered.keySet()), "Native guest ownership differs from registered accounts");
                Path expectedFile = root.resolve("identity-paired-progress-expected.json");
                if (seed) {
                    require(!Files.exists(expectedFile), "Paired seed already exists; retain prior evidence");
                    var commandResults = new LinkedHashMap<String, Object>();
                    setup.put("commandResults", commandResults);
                    for (String id : actual.keySet()) {
                        boolean host = id.equals(local.toString());
                        String name = names.get(id);
                        for (String text : List.of("gamemode creative " + name,
                                "give " + name + " minecraft:diamond " + (host ? 7 : 11),
                                "experience set " + name + (host ? " 13 levels" : " 17 levels"),
                                "daot bloodline set " + name + " eldian",
                                "daot shifter set " + name + (host ? " attack" : " armored"))) {
                            if (text.startsWith("give ")) commandResults.put("clear " + name, hasItems(actual.get(id))
                                    ? command(server, "clear " + name) : "SKIPPED_NATIVE_INVENTORY_ALREADY_EMPTY");
                            commandResults.put(text, command(server, text));
                        }
                    }
                    var observed = snapshots(loader, actual);
                    setup.put("players", observed);
                    write(loader, evidence.resolve("identity-paired-progress-setup.json"), setup);
                    for (String id : actual.keySet()) {
                        boolean host = id.equals(local.toString());
                        Map<String, Object> row = observed.get(id);
                        require(number(row, "diamondCount") == (host ? 7 : 11), "Native diamond setup failed: " + id);
                        require(number(row, "experienceLevel") == (host ? 13 : 17), "Native XP setup failed: " + id);
                        require("ELDIAN".equals(row.get("bloodline")), "Native bloodline setup failed: " + id);
                        require(Boolean.TRUE.equals(row.get(host ? "attackPower" : "armoredPower")), "Native Titan assignment not observed: " + id);
                        require(Boolean.FALSE.equals(row.get(host ? "armoredPower" : "attackPower")), "Player also holds the other QA Titan: " + id);
                    }
                    var expected = new LinkedHashMap<String, Object>();
                    expected.put("schemaVersion", 1); expected.put("seedHostUuid", local.toString());
                    expected.put("seedEvidenceLabel", evidence.getFileName().toString());
                    expected.put("seedPlayerNames", names); expected.put("players", observed);
                    write(loader, expectedFile, expected);
                    var result = new LinkedHashMap<>(expected);
                    result.put("status", "SEEDED_AWAITING_NORMAL_SAVE_AND_REJOIN");
                    result.put("actualSteamTransportVerified", false);
                    result.put("fixtureOnly", true);
                    write(loader, evidence.resolve("identity-paired-progress-report.json"), result);
                } else {
                    Map<?, ?> expected = read(loader, expectedFile);
                    require(number(expected, "schemaVersion") == 1, "Unknown paired progress receipt");
                    require(!evidence.getFileName().toString().equals(expected.get("seedEvidenceLabel")), "Verification needs another native launch");
                    boolean changedHost = !local.toString().equals(expected.get("seedHostUuid"));
                    require(swap == changedHost, "Local host role differs from this verification phase");
                    Map<?, ?> oldNames = (Map<?, ?>) expected.get("seedPlayerNames");
                    Map<?, ?> oldRows = (Map<?, ?>) expected.get("players");
                    require(actual.keySet().equals(oldRows.keySet()), "Paired receipt belongs to different players");
                    var observed = snapshots(loader, actual);
                    for (String id : actual.keySet()) {
                        require(!names.get(id).equals(oldNames.get(id)), "Each account must rejoin under a different nickname: " + id);
                        Map<?, ?> before = (Map<?, ?>) oldRows.get(id);
                        Map<String, Object> after = observed.get(id);
                        for (String key : Set.of("diamondCount", "experienceLevel"))
                            require(number(after, key) == number(before, key), "Native reloaded progress changed: " + id + "/" + key);
                        for (String key : Set.of("bloodline", "attackPower", "armoredPower"))
                            require(after.get(key).equals(before.get(key)), "Native reloaded DAOT progress changed: " + id + "/" + key);
                    }
                    var result = new LinkedHashMap<String, Object>();
                    result.put("status", "PASS"); result.put("players", observed);
                    result.put("seedPlayerNames", oldNames); result.put("rejoinPlayerNames", names);
                    result.put("seedHostUuid", expected.get("seedHostUuid")); result.put("currentHostUuid", local.toString());
                    result.put("nativeServerPlayerCount", 2); result.put("stableDistinctUuidChangedNicknames", true);
                    result.put("normalSaveAndRejoinVerified", true); result.put("bothLocalHostRolesObserved", changedHost);
                    result.put("actualSteamTransportVerified", false); result.put("worldCopiedOrNbtEditedByProbe", false);
                    result.put("allModProgressFormatsExhaustivelyVerified", false);
                    result.put("scope", "Actual host and guest ServerPlayer inventory/XP and author DAOT PersistentState after normal save and native rejoin; optional stopped synthetic world host-role swap. Local TCP does not prove Steam transport.");
                    write(loader, evidence.resolve("identity-paired-progress-report.json"), result);
                }
                complete = true;
            } catch (Throwable failure) {
                try {
                    setup.put("status", "FAIL");
                    var causes = new ArrayList<String>();
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) causes.add(cause.toString());
                    setup.put("exceptions", causes);
                    write(server.getClass().getClassLoader(), evidence.resolve("identity-paired-progress-report.json"), setup);
                } catch (Throwable ignored) { }
                complete = true;
            } finally { busy.set(false); }
        });
    }

    private static Map<String, Map<String, Object>> snapshots(ClassLoader loader, Map<String, Object> actual) throws Exception {
        var result = new TreeMap<String, Map<String, Object>>();
        Object armor = Class.forName("daot.TitanPowerType", false, loader).getField("ARMORED").get(null);
        Class<?> powerType = Class.forName("daot.TitanPowerData", false, loader);
        for (var entry : actual.entrySet()) {
            Object world = call(entry.getValue(), "method_37908");
            var row = snapshot(loader, entry.getValue(), world);
            Object data = staticCall(powerType, "get", world);
            row.put("armoredPower", call(data, "playerHoldsPower", UUID.fromString(entry.getKey()), armor));
            result.put(entry.getKey(), row);
        }
        return result;
    }
    private static int number(Map<?, ?> row, String key) { return ((Number) row.get(key)).intValue(); }
}
