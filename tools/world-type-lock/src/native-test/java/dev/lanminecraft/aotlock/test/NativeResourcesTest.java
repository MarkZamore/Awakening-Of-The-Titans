package dev.lanminecraft.aotlock.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Native serializers, never substitute generator fixtures. Full preset verification
 * must run inside the initialized Fabric client with its real creation context.
 * Standalone main proves only pack metadata; it deliberately does not report a
 * successful custom-generator decode without Danny's actual registered codecs.
 */
public final class NativeResourcesTest {
    private static final String PRESET = "attack_on_titan:paradis";
    private static final String AUTHOR_SHA256 = "eb30a874e618cbf1f356301d672e7b322673babfa46f8212bc0e98f112eb085d";

    private NativeResourcesTest() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2)
            throw new IllegalArgumentException("Usage: NativeResourcesTest RESOURCE_ROOT REPORT_JSON");
        ClassLoader loader = NativeResourcesTest.class.getClassLoader();
        type(loader, "net.minecraft.class_155").getMethod("method_36208").invoke(null);
        type(loader, "net.minecraft.class_2966").getMethod("method_12851").invoke(null);
        JsonObject report = metadata(loader, Path.of(arguments[0]));
        report.addProperty("nativeWorldPresetCodec", false);
        report.addProperty("reason", "Full preset verification requires an initialized Fabric client creation context");
        save(Path.of(arguments[1]), report);
        System.out.println("NativeResourcesTest metadata PASS; custom preset and runtime not asserted.");
    }

    /** Invoked by the native-client QA bridge after opening CreateWorldScreen. */
    public static String verify(Object uiState, String resourceRoot, String authorJar, String reportJson)
            throws Exception {
        ClassLoader loader = uiState.getClass().getClassLoader();
        Path resources = Path.of(resourceRoot);
        Path source = Path.of(authorJar);
        if (!hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))).equals(AUTHOR_SHA256))
            throw new AssertionError("The native author codec audit requires the locked Danny's AOT 2.5.2 JAR");
        JsonObject report = metadata(loader, resources);
        Object context = call(uiState, "method_48728");
        Object access = call(context, "method_45689");
        Object rawOps = staticCall(type(loader, "net.minecraft.class_6903"), "method_46632", JsonOps.INSTANCE, access);
        @SuppressWarnings("unchecked")
        DynamicOps<JsonElement> ops = (DynamicOps<JsonElement>) rawOps;
        Codec<Object> presetCodec = codec(loader, "net.minecraft.class_7145", "field_37725");
        Codec<Object> dimensionCodec = codec(loader, "net.minecraft.class_5363", "field_25411");
        JsonElement supplied = read(resources.resolve("data/attack_on_titan/worldgen/world_preset/paradis.json"));
        Object decoded = value(presetCodec.parse(ops, supplied), "native preset decode");
        JsonElement canonical = value(presetCodec.encodeStart(ops, decoded), "native preset encode");
        Object roundTrip = value(presetCodec.parse(ops, canonical), "native preset round-trip decode");
        if (!canonical.equals(value(presetCodec.encodeStart(ops, roundTrip), "native preset round-trip encode")))
            throw new AssertionError("WorldPreset native codec round-trip changed its dimensions");
        JsonObject dimensions = canonical.getAsJsonObject().getAsJsonObject("dimensions");
        Set<String> expected = Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end",
                "dannys-aot:paradis", "dannys-aot:paths");
        if (!dimensions.keySet().equals(expected))
            throw new AssertionError("Preset has unexpected/missing dimensions: " + dimensions.keySet());
        try (ZipFile jar = new ZipFile(source.toFile())) {
            for (String dimension : List.of("paradis", "paths")) {
                var entry = jar.getEntry("data/dannys-aot/dimension/" + dimension + ".json");
                if (entry == null) throw new AssertionError("Author dimension is missing: " + dimension);
                JsonElement author = JsonParser.parseString(new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                Object nativeDimension = value(dimensionCodec.parse(ops, author), "native author " + dimension + " decode");
                JsonElement nativeAuthor = value(dimensionCodec.encodeStart(ops, nativeDimension), "native author " + dimension + " encode");
                if (!nativeAuthor.equals(dimensions.get("dannys-aot:" + dimension)))
                    throw new AssertionError("Preset changes native author dimension " + dimension);
            }
        }
        Object presetRegistryKey = type(loader, "net.minecraft.class_7924").getField("field_41250").get(null);
        Object registry = call(access, "method_30530", presetRegistryKey);
        Object normal = null;
        for (Object raw : (Set<?>) call(registry, "method_29722")) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) raw;
            if ("minecraft:normal".equals(String.valueOf(call(entry.getKey(), "method_29177")))) normal = entry.getValue();
        }
        if (normal == null) throw new AssertionError("Native normal world preset is missing");
        JsonObject normalDimensions = value(presetCodec.encodeStart(ops, normal), "native normal preset encode")
                .getAsJsonObject().getAsJsonObject("dimensions");
        for (String name : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"))
            if (!normalDimensions.get(name).equals(dimensions.get(name)))
                throw new AssertionError("Preset changes native normal dimension " + name);
        for (String getter : List.of("method_48732", "method_48733")) {
            List<?> entries = (List<?>) call(uiState, getter);
            if (entries.size() != 1 || !PRESET.equals(id(entries.get(0))))
                throw new AssertionError("World type list is not restricted: " + getter);
        }
        if (!PRESET.equals(id(call(uiState, "method_48730"))))
            throw new AssertionError("Selected native world preset is not " + PRESET);
        report.addProperty("nativeWorldPresetCodec", true);
        report.addProperty("nativeAuthorDimensionsEqual", true);
        report.addProperty("nativeNormalDimensionsEqual", true);
        report.addProperty("nativeNormalAndShiftListsRestricted", true);
        verifyNativePolicy(uiState, registry, Path.of(reportJson).toAbsolutePath().getParent(), report);
        report.addProperty("authorSha256", AUTHOR_SHA256);
        report.addProperty("presetId", PRESET);
        report.addProperty("runtimeClientWorldCreated", false);
        report.add("encodedPreset", canonical);
        save(Path.of(reportJson), report);
        return report.toString();
    }

    /** Uses the real, already transformed native classes. No replacement game fixtures. */
    private static void verifyNativePolicy(Object uiState, Object registry, Path output, JsonObject report) throws Exception {
        ClassLoader loader = uiState.getClass().getClassLoader();
        Object context = call(uiState, "method_48728");
        Object dimensions = call(context, "comp_1028");
        long seed = 839472928314L;
        Object options = type(loader, "net.minecraft.class_5285")
                .getConstructor(long.class, boolean.class, boolean.class).newInstance(seed, false, true);
        Object constructorContext = call(context, "method_41864", options, dimensions);
        Path saves = output.resolve("native-policy-constructor");
        Files.createDirectories(saves);
        Object independent = type(loader, "net.minecraft.class_8100").getConstructor(
                Path.class, type(loader, "net.minecraft.class_7193"), Optional.class, OptionalLong.class)
                .newInstance(saves, constructorContext, Optional.empty(), OptionalLong.of(seed));
        assertPolicyOptions(independent, seed);
        requireRestricted(independent);
        report.addProperty("nativeConstructorNondefaultSeedAndFlagsPreserved", true);

        Object normalEntry = null;
        for (Object raw : (Set<?>) call(registry, "method_29722")) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) raw;
            if ("minecraft:normal".equals(String.valueOf(call(entry.getKey(), "method_29177")))) {
                Object holder = ((Optional<?>) call(registry, "method_40264", entry.getKey())).orElseThrow();
                normalEntry = type(loader, "net.minecraft.class_8100$class_8101")
                        .getConstructor(type(loader, "net.minecraft.class_6880")).newInstance(holder);
            }
        }
        if (normalEntry == null) throw new AssertionError("Native normal preset entry is absent");
        call(independent, "method_48705", normalEntry);
        if (!"minecraft:normal".equals(id(call(independent, "method_48730"))))
            throw new AssertionError("Probe did not actually forge native normal selection");
        call(independent, "method_48701", call(independent, "method_48728"));
        requireRestricted(independent);
        assertPolicyOptions(independent, seed);
        report.addProperty("nativeSetSettingsReloadRepairsSelectionAndBothLists", true);
        report.addProperty("nativeSetSettingsReloadPreservesSeedAndFlags", true);

        Object minecraft = type(loader, "net.minecraft.class_310").getMethod("method_1551").invoke(null);
        Object screen = field(minecraft.getClass(), "field_1755").get(minecraft);
        if (screen == null || !type(loader, "net.minecraft.class_525").isInstance(screen)
                || call(screen, "method_48657") != uiState)
            throw new AssertionError("beforeCreate audit requires the actual current CreateWorldScreen");
        Object originalContext = call(uiState, "method_48728");
        Object originalEntry = call(uiState, "method_48730");
        String originalSeed = (String) call(uiState, "method_48725");
        boolean originalStructures = (Boolean) call(uiState, "method_48726");
        boolean originalBonus = (Boolean) call(uiState, "method_48727");
        try {
            call(uiState, "method_48705", normalEntry);
            if (!"minecraft:normal".equals(id(call(uiState, "method_48730"))))
                throw new AssertionError("Actual screen selection was not forged for gate audit");
            Object allowed = staticCall(type(loader, "dev.lanminecraft.aotlock.WorldTypePolicy"), "beforeCreate", screen);
            if (!Boolean.TRUE.equals(allowed)) throw new AssertionError("Native beforeCreate refused intact resources");
            requireRestricted(uiState);
            if (!originalSeed.equals(call(uiState, "method_48725"))
                    || originalStructures != (Boolean) call(uiState, "method_48726")
                    || originalBonus != (Boolean) call(uiState, "method_48727"))
                throw new AssertionError("Native beforeCreate changed player world options");
            report.addProperty("nativeBeforeCreateRepairsForgedSelectionWithoutCreation", true);
        } finally {
            // Restore the exact creation-context references; never allocate a save
            // or invoke onCreate as part of this policy-only audit.
            field(uiState.getClass(), "field_42219").set(uiState, originalContext);
            field(uiState.getClass(), "field_42220").set(uiState, originalEntry);
        }
        report.addProperty("nativePolicyAuditAllocatedGameSave", false);
    }

    private static void assertPolicyOptions(Object state, long seed) throws Exception {
        Object options = call(call(state, "method_48728"), "comp_616");
        if (!Long.toString(seed).equals(call(state, "method_48725"))
                || (Boolean) call(state, "method_48726") || !(Boolean) call(state, "method_48727")
                || (Long) call(options, "method_28028") != seed
                || (Boolean) call(options, "method_28029") || !(Boolean) call(options, "method_28030"))
            throw new AssertionError("Native policy lost nondefault seed/structures/bonus options");
    }

    private static void requireRestricted(Object state) throws Exception {
        for (String getter : List.of("method_48732", "method_48733")) {
            List<?> list = (List<?>) call(state, getter);
            if (list.size() != 1 || !PRESET.equals(id(list.get(0))))
                throw new AssertionError("Native normal/Shift list is not restricted: " + getter);
        }
        if (!PRESET.equals(id(call(state, "method_48730"))))
            throw new AssertionError("Native selection is not repaired to " + PRESET);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> current = owner; current != null; current = current.getSuperclass())
            try { Field value = current.getDeclaredField(name); value.setAccessible(true); return value; }
            catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static String id(Object entry) throws Exception {
        Object holder = call(entry, "comp_1238");
        if (holder == null) return "<unavailable>";
        Optional<?> key = (Optional<?>) call(holder, "method_40230");
        return key.isEmpty() ? "<unbound>" : String.valueOf(call(key.get(), "method_29177"));
    }

    private static JsonObject metadata(ClassLoader loader, Path root) throws Exception {
        Codec<Object> codec = codec(loader, "net.minecraft.class_3272", "field_45051");
        JsonObject raw = read(root.resolve("pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack");
        Object decoded = value(codec.parse(JsonOps.INSTANCE, raw), "native pack metadata decode");
        int format = (Integer) call(decoded, "comp_1581");
        Optional<?> range = (Optional<?>) call(decoded, "comp_1582");
        if (format != 34 || range.isEmpty()
                || !(Boolean) call(range.get(), "method_37955", Integer.valueOf(34))
                || !(Boolean) call(range.get(), "method_37955", Integer.valueOf(48)))
            throw new AssertionError("Native metadata must support MC1.21.1 resource format34 and data format48");
        Object description = call(decoded, "comp_1580");
        if (String.valueOf(call(description, "getString")).isBlank())
            throw new AssertionError("Native pack metadata description is blank");
        JsonObject report = new JsonObject();
        report.addProperty("minecraft", "1.21.1");
        report.addProperty("nativePackMetadataSerializer", true);
        report.addProperty("resourcePackFormat", 34);
        report.addProperty("dataPackFormat", 48);
        return report;
    }

    @SuppressWarnings("unchecked")
    private static Codec<Object> codec(ClassLoader loader, String name, String field) throws Exception {
        return (Codec<Object>) type(loader, name).getField(field).get(null);
    }

    private static <T> T value(DataResult<T> result, String operation) {
        return result.result().orElseThrow(() -> new AssertionError(operation + ": " + result.error()));
    }

    private static Class<?> type(ClassLoader loader, String name) throws Exception { return Class.forName(name, true, loader); }

    private static Object call(Object object, String method, Object... arguments) throws Exception {
        return invoke(object.getClass(), object, method, arguments);
    }

    private static Object staticCall(Class<?> owner, String method, Object... arguments) throws Exception {
        return invoke(owner, null, method, arguments);
    }

    private static Object invoke(Class<?> owner, Object receiver, String name, Object[] arguments) throws Exception {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            boolean match = true;
            for (int i = 0; i < arguments.length; i++)
                if (arguments[i] != null && !method.getParameterTypes()[i].isInstance(arguments[i])) match = false;
            if (!match) continue;
            method.trySetAccessible();
            return method.invoke(receiver, arguments);
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static JsonElement read(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static void save(Path path, JsonObject report) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, report + "\n", StandardCharsets.UTF_8);
    }

    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
}
