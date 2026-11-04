package dev.lanminecraft.aotqa.identity;

import static dev.lanminecraft.aotqa.identity.NativeProgressProbe.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Existing author FTB task progress through native commands and team APIs. */
public final class NativeQuestsProgressProbe {
    private static final String TASK = "79EEEC6BA7256200";
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static volatile boolean complete;
    private NativeQuestsProgressProbe() { }

    public static void tick(Object client, Object server, Object player, String report) throws Exception {
        if (complete || server == null || !busy.compareAndSet(false, true)) return;
        Path root = Path.of(System.getProperty("lanminecraft.qa.root")).toAbsolutePath().normalize();
        Path evidence = Path.of(System.getProperty("lanminecraft.qa.evidence")).toAbsolutePath().normalize();
        require(Files.isRegularFile(root.resolve(".aot-runtime-qa.json")), "Unmarked QA fixture");
        require(evidence.startsWith(root) && Path.of(report).toAbsolutePath().normalize().getParent().equals(evidence), "Unexpected report path");
        boolean seed = Files.isRegularFile(evidence.resolve("quests-seed.flag"));
        boolean verify = Files.isRegularFile(evidence.resolve("quests-verify.flag"));
        boolean swap = Files.isRegularFile(evidence.resolve("quests-swap.flag"));
        if (seed == verify) { busy.set(false); return; }
        require(!swap || verify, "Quest host swap is a verification phase");
        ((Executor) server).execute(() -> {
            var setup = new LinkedHashMap<String, Object>();
            try {
                ClassLoader loader = server.getClass().getClassLoader();
                List<?> players = (List<?>) call(call(server, "method_3760"), "method_14571");
                if (players.size() == 1) return;
                require(players.size() == 2, "Wait for exactly two actual QA ServerPlayers");
                String local = UUID.fromString(System.getProperty("minecraft.portable.identity.localPlayer")).toString();
                require(local.equals(String.valueOf(call(player, "method_5667"))), "Native local ownership mismatch");
                var registered = new TreeMap<String, String>();
                for (String line : Files.readAllLines(Path.of(System.getProperty("minecraft.portable.identity.registry")), StandardCharsets.UTF_8)) {
                    String[] row = line.split("\\|", 3);
                    if (row.length >= 2) require(registered.put(UUID.fromString(row[1]).toString(), row[0]) == null, "Duplicate registered UUID");
                }
                require(registered.size() == 2 && registered.containsKey(local), "Two distinct known portable accounts required");
                var actual = new TreeMap<String, Object>();
                var names = new TreeMap<String, String>();
                for (Object target : players) {
                    String id = String.valueOf(call(target, "method_5667"));
                    String name = String.valueOf(call(call(target, "method_7334"), "getName"));
                    require(name.matches("[A-Za-z0-9_]{1,16}") && name.equals(registered.get(id)), "Unsafe or unregistered native profile");
                    require(actual.put(id, target) == null, "Duplicate native player ownership");
                    require(itemCount(loader, target, "immersive_aircraft:biplane") == 0, "Biplane sentinel could auto-complete from inventory");
                    names.put(id, name);
                }
                require(actual.keySet().equals(registered.keySet()), "Native quest accounts differ from registry");
                Class<?> fileType = Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile", false, loader);
                Object questFile = fileType.getField("INSTANCE").get(null);
                require(questFile != null, "Native server quest file absent");
                Object task = questFile.getClass().getMethod("getTask", long.class).invoke(questFile, Long.parseUnsignedLong(TASK, 16));
                require(task != null, "Pinned author biplane task missing");
                Path expectedFile = root.resolve("identity-quests-progress-expected.json");
                if (seed) {
                    require(!Files.exists(expectedFile), "Quest seed receipt already exists");
                    var before = snapshots(loader, actual, task);
                    for (var row : before.values())
                        require(((Number) row.get("progress")).longValue() == 0 && Boolean.FALSE.equals(row.get("completed")), "Existing author sentinel is already complete");
                    var results = new LinkedHashMap<String, Object>();
                    setup.put("commandResults", results); setup.put("before", before);
                    Object dispatcher = dispatcher(server);
                    Object console = call(server, "method_3739");
                    Object source = call(actual.get(local), "method_5671");
                    Object ftb = call(call(dispatcher, "getRoot"), "getChild", "ftbquests");
                    require(ftb != null, "Native ftbquests command not registered");
                    var tree = new ArrayList<Map<String, Object>>();
                    for (Object node : (java.util.Collection<?>) call(ftb, "getChildren")) tree.add(Map.of(
                            "name", call(node, "getName"), "consoleAllowed", call(node, "canUse", console),
                            "actualHostPlayerAllowed", call(node, "canUse", source)));
                    setup.put("nativeFtbCommandTree", tree);
                    write(loader, evidence.resolve("identity-quests-command-tree.json"), setup);
                    Object change = call(ftb, "getChild", "change_progress");
                    require(change != null && Boolean.TRUE.equals(call(change, "canUse", source)), "Native FTB editor permission rejected actual QA host");
                    require(Boolean.TRUE.equals(call(source, "method_43737")) && Boolean.TRUE.equals(call(source, "method_9259", 2)), "FTB sentinel requires the real player/operator source");
                    for (String name : names.values()) {
                        String text = "ftbquests change_progress " + name + " complete " + TASK;
                        results.put(text, call(dispatcher, "execute", text, source));
                    }
                    var observed = snapshots(loader, actual, task);
                    setup.put("players", observed);
                    write(loader, evidence.resolve("identity-quests-progress-setup.json"), setup);
                    for (var row : observed.values()) require(((Number) row.get("progress")).longValue() == ((Number) row.get("maxProgress")).longValue()
                            && Boolean.TRUE.equals(row.get("completed")), "Native FTB command did not complete the author task");
                    var expected = new LinkedHashMap<String, Object>();
                    expected.put("schemaVersion", 1); expected.put("taskId", TASK);
                    expected.put("seedHostUuid", local); expected.put("seedPlayerNames", names);
                    expected.put("seedEvidenceLabel", evidence.getFileName().toString()); expected.put("players", observed);
                    write(loader, expectedFile, expected);
                    var result = new LinkedHashMap<>(expected);
                    result.put("status", "SEEDED_AWAITING_NORMAL_SAVE_AND_REJOIN");
                    result.put("rewardClaimCommandIssued", false); result.put("actualSteamTransportVerified", false);
                    write(loader, evidence.resolve("identity-quests-progress-report.json"), result);
                } else {
                    Map<?, ?> expected = read(loader, expectedFile);
                    require(TASK.equals(expected.get("taskId")), "Wrong author quest receipt");
                    require(!evidence.getFileName().toString().equals(expected.get("seedEvidenceLabel")), "Verification requires another native launch");
                    boolean changedHost = !local.equals(expected.get("seedHostUuid"));
                    require(swap == changedHost, "Wrong localhost role for quest verification");
                    Map<?, ?> oldRows = (Map<?, ?>) expected.get("players");
                    Map<?, ?> oldNames = (Map<?, ?>) expected.get("seedPlayerNames");
                    require(actual.keySet().equals(oldRows.keySet()), "Quest receipt belongs to other accounts");
                    var observed = snapshots(loader, actual, task);
                    for (String id : actual.keySet()) {
                        require(!names.get(id).equals(oldNames.get(id)), "Both quest owners must use different nicknames");
                        Map<?, ?> before = (Map<?, ?>) oldRows.get(id);
                        Map<String, Object> after = observed.get(id);
                        require(after.get("teamId").equals(before.get("teamId")), "Native effective quest team changed");
                        if (before.containsKey("maxProgress")) require(after.get("maxProgress").equals(((Number) before.get("maxProgress")).longValue()), "Author task maximum changed");
                        require(((Number) after.get("progress")).longValue() == ((Number) before.get("progress")).longValue()
                                && after.get("completed").equals(before.get("completed")), "Native FTB task progress changed");
                    }
                    var result = new LinkedHashMap<String, Object>();
                    result.put("status", "PASS"); result.put("taskId", TASK); result.put("players", observed);
                    result.put("seedPlayerNames", oldNames); result.put("rejoinPlayerNames", names);
                    result.put("seedHostUuid", expected.get("seedHostUuid")); result.put("currentHostUuid", local);
                    result.put("normalSaveAndRejoinVerified", true); result.put("bothLocalHostRolesObserved", changedHost);
                    result.put("actualSteamTransportVerified", false); result.put("allQuestsExhaustivelyVerified", false);
                    result.put("worldNbtEditedByProbe", false); result.put("rewardClaimCommandIssued", false);
                    result.put("scope", "Existing author biplane task, zero matching inventory items, native FTB team IDs and progress after normal save/rejoin with two stable UUIDs and changed nicknames. No real Steam or exhaustive quest claim.");
                    write(loader, evidence.resolve("identity-quests-progress-report.json"), result);
                }
                complete = true;
            } catch (Throwable failure) {
                try {
                    setup.put("status", "FAIL"); var causes = new ArrayList<String>();
                    for (Throwable c = failure; c != null; c = c.getCause()) causes.add(c.toString());
                    setup.put("exceptions", causes);
                    write(server.getClass().getClassLoader(), evidence.resolve("identity-quests-progress-report.json"), setup);
                } catch (Throwable ignored) { }
                complete = true;
            } finally { busy.set(false); }
        });
    }

    private static Map<String, Map<String, Object>> snapshots(ClassLoader loader, Map<String, Object> players, Object task) throws Exception {
        Class<?> dataType = Class.forName("dev.ftb.mods.ftbquests.quest.TeamData", false, loader);
        Class<?> apiType = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI", false, loader);
        Object api = apiType.getMethod("api").invoke(null);
        Object manager = api.getClass().getMethod("getManager").invoke(api);
        long maximum = ((Number) task.getClass().getMethod("getMaxProgress").invoke(task)).longValue();
        require(maximum > 0, "Native author task has no positive maximum");
        var result = new TreeMap<String, Map<String, Object>>();
        for (var entry : players.entrySet()) {
            UUID id = UUID.fromString(entry.getKey());
            Object data = staticCall(dataType, "get", entry.getValue());
            String teamId = String.valueOf(call(data, "getTeamId"));
            require(entry.getKey().equals(teamId), "QA player unexpectedly belongs to a shared party team");
            Object personal = ((Optional<?>) manager.getClass().getMethod("getPlayerTeamForPlayerID", UUID.class).invoke(manager, id)).orElseThrow();
            Object effective = ((Optional<?>) manager.getClass().getMethod("getTeamForPlayerID", UUID.class).invoke(manager, id)).orElseThrow();
            String personalId = String.valueOf(personal.getClass().getMethod("getId").invoke(personal));
            String effectiveId = String.valueOf(effective.getClass().getMethod("getId").invoke(effective));
            Set<?> members = (Set<?>) effective.getClass().getMethod("getMembers").invoke(effective);
            require(entry.getKey().equals(personalId) && teamId.equals(effectiveId) && members.contains(id), "Native FTB team ownership/membership changed");
            result.put(entry.getKey(), Map.of("teamId", teamId, "personalTeamId", personalId,
                    "effectiveTeamContainsPlayer", true, "maxProgress", maximum,
                    "progress", call(data, "getProgress", task), "completed", call(data, "isCompleted", task)));
        }
        return result;
    }

    private static int itemCount(ClassLoader loader, Object player, String id) throws Exception {
        Object inv = call(player, "method_31548");
        Object registry = Class.forName("net.minecraft.class_7923", false, loader).getField("field_41178").get(null);
        int count = 0;
        for (int slot = 0; slot < (Integer) call(inv, "method_5439"); slot++) {
            Object stack = call(inv, "method_5438", slot);
            if (!(Boolean) call(stack, "method_7960") && id.equals(String.valueOf(call(registry, "method_10221", call(stack, "method_7909")))))
                count += (Integer) call(stack, "method_7947");
        }
        return count;
    }
}
