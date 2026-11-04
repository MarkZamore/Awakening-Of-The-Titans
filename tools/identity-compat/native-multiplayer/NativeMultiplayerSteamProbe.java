package dev.lanminecraft.aotqa.discovery;

import java.lang.reflect.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** QA-only native screen/provider-boundary fixture; never fakes a Steam API. */
public final class NativeMultiplayerSteamProbe {
    private static boolean finished;
    private static final String HOOKS = "minecraft.portable.identity.PortableSteamDiscoveryHooks";

    public static synchronized void tick(Object client, Object server, Object player, String reportPath) {
        if (finished) return;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("actualSteamTransportVerified", false);
        report.put("nativeFriendshipDiscoveryValidation", false);
        report.put("gpuVisualAssessment", false);
        report.put("syntheticProviderBoundary", "PortableSteamDiscoveryHooks.Poll.apply");
        if (player != null || server != null) {
            report.put("status", "WAITING_FOR_NORMAL_DISCONNECT"); write(reportPath, report); return;
        }
        finished = true;
        Object screen = null, previous = null;
        Class<?> hooks = null;
        Map<?, ?> savedPolls = null;
        try {
            Path root = Path.of(System.getProperty("lanminecraft.qa.root", "")).toRealPath();
            require(Files.isRegularFile(root.resolve(".aot-runtime-qa.json")), "Not an isolated QA runtime");
            require(Path.of(reportPath).toAbsolutePath().normalize().startsWith(root), "Report is outside the QA runtime");
            require(Boolean.getBoolean("minecraft.portable.identity.steamDiscoveryEnabled"), "Production Steam discovery is disabled");
            require("1.21.1".equals(System.getProperty("minecraft.portable.identity.steamDiscoveryMinecraftVersion")), "Native fixture requires Minecraft 1.21.1");
            require("net.minecraft.class_310".equals(client.getClass().getName()), "Unexpected native client namespace");
            ClassLoader loader = client.getClass().getClassLoader();
            hooks = Class.forName(HOOKS, false, null);
            previous = field(client, "field_1755");
            Path game = ((java.io.File) field(client, "field_1697")).toPath();
            report.put("initialScreenClass", previous == null ? "none" : previous.getClass().getName());
            Class<?> mcef = Class.forName("com.cinemamod.mcef.MCEF", false, loader);
            boolean mcefInitialized = (boolean) mcef.getMethod("isInitialized").invoke(null);
            Object download = Class.forName("com.cinemamod.mcef.internal.MCEFDownloadListener", false, loader)
                .getField("INSTANCE").get(null);
            boolean downloadDone = (boolean) invoke(download, "isDone");
            boolean downloadFailed = (boolean) invoke(download, "isFailed");
            report.put("mcefInitialized", mcefInitialized);
            report.put("mcefDownloadDone", downloadDone);
            report.put("mcefDownloadFailed", downloadFailed);
            require(!downloadFailed, "Author MCEF initialization/download failed; no screen bypass is allowed");
            if (!mcefInitialized) {
                finished = false;
                report.put("status", "WAITING_FOR_AUTHOR_MCEF_INITIALIZATION");
                report.put("nativeScreenAttempted", false);
                return;
            }
            Path nativeRoot = Path.of(System.getProperty("mcef.libraries.path")).toAbsolutePath().normalize();
            Path browserRoot = ((Path) staticField(Class.forName("com.cinemamod.mcef.CefUtil", false, loader), "CACHE_PATH"))
                .toAbsolutePath().normalize();
            Path mods = game.resolve("mods").toAbsolutePath().normalize();
            report.put("mcefNativeOutsideMods", !nativeRoot.startsWith(mods));
            report.put("mcefBrowserProfileOutsideMods", !browserRoot.startsWith(mods));
            Path manualServers = game.resolve("servers.dat");
            String savedServersBefore = fileHash(manualServers);
            screen = Class.forName("net.minecraft.class_500", true, loader)
                .getConstructor(Class.forName("net.minecraft.class_437", false, loader)).newInstance(previous);
            invoke(client, "method_1507", screen); // actual native init, widgets and lifecycle
            require(field(client, "field_1755") == screen, "Native Multiplayer screen did not become active");
            hooks.getMethod("end", Object.class).invoke(null, screen); // stop real network poll; synthetic boundary only
            Object list = field(screen, "field_3046");
            require(list != null && list.getClass().getName().equals("net.minecraft.class_1134$class_1136"), "No native automatic server collection");

            Class<?> pollClass = Class.forName(HOOKS + "$Poll", false, null);
            Constructor<?> pollConstructor = pollClass.getDeclaredConstructor(Object.class); pollConstructor.setAccessible(true);
            Object poll = pollConstructor.newInstance(screen); // pinned real e4steam interface check, no thread start
            @SuppressWarnings("unchecked") Map<Object, Object> polls = (Map<Object, Object>) staticField(hooks, "POLLS");
            savedPolls = polls;
            polls.put(screen, poll);
            Method clean = hooks.getDeclaredMethod("cleanName", String.class); clean.setAccessible(true);
            String playerName = (String) clean.invoke(null, "QA\u00a7 Host\u0001");
            String worldName = (String) clean.invoke(null, "QA\u00a7 Paradis World\u0001");
            require(!playerName.contains("\u00a7") && !worldName.contains("\u0001"), "Production names were not sanitized");
            Class<?> worldClass = Class.forName(HOOKS + "$World", false, null);
            Constructor<?> worldConstructor = worldClass.getDeclaredConstructor(long.class, long.class, String.class, String.class);
            worldConstructor.setAccessible(true);
            Object world = worldConstructor.newInstance(900001L, 76561197960265729L, playerName, worldName);
            Method addressMethod = worldClass.getDeclaredMethod("address"); addressMethod.setAccessible(true);
            String address = (String) addressMethod.invoke(world);
            require(address.equals("portable-steam-900001.invalid"), "Unexpected synthetic lobby address");
            Map<String, Object> found = Map.of(address, world);
            set(poll, "worlds", found);
            Method apply = pollClass.getDeclaredMethod("apply", Map.class); apply.setAccessible(true); apply.invoke(poll, found);
            require((boolean) hooks.getMethod("nativeDiscoveryManaged", Object.class).invoke(null, list), "Native LAN list is not managed by production provider");
            @SuppressWarnings("unchecked") List<Object> servers = (List<Object>) field(list, "field_5536");
            require(servers.size() == 1, "Production provider did not insert exactly one native server");
            Object nativeServer = servers.getFirst();
            require(nativeServer.getClass().getName().equals("net.minecraft.class_1131"), "Provider created a non-native server object");
            require(invoke(nativeServer, "method_4812").equals(address), "Native join address was changed");
            require(hooks.getMethod("displayAddress", String.class, Object.class).invoke(null, address, nativeServer).equals("Steam"),
                "Production native display hook did not identify the Steam transport");
            invoke(screen, "method_25393"); // native tick consumes dirty collection into actual widget rows
            Object widget = field(screen, "field_3043");
            @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) field(widget, "field_19111");
            require(rows.size() == 1, "Native server-list widget did not create exactly one network row");
            Object row = rows.getFirst();
            require(row.getClass().getName().equals("net.minecraft.class_4267$class_4269"), "Not a native network server row");
            require(field(row, "field_19114") == nativeServer, "The native row does not refer to the provider's server");
            @SuppressWarnings("unchecked") List<Object> visibleRows = (List<Object>) invoke(widget, "method_25396");
            require(visibleRows.contains(row), "Provider row is missing from native visible children");

            Object renderBuffers = invoke(invoke(client, "method_22940"), "method_23000");
            NativeRecordingDrawContext context = new NativeRecordingDrawContext((net.minecraft.class_310) client,
                (net.minecraft.class_4597.class_4598) renderBuffers);
            invoke(row, "method_25343", context, 0, 48, 0, 420, 36, -1, -1, false, 0f);
            require(context.text.size() == 3, "Unexpected native renderer text sites");
            require(context.text.get(0).equals(worldName), "Native renderer did not draw the world name as header");
            require(context.text.get(1).equals(playerName), "Native renderer did not retain the host line");
            boolean hidden = (boolean) field(field(client, "field_1690"), "field_1815");
            require(hidden || context.text.get(2).equals("Steam"), "Native renderer did not show the Steam transport label");
            require(context.text.stream().noneMatch(text -> text.contains("portable-steam-") || text.contains("900001")), "Private synthetic address leaked into native labels");
            Object narration = invoke(row, "method_48273");
            String narrationText = (String) invoke(narration, "getString");
            require(narrationText.contains(worldName) && !narrationText.contains(address), "Native row narration lost the world label or exposed an address");

            invoke(list, "method_4824", "[MOTD]QA UDP control[/MOTD][AD]25565[/AD]", InetAddress.getLoopbackAddress());
            require(servers.size() == 1 && servers.getFirst() == nativeServer, "Managed native list accepted a UDP-discovered row");
            Object ordinaryList = list.getClass().getConstructor().newInstance();
            invoke(ordinaryList, "method_4824", "[MOTD]QA UDP control[/MOTD][AD]25565[/AD]", InetAddress.getLoopbackAddress());
            require(((List<?>) field(ordinaryList, "field_5536")).size() == 1, "An unrelated native LAN list lost ordinary UDP behavior");
            Object manual = serverData(loader, "127.0.0.1:9");
            require(!(boolean) hooks.getMethod("join", Object.class, Object.class).invoke(null, screen, manual), "Ordinary manual server was intercepted");
            Object stale = serverData(loader, address);
            set(poll, "worlds", Map.of());
            require((boolean) hooks.getMethod("join", Object.class, Object.class).invoke(null, screen, stale), "Stale Steam row was not consumed");
            invoke(screen, "method_2548", stale); // transformed actual native join must short-circuit
            require(field(client, "field_1755") == screen && invoke(client, "method_1562") == null,
                "Stale synthetic join entered the ordinary connection path");
            require(fileHash(manualServers).equals(savedServersBefore), "Manual saved-server file changed");
            report.put("status", "PASS");
            report.put("actualNativeMultiplayerScreen", screen.getClass().getName());
            report.put("actualNativeNetworkRow", row.getClass().getName());
            report.put("productionApplyUsed", true);
            report.put("nativeVisibleRowCount", rows.size());
            report.put("nativeRendererExecuted", true);
            report.put("nativeRendererTextArguments", context.text);
            report.put("serverAddressHiddenByExistingOption", hidden);
            report.put("nativeNarrationVerified", true);
            report.put("managedUdpSuppressed", true);
            report.put("ordinaryUnmanagedUdpPreserved", true);
            report.put("manualServerRoutePreserved", true);
            report.put("staleNativeJoinShortCircuited", true);
            report.put("savedServersUnchanged", true);
            report.put("actualSteamRuntimeClass", field(poll, "runtime").getClass().getName());
        } catch (Throwable failure) {
            report.put("status", "FAIL"); report.put("error", failure.toString());
            Throwable cause = failure.getCause(); if (cause != null) report.put("cause", cause.toString());
        } finally {
            try {
                if (hooks != null && screen != null) hooks.getMethod("end", Object.class).invoke(null, screen);
                if (screen != null && field(client, "field_1755") == screen) invoke(client, "method_1507", previous);
                report.put("previousScreenRestored", screen == null || field(client, "field_1755") == previous);
                if (savedPolls != null) require(!savedPolls.containsKey(screen), "Synthetic poll was not removed");
            } catch (Throwable cleanup) { report.put("status", "FAIL"); report.put("cleanupError", cleanup.toString()); }
            write(reportPath, report);
        }
    }

    private static Object serverData(ClassLoader loader, String address) throws Exception {
        Class<?> type = Class.forName("net.minecraft.class_642", true, loader);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 3 && parameters[0] == String.class && parameters[1] == String.class && parameters[2].isEnum()) {
                constructor.setAccessible(true); return constructor.newInstance("QA transient server", address, parameters[2].getEnumConstants()[0]);
            }
        }
        throw new NoSuchMethodException("Native ServerInfo constructor");
    }
    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            method.setAccessible(true); return method.invoke(target, arguments);
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            method.setAccessible(true); return method.invoke(target, arguments);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
    private static Field find(Class<?> type, String name) throws Exception {
        for (; type != null; type = type.getSuperclass()) try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static Object field(Object target, String name) throws Exception { return find(target.getClass(), name).get(target); }
    private static Object staticField(Class<?> type, String name) throws Exception { return find(type, name).get(null); }
    private static void set(Object target, String name, Object value) throws Exception { find(target.getClass(), name).set(target, value); }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    private static String fileHash(Path path) throws Exception {
        return Files.exists(path) ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) : "absent";
    }
    private static void write(String path, Map<String, Object> report) {
        try { Files.writeString(Path.of(path), json(report)); }
        catch (Exception failure) { throw new IllegalStateException("QA report could not be written", failure); }
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream().map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue())).toList()) + "}";
        if (value instanceof Collection<?> list) return "[" + String.join(",", list.stream().map(NativeMultiplayerSteamProbe::json).toList()) + "]";
        String text = value.toString(); StringBuilder result = new StringBuilder("\"");
        for (char c : text.toCharArray()) switch (c) { case '\\' -> result.append("\\\\"); case '"' -> result.append("\\\""); case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t"); default -> { if (c < 32) result.append(String.format("\\u%04x", (int)c)); else result.append(c); } }
        return result.append('"').toString();
    }
}
