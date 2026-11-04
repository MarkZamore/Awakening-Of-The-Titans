package dev.lanminecraft.aotqa.identity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicitly armed disposable-world native player progress check. Not a mod. */
public final class NativeProgressProbe {
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static volatile boolean complete;
    private NativeProgressProbe() { }

    public static void tick(Object client, Object server, Object player, String report) throws Exception {
        if (complete || !busy.compareAndSet(false, true)) return;
        Path root = Path.of(System.getProperty("lanminecraft.qa.root")).toAbsolutePath().normalize();
        Path evidence = Path.of(System.getProperty("lanminecraft.qa.evidence")).toAbsolutePath().normalize();
        require(Files.isRegularFile(root.resolve(".aot-runtime-qa.json")), "Unmarked QA fixture");
        require(evidence.startsWith(root) && Path.of(report).toAbsolutePath().normalize().getParent().equals(evidence), "Unexpected QA report path");
        boolean seed = Files.isRegularFile(evidence.resolve("progress-seed.flag"));
        boolean verify = Files.isRegularFile(evidence.resolve("progress-verify.flag"));
        if (seed == verify) { busy.set(false); return; }
        ((Executor) server).execute(() -> {
            try {
                ClassLoader loader = server.getClass().getClassLoader();
                List<?> players = (List<?>) call(call(server, "method_3760"), "method_14571");
                require(players.size() == 1, "Progress probe needs exactly one disposable-world player");
                Object actual = players.get(0);
                UUID uuid = (UUID) call(actual, "method_5667");
                require(uuid.equals(UUID.fromString(System.getProperty("minecraft.portable.identity.localPlayer"))), "Player ownership differs from launch UUID");
                Object profile = call(actual, "method_7334");
                String playerName = String.valueOf(call(profile, "getName"));
                Object world = call(actual, "method_37908");
                Path expectedFile = root.resolve("identity-progress-expected.json");
                if (seed) {
                    require(!Files.exists(expectedFile), "Progress seed already exists; do not overwrite evidence");
                    // Native players-only arguments reject UUID literals because
                    // those can also identify non-player entities. @a is bounded
                    // by the exact-one-player and launch-UUID guards above.
                    Map<String, Object> commands = new LinkedHashMap<>();
                    for (String text : List.of("gamemode creative @a",
                            "give @a minecraft:diamond 7", "experience set @a 13 levels",
                            "daot bloodline set @a eldian", "daot shifter set @a attack")) {
                        if (text.startsWith("give ")) commands.put("clear @a", hasItems(actual)
                                ? command(server, "clear @a") : "SKIPPED_NATIVE_INVENTORY_ALREADY_EMPTY");
                        commands.put(text, command(server, text));
                    }
                    Map<String, Object> expected = snapshot(loader, actual, world);
                    Map<String, Object> setup = new LinkedHashMap<>(expected);
                    setup.put("commandResults", commands);
                    setup.put("playerUuid", uuid.toString());
                    write(loader, evidence.resolve("identity-progress-setup.json"), setup);
                    require(((Number) expected.get("diamondCount")).intValue() == 7, "Native fixture diamond grant failed");
                    require(((Number) expected.get("experienceLevel")).intValue() == 13, "Native fixture experience grant failed");
                    require(Boolean.TRUE.equals(expected.get("attackPower")) && "ELDIAN".equals(expected.get("bloodline")), "Author DAOT command assignment not observed");
                    expected.put("playerUuid", uuid.toString()); expected.put("seedPlayerName", playerName);
                    expected.put("seedEvidenceLabel", evidence.getFileName().toString());
                    write(loader, expectedFile, expected);
                    Map<String, Object> result = new LinkedHashMap<>(expected);
                    result.put("status", "SEEDED_AWAITING_NORMAL_SAVE_AND_RESTART");
                    result.put("runtimeSaveReloadProved", false);
                    result.put("fixtureOnly", true);
                    write(loader, evidence.resolve("identity-progress-report.json"), result);
                } else {
                    Map<?, ?> expected = read(loader, expectedFile);
                    require(uuid.toString().equals(expected.get("playerUuid")), "Restart used a different portable UUID");
                    require(!evidence.getFileName().toString().equals(expected.get("seedEvidenceLabel")), "Verification must run in another native launch");
                    require(!playerName.equals(expected.get("seedPlayerName")), "Verification must change the display nickname");
                    Map<String, Object> observed = snapshot(loader, actual, world);
                    for (String key : List.of("diamondCount", "experienceLevel"))
                        require(((Number) observed.get(key)).intValue() == ((Number) expected.get(key)).intValue(), "Native reloaded progress changed: " + key);
                    for (String key : List.of("attackPower", "bloodline"))
                        require(observed.get(key).equals(expected.get(key)), "Native reloaded DAOT state changed: " + key);
                    observed.put("playerUuid", uuid.toString()); observed.put("seedPlayerName", expected.get("seedPlayerName"));
                    observed.put("rejoinPlayerName", playerName); observed.put("status", "PASS");
                    observed.put("runtimeSaveReloadProved", true);
                    observed.put("samePortableUuidChangedNickname", true);
                    observed.put("actualSteamGuestProgressVerified", false);
                    observed.put("allModProgressFormatsExhaustivelyVerified", false);
                    observed.put("scope", "Actual integrated-server native inventory, XP and DAOT PersistentState after normal save/restart with the same portable UUID and a different nickname");
                    write(loader, evidence.resolve("identity-progress-report.json"), observed);
                }
                complete = true;
            } catch (Throwable failure) {
                try {
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("status", "FAIL"); failed.put("exception", failure.toString());
                    write(server.getClass().getClassLoader(), evidence.resolve("identity-progress-report.json"), failed);
                } catch (Throwable ignored) { }
                complete = true;
            } finally { busy.set(false); }
        });
    }

    static Map<String, Object> snapshot(ClassLoader loader, Object player, Object world) throws Exception {
        Object inv = call(player, "method_31548");
        Object registries = Class.forName("net.minecraft.class_7923", false, loader).getField("field_41178").get(null);
        int count = 0;
        for (int slot = 0; slot < (Integer) call(inv, "method_5439"); slot++) {
            Object stack = call(inv, "method_5438", slot);
            if ((Boolean) call(stack, "method_7960")) continue;
            if ("minecraft:diamond".equals(String.valueOf(call(registries, "method_10221", call(stack, "method_7909")))))
                count += (Integer) call(stack, "method_7947");
        }
        UUID id = (UUID) call(player, "method_5667");
        Class<?> powersType = Class.forName("daot.TitanPowerData", false, loader);
        Object powers = staticCall(powersType, "get", world);
        Object attack = Class.forName("daot.TitanPowerType", false, loader).getField("ATTACK").get(null);
        Object blood = staticCall(Class.forName("daot.BloodlineData", false, loader), "get", world);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("diamondCount", count); row.put("experienceLevel", field(player.getClass(), "field_7520").getInt(player));
        row.put("attackPower", call(powers, "playerHoldsPower", id, attack));
        row.put("bloodline", String.valueOf(call(blood, "getBloodline", id)));
        return row;
    }
    static int command(Object server, String text) throws Exception {
        return (Integer) call(dispatcher(server), "execute", text, call(server, "method_3739"));
    }
    static Object dispatcher(Object server) throws Exception {
        Object manager = call(server, "method_3734");
        Object dispatcher = null;
        for (Method method : manager.getClass().getDeclaredMethods())
            if (method.getParameterCount() == 0 && method.getReturnType().getName().equals("com.mojang.brigadier.CommandDispatcher")) {
                require(dispatcher == null, "Ambiguous native command dispatcher");
                method.setAccessible(true); dispatcher = method.invoke(manager);
            }
        require(dispatcher != null, "Native command dispatcher missing");
        // Unlike executeWithPrefix, this preserves native syntax/permission
        // exceptions and returns the actual Brigadier command result.
        return dispatcher;
    }
    static boolean hasItems(Object player) throws Exception {
        Object inv = call(player, "method_31548");
        for (int slot = 0; slot < (Integer) call(inv, "method_5439"); slot++)
            if (!(Boolean) call(call(inv, "method_5438", slot), "method_7960")) return true;
        return false;
    }
    private static Field field(Class<?> type, String name) throws Exception { for (Class<?> c = type; c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) { } throw new NoSuchFieldException(name); }
    static Object call(Object target, String name, Object... args) throws Exception { return invoke(target.getClass(), target, name, args); }
    static Object staticCall(Class<?> type, String name, Object... args) throws Exception { return invoke(type, null, name, args); }
    private static Object invoke(Class<?> type, Object target, String name, Object... args) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Method method : c.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean fits = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> t = method.getParameterTypes()[i]; if (t == int.class) t = Integer.class;
                if (args[i] != null && !t.isInstance(args[i])) fits = false;
            }
            if (fits) { method.setAccessible(true); return method.invoke(target, args); }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    static void write(ClassLoader loader, Path path, Map<String, Object> value) throws Exception {
        Class<?> gson = Class.forName("com.google.gson.Gson", true, loader); Object g = gson.getConstructor().newInstance();
        Files.writeString(path, (String) gson.getMethod("toJson", Object.class).invoke(g, value) + "\n", StandardCharsets.UTF_8);
    }
    static Map<?, ?> read(ClassLoader loader, Path path) throws Exception {
        Class<?> gson = Class.forName("com.google.gson.Gson", true, loader); Object g = gson.getConstructor().newInstance();
        return (Map<?, ?>) gson.getMethod("fromJson", String.class, Class.class).invoke(g, Files.readString(path, StandardCharsets.UTF_8), Map.class);
    }
}
