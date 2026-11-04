package dev.lanminecraft.aotqa.xaero;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Optional disposable-world QA. No transformer, entrypoint or gameplay installation. */
public final class XaeroProbe {
    private static final String EXPECTED_JAR = "86bdc4282a73ed55e6a4903a7ba65940a6aed20404f7ab7c96f8d94078accd1b";
    private static final String FILE_NAME = "xaerominimap-fabric-1.21.1-26.6.0.jar";
    private static boolean finished;

    private XaeroProbe() { }

    /** Called by the fixture's opt-in QA agent on the real client thread. */
    public static synchronized void tick(Object client, Object server, Object player, String reportPath) {
        if (finished || client == null || player == null) return;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("utc", Instant.now().toString());
        report.put("status", "RUNNING");
        report.put("actualSteamGuestConnectionVerified", false);
        report.put("visualMinimapAssessmentPerformed", false);
        String mode = System.getProperty("aot.qa.mapsProbe.mode", "create");
        report.put("mode", mode);
        try {
            require(mode.equals("create") || mode.equals("verify"), "Unsupported maps probe mode");
            String receiptArgument = System.getProperty("aot.qa.mapsProbe.receipt", "");
            require(!receiptArgument.isBlank(), "aot.qa.mapsProbe.receipt is required");
            Path receipt = Path.of(receiptArgument).toAbsolutePath().normalize();
            Path output = Path.of(reportPath).toAbsolutePath().normalize();
            require(output.getParent() != null && receipt.getParent() != null, "Absolute evidence paths are required");
            require(!output.equals(receipt), "Report and persistent receipt must be different files");

            ClassLoader loader = client.getClass().getClassLoader();
            Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules", false, loader)
                    .getField("MINIMAP").get(null);
            Object session = call(module, "getCurrentSession");
            if (session == null) return;
            Object manager = call(session, "getWorldManager");
            Object world = call(manager, "getCurrentWorld");
            if (world == null) return;
            Object currentSet = call(world, "getCurrentWaypointSet");
            if (currentSet == null) return;

            Path game = ((File) field(client, "field_1697")).toPath().toAbsolutePath().normalize();
            Path sourceJar = game.resolve("mods").resolve(FILE_NAME);
            require(Files.isRegularFile(sourceJar), "The pinned original Xaero JAR is absent");
            String jarHash = sha(Files.readAllBytes(sourceJar));
            require(EXPECTED_JAR.equals(jarHash), "Xaero JAR changed; this native probe needs review");
            report.put("xaeroJarSha256", jarHash);

            Object state = call(session, "getWorldState");
            String dimension = String.valueOf(call(call(world, "getDimId"), "method_29177"));
            String expectedDimension = System.getProperty("aot.qa.mapsProbe.dimension", "dannys-aot:paradis");
            require(dimension.equals(expectedDimension), "Wrong native waypoint dimension: " + dimension);
            String worldNode = String.valueOf(call(world, "getNode"));
            String nativeWorldPath = String.valueOf(call(state, "getCurrentWorldPath"));
            String rootPath = String.valueOf(call(state, "getAutoRootContainerPath"));
            report.put("dimension", dimension);
            report.put("worldNode", worldNode);
            report.put("worldPathSha256", sha(nativeWorldPath));
            report.put("rootPathSha256", sha(rootPath));
            report.put("waypointSet", String.valueOf(call(currentSet, "getName")));
            String address = address(client);
            report.put("serverAddressSha256", sha(address));
            report.put("serverAddressKind", address.contains(".steam") ? "steam" : address.isEmpty() ? "integrated" : "other");

            Object mod = Class.forName("xaero.common.HudMod", false, loader).getField("INSTANCE").get(null);
            Object minimap = call(mod, "getMinimap");
            Object crash = call(minimap, "getCrashedWith");
            require(crash == null, "Native minimap records a renderer crash");
            report.put("minimapCrashRecorded", false);
            report.put("minimapUsingFbo", call(minimap, "usingFBO"));
            report.put("minimapSessionActive", call(session, "isActive"));

            String sentinel = System.getProperty("aot.qa.mapsProbe.sentinel", "LANMinecraft_QA_Paradis_Marker");
            require(sentinel.matches("[A-Za-z0-9_]{1,80}"), "Unsafe sentinel name");
            Properties expected = new Properties();
            if (mode.equals("create")) {
                require(server != null, "Creation probe requires the disposable integrated server");
                require(!Files.exists(receipt), "Use a new persistent receipt for the create phase");
                require(find(currentSet, sentinel).isEmpty(), "Sentinel already exists; no duplicate is created");
                int x = (int) Math.floor(((Number) call(player, "method_23317")).doubleValue()) + 8;
                int y = (int) Math.floor(((Number) call(player, "method_23318")).doubleValue());
                int z = (int) Math.floor(((Number) call(player, "method_23321")).doubleValue()) + 8;
                Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint", false, loader);
                Object waypoint = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class)
                        .newInstance(x, y, z, sentinel, "Q", 0);
                call(currentSet, "add", waypoint);
                expected.setProperty("sentinel", sentinel);
                expected.setProperty("dimension", dimension);
                expected.setProperty("worldNode", worldNode);
                expected.setProperty("setName", String.valueOf(call(currentSet, "getName")));
                expected.setProperty("x", Integer.toString(x));
                expected.setProperty("y", Integer.toString(y));
                expected.setProperty("z", Integer.toString(z));
                expected.setProperty("worldPathSha256", sha(nativeWorldPath));
                expected.setProperty("rootPathSha256", sha(rootPath));
                expected.setProperty("jarSha256", jarHash);
            } else {
                try (InputStream stream = Files.newInputStream(receipt)) { expected.load(stream); }
                require(EXPECTED_JAR.equals(expected.getProperty("jarSha256")), "Persistent receipt is for another Xaero file");
                require(sentinel.equals(expected.getProperty("sentinel")), "Persistent sentinel name differs");
                require(dimension.equals(expected.getProperty("dimension")), "Persistent waypoint dimension differs");
                require(worldNode.equals(expected.getProperty("worldNode")), "The actual native world node differs");
                require(String.valueOf(call(currentSet, "getName")).equals(expected.getProperty("setName")), "Selected waypoint set changed");
                boolean allowRootChange = Boolean.getBoolean("aot.qa.mapsProbe.allowRootChange");
                if (!allowRootChange)
                    require(sha(nativeWorldPath).equals(expected.getProperty("worldPathSha256")), "Native waypoint path changed unexpectedly");
                report.put("rootChanged", !sha(rootPath).equals(expected.getProperty("rootPathSha256")));
                report.put("rootChangeAllowedForSessionTokenTest", allowRootChange);
            }

            List<Object> found = find(currentSet, sentinel);
            require(found.size() == 1, "Expected exactly one native sentinel; found " + found.size());
            Object waypoint = found.getFirst();
            for (String coordinate : List.of("x", "y", "z")) {
                Object value = call(waypoint, "get" + coordinate.toUpperCase());
                require(Integer.toString(((Number) value).intValue()).equals(expected.getProperty(coordinate)), "Waypoint coordinate changed: " + coordinate);
                report.put(coordinate, value);
            }
            Object io = call(session, "getWorldManagerIO");
            call(io, "saveAllWorlds", session);
            Path nativeFile = (Path) call(io, "getWorldFile", world);
            require(Files.isRegularFile(nativeFile), "Native waypoint save did not create its file");
            byte[] fileBytes = Files.readAllBytes(nativeFile);
            require(new String(fileBytes, StandardCharsets.UTF_8).contains("waypoint:" + sentinel + ":"), "Native save file does not contain the sentinel");
            report.put("sentinel", sentinel);
            report.put("nativeSentinelCount", found.size());
            report.put("nativeFileName", nativeFile.getFileName().toString());
            report.put("nativeFileSize", fileBytes.length);
            report.put("nativeFileSha256", sha(fileBytes));
            report.put("nativeSaveCalled", true);
            if (mode.equals("create")) {
                Files.createDirectories(receipt.getParent());
                StringBuilder content = new StringBuilder();
                expected.stringPropertyNames().stream().sorted().forEach(key -> content.append(key).append('=').append(expected.getProperty(key)).append('\n'));
                atomic(receipt, content.toString());
            }
            report.put("status", "PASS");
            atomic(output, json(report) + "\n");
            System.out.println("[AotQaXaero] " + mode + " PASS: native waypoint and Paradis session verified; Steam guest and visual assessment not claimed.");
            finished = true;
        } catch (Throwable failure) {
            Throwable cause = failure;
            while (cause instanceof InvocationTargetException invocation && invocation.getCause() != null) cause = invocation.getCause();
            report.put("status", "FAIL");
            report.put("errorType", cause.getClass().getName());
            report.put("error", String.valueOf(cause.getMessage()));
            try { atomic(Path.of(reportPath).toAbsolutePath().normalize(), json(report) + "\n"); }
            catch (Throwable writingFailure) { System.err.println("[AotQaXaero] Cannot write failure evidence: " + writingFailure.getClass().getSimpleName()); }
            System.err.println("[AotQaXaero] FAIL: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            finished = true;
        }
    }

    private static List<Object> find(Object set, String name) throws ReflectiveOperationException {
        List<Object> result = new ArrayList<>();
        for (Object waypoint : (Iterable<?>) call(set, "getWaypoints"))
            if (name.equals(String.valueOf(call(waypoint, "getName")))) result.add(waypoint);
        return result;
    }

    private static String address(Object client) {
        try {
            Object info = call(client, "method_1558");
            return info == null ? "" : String.valueOf(field(info, "field_3761"));
        } catch (ReflectiveOperationException unavailable) { return ""; }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object target, String name, Object... arguments) throws ReflectiveOperationException {
        outer: for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            Class<?>[] parameters = method.getParameterTypes();
            for (int index = 0; index < arguments.length; index++)
                if (arguments[index] != null && !parameters[index].isInstance(arguments[index])
                        && !(parameters[index].isPrimitive() && arguments[index] instanceof Number)) continue outer;
            method.setAccessible(true);
            return method.invoke(target, arguments);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String sha(String text) throws Exception { return sha(text.getBytes(StandardCharsets.UTF_8)); }
    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void atomic(Path output, String contents) throws IOException {
        Files.createDirectories(output.getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temporary, contents, StandardCharsets.UTF_8);
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) entries.add(json(String.valueOf(entry.getKey())) + ":" + json(entry.getValue()));
            return "{" + String.join(",", entries) + "}";
        }
        StringBuilder escaped = new StringBuilder("\"");
        for (char character : String.valueOf(value).toCharArray()) {
            if (character == '\\' || character == '"') escaped.append('\\').append(character);
            else if (character < 32) escaped.append(String.format("\\u%04x", (int) character));
            else escaped.append(character);
        }
        return escaped.append('"').toString();
    }
}
