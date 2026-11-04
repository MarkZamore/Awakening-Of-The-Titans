package dev.lanminecraft.aotqa.identity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/** Test-only native 1.21.1 Fabric skin observer. Never installed in mods/. */
public final class NativeIdentityProbe {
    private static long started;
    private static Object unknownInfo;
    private static Map<String, Object> passed;
    private static String referenceHash;
    private static BufferedImage reference;

    private NativeIdentityProbe() { }

    public static void tick(Object client, Object server, Object player, String report) throws Exception {
        Path evidence = Path.of(System.getProperty("lanminecraft.qa.evidence")).toAbsolutePath().normalize();
        Path qaRoot = Path.of(System.getProperty("lanminecraft.qa.root")).toAbsolutePath().normalize();
        require(Files.isRegularFile(qaRoot.resolve(".aot-runtime-qa.json")), "Not a marked isolated QA fixture");
        require(Path.of(report).toAbsolutePath().normalize().getParent().equals(evidence), "Unexpected report target");
        Path output = evidence.resolve("identity-skin-report.json");
        if (passed != null && (!Files.isRegularFile(evidence.resolve("peer-selected-skin.png"))
                || Boolean.TRUE.equals(passed.get("actualRemoteEntitySkinGpuVerified")))) { write(output, passed); return; }
        if (started == 0) started = System.nanoTime();

        UUID self = (UUID) call(player, "method_5667");
        String expectedSelf = System.getProperty("minecraft.portable.identity.localPlayer", "");
        require(!expectedSelf.isBlank() && self.equals(UUID.fromString(expectedSelf)), "Native self UUID differs from portable launch UUID");
        List<String[]> registered = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(System.getProperty("minecraft.portable.skin.registry")), StandardCharsets.UTF_8)) {
            String[] parts = line.split("\\|", 4);
            if (parts.length == 4) registered.add(parts);
        }
        String[] own = registered.stream().filter(row -> row[0].equalsIgnoreCase(self.toString())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Self has no production skin registry entry"));
        require(own[3].startsWith("http://127.0.0.1:"), "Expected the real local SkinService endpoint");
        if (reference == null) {
            byte[] png = Files.readAllBytes(evidence.resolve("selected-skin.png"));
            referenceHash = hex(MessageDigest.getInstance("SHA-256").digest(png));
            require(referenceHash.equalsIgnoreCase(own[1]), "Selected reference PNG differs from registry hash");
            reference = ImageIO.read(new ByteArrayInputStream(png));
            require(reference != null && reference.getWidth() == 64, "Invalid selected reference skin");
        }

        Object skin = call(player, "method_52814");
        String actualUrl = String.valueOf(call(skin, "comp_1911"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schemaVersion", 1); row.put("nativeMinecraft", "1.21.1");
        row.put("selfUuid", self.toString()); row.put("expectedSkinSha256", referenceHash);
        row.put("actualTextureUrlMatchesRegistry", own[3].equals(actualUrl));
        row.put("actualSteamGuestConnectionVerified", false);
        row.put("twoClientVisualCheckPerformed", false);
        row.put("syntheticPlayerInfoIsActualPeer", false);
        if (!own[3].equals(actualUrl)) {
            row.put("status", "PENDING"); row.put("reason", "Native asynchronous skin lookup has not selected registered texture yet");
            write(output, row);
            require(System.nanoTime() - started < 120_000_000_000L, "Native player never selected the launcher skin within 120s");
            return;
        }

        ClassLoader gameLoader = client.getClass().getClassLoader();
        verifyNativeProfileOwnership(gameLoader, self, row);
        row.putAll(gpuProof(client, skin, reference, own[2]));

        // Exercise the actual PlayerInfo lookup, without adding a fake entity to
        // the game or calling this a second client's skin/render observation.
        if (unknownInfo == null) {
            Class<?> profile = Class.forName("com.mojang.authlib.GameProfile", false, gameLoader);
            UUID unknown = UUID.fromString("00000000-0000-4000-8000-000000000099");
            require(registered.stream().noneMatch(entry -> entry[0].equalsIgnoreCase(unknown.toString())), "Unknown-profile control is registered");
            Object unknownProfile = profile.getConstructor(UUID.class, String.class).newInstance(unknown, "AotSkinControl");
            Class<?> info = Class.forName("net.minecraft.class_640", false, gameLoader);
            unknownInfo = info.getConstructor(profile, boolean.class).newInstance(unknownProfile, false);
        }
        Object unknownSkin = call(unknownInfo, "method_52810");
        String unknownUrl = String.valueOf(call(unknownSkin, "comp_1911"));
        require(!own[3].equals(unknownUrl), "Unknown native player inherited local selected skin");
        row.put("status", "PASS"); row.put("nativeSelfSkinSelected", true);
        row.put("unknownNativePlayerInfoDidNotInheritSelfSkin", true);
        row.put("authorSkinMixinsLeftEnabled", true);
        boolean requirePeer = Files.isRegularFile(evidence.resolve("peer-selected-skin.png"));
        if (requirePeer) {
            byte[] peerPng = Files.readAllBytes(evidence.resolve("peer-selected-skin.png"));
            String peerHash = hex(MessageDigest.getInstance("SHA-256").digest(peerPng));
            BufferedImage peerReference = ImageIO.read(new ByteArrayInputStream(peerPng));
            require(peerReference != null && peerReference.getWidth() == 64, "Invalid peer reference PNG");
            Object world = call(player, "method_37908");
            List<?> players = (List<?>) call(world, "method_18456");
            var observations = new ArrayList<Map<String, Object>>();
            for (Object visible : players) {
                var observed = new LinkedHashMap<String, Object>();
                observed.put("nativeClass", visible.getClass().getName());
                try {
                    UUID id = (UUID) call(visible, "method_5667");
                    Object profile = call(visible, "method_7334");
                    String[] entry = registered.stream().filter(value -> value[0].equalsIgnoreCase(id.toString())).findFirst().orElse(null);
                    Object nativeSkin = call(visible, "method_52814");
                    String url = String.valueOf(call(nativeSkin, "comp_1911"));
                    observed.put("entityUuid", id.toString()); observed.put("profileUuid", String.valueOf(call(profile, "getId")));
                    observed.put("profileName", String.valueOf(call(profile, "getName")));
                    observed.put("x", call(visible, "method_23317")); observed.put("y", call(visible, "method_23318")); observed.put("z", call(visible, "method_23321"));
                    observed.put("registered", entry != null); observed.put("nativeSkinUrl", url);
                    observed.put("nativeModel", String.valueOf(call(nativeSkin, "comp_1629")));
                    observed.put("nativeTextureIdentifier", String.valueOf(call(nativeSkin, "comp_1626")));
                    observed.put("nativeUrlMatchesRegistry", entry != null && entry[3].equals(url));
                    observed.put("playerInfoDiagnostic", playerInfoDiagnostic(visible));
                } catch (Throwable failure) { observed.put("observationException", failure.toString()); }
                observations.add(observed);
            }
            row.put("actualClientWorldPlayers", observations);
            row.put("actualClientWorldPlayerCount", players.size());
            boolean observed = false;
            for (Object remote : players) {
                UUID id = (UUID) call(remote, "method_5667");
                if (self.equals(id)) continue;
                String[] entry = registered.stream().filter(value -> value[0].equalsIgnoreCase(id.toString())).findFirst().orElse(null);
                if (entry == null) continue;
                require(peerHash.equalsIgnoreCase(entry[1]), "Actual connected peer is bound to the wrong reference PNG");
                require(!entry[1].equalsIgnoreCase(own[1]), "Two-client isolation control requires distinct selected skins");
                Object remoteSkin = call(remote, "method_52814");
                if (!entry[3].equals(String.valueOf(call(remoteSkin, "comp_1911")))) continue;
                Map<String, Object> proof = new LinkedHashMap<>(gpuProof(client, remoteSkin, peerReference, entry[2]));
                proof.put("playerUuid", id.toString()); proof.put("skinSha256", peerHash);
                proof.put("actualRemotePlayerEntity", true); proof.put("textureUrlMatchesOwnPeerRegistryEntry", true);
                row.put("remotePeerProof", proof); observed = true; break;
            }
            row.put("actualRemoteEntitySkinGpuVerified", observed);
            if (!observed) {
                row.put("status", "PASS_SELF_AWAITING_ACTUAL_PEER");
                row.put("reason", "Self verified; registered actual peer entity and asynchronous peer texture still required");
                write(output, row); return;
            }
            row.put("twoActualClientPlayerEntitiesObserved", true);
        }
        row.put("scope", requirePeer
                ? "Actual local and connected remote player skin lookups, UUID ownership, distinct models and uploaded GPU texture pixels. No real Steam transport or two-client visual screenshot claim."
                : "Actual native self player skin lookup, model and GPU texture; unknown synthetic native PlayerInfo isolation. No two-client visual or real Steam guest claim.");
        passed = row; write(output, row);
    }

    private static Map<String, Object> playerInfoDiagnostic(Object visible) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        Class<?> base = Class.forName("net.minecraft.class_742", false, visible.getClass().getClassLoader());
        Object info = null;
        for (Method m : base.getDeclaredMethods()) if (m.getParameterCount() == 0 && m.getReturnType().getName().equals("net.minecraft.class_640")) {
            m.setAccessible(true); info = m.invoke(visible); break;
        }
        result.put("nativePlayerInfoPresent", info != null);
        if (info == null) return result;
        for (Method m : info.getClass().getDeclaredMethods()) if (m.getParameterCount() == 0 && m.getReturnType().getName().equals("com.mojang.authlib.GameProfile")) {
            m.setAccessible(true); Object profile = m.invoke(info);
            result.put("profileUuid", String.valueOf(call(profile, "getId"))); result.put("profileName", String.valueOf(call(profile, "getName")));
        }
        Object selected = call(info, "method_52810");
        result.put("selectedTextureIdentifier", String.valueOf(call(selected, "comp_1626")));
        result.put("selectedUrl", String.valueOf(call(selected, "comp_1911")));
        for (Field f : info.getClass().getDeclaredFields()) if (Supplier.class.isAssignableFrom(f.getType())) {
            f.setAccessible(true); Object supplier = f.get(info);
            if (supplier == null) continue;
            var futures = new ArrayList<Map<String, Object>>();
            for (Field captured : supplier.getClass().getDeclaredFields()) {
                captured.setAccessible(true); Object value = captured.get(supplier);
                if (value instanceof CompletableFuture<?> future) {
                    var snapshot = new LinkedHashMap<String, Object>();
                    snapshot.put("done", future.isDone()); snapshot.put("exceptional", future.isCompletedExceptionally());
                    if (future.isDone() && !future.isCompletedExceptionally()) {
                        Object candidate = future.getNow(null);
                        if (candidate instanceof Optional<?> optional) candidate = optional.orElse(null);
                        snapshot.put("candidatePresent", candidate != null);
                        if (candidate != null) {
                            snapshot.put("candidateType", candidate.getClass().getName());
                            if (candidate.getClass().getName().equals("net.minecraft.class_8685")) {
                                snapshot.put("candidateTextureIdentifier", String.valueOf(call(candidate, "comp_1626")));
                                snapshot.put("candidateUrl", String.valueOf(call(candidate, "comp_1911")));
                                snapshot.put("candidateSecure", call(candidate, "comp_1630"));
                            }
                        }
                    }
                    futures.add(snapshot);
                }
            }
            result.put("capturedNativeFutures", futures);
        }
        return result;
    }

    private static Map<String, Object> gpuProof(Object client, Object skin, BufferedImage expectedImage, String expectedModel) throws Exception {
        String modelName = ((Enum<?>) call(skin, "comp_1629")).name();
        require(("slim".equals(expectedModel)) == "SLIM".equals(modelName), "Native selected skin model differs from launcher model");
        Object location = call(skin, "comp_1626");
        Object texture = call(call(client, "method_1531"), "method_4619", location);
        int textureId = (Integer) call(texture, "method_4624");
        Class<?> gl = Class.forName("org.lwjgl.opengl.GL11", false, client.getClass().getClassLoader());
        require((Boolean) gl.getMethod("glIsTexture", int.class).invoke(null, textureId), "Selected native skin has no GPU texture");
        int oldBinding = (Integer) gl.getMethod("glGetInteger", int.class).invoke(null, 0x8069);
        int width, height, opaque = 0, mismatches = 0;
        try {
            gl.getMethod("glBindTexture", int.class, int.class).invoke(null, 0x0DE1, textureId);
            Method parameter = gl.getMethod("glGetTexLevelParameteri", int.class, int.class, int.class);
            width = (Integer) parameter.invoke(null, 0x0DE1, 0, 0x1000);
            height = (Integer) parameter.invoke(null, 0x0DE1, 0, 0x1001);
            require(width == 64 && height == 64, "Native uploaded skin is not a 64x64 skin texture");
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
            gl.getMethod("glGetTexImage", int.class, int.class, int.class, int.class, ByteBuffer.class)
                    .invoke(null, 0x0DE1, 0, 0x1908, 0x1401, pixels);
            for (int y = 0; y < expectedImage.getHeight(); y++) for (int x = 0; x < 64; x++) {
                int expected = expectedImage.getRGB(x, y);
                // Native skin processing normalizes alpha and expands legacy
                // layouts. Compare only unchanged opaque original RGB pixels.
                if ((expected >>> 24) != 255) continue;
                opaque++;
                int at = (y * width + x) * 4;
                int actual = ((pixels.get(at) & 255) << 16) | ((pixels.get(at + 1) & 255) << 8) | (pixels.get(at + 2) & 255);
                if ((expected & 0xffffff) != actual) mismatches++;
            }
        } finally { gl.getMethod("glBindTexture", int.class, int.class).invoke(null, 0x0DE1, oldBinding); }
        require(opaque > 0 && mismatches == 0, "Native uploaded selected skin pixels differ: " + mismatches + "/" + opaque);
        return Map.of("model", modelName, "gpuTextureExists", true,
                "gpuTextureWidth", width, "gpuTextureHeight", height,
                "opaqueRgbPixelsCompared", opaque, "opaqueRgbMismatches", mismatches);
    }

    private static void verifyNativeProfileOwnership(ClassLoader loader, UUID self, Map<String, Object> report) throws Exception {
        Class<?> profileType = Class.forName("com.mojang.authlib.GameProfile", false, loader);
        Constructor<?> constructor = profileType.getConstructor(UUID.class, String.class);
        Method getId = profileType.getMethod("getId");
        boolean checked = false;
        for (String line : Files.readAllLines(Path.of(System.getProperty("minecraft.portable.identity.registry")), StandardCharsets.UTF_8)) {
            String[] entry = line.split("\\|", 3);
            if (entry.length < 2 || !self.toString().equalsIgnoreCase(entry[1])) continue;
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + entry[0]).getBytes(StandardCharsets.UTF_8));
            Object byName = constructor.newInstance(offline, entry[0]);
            require(self.equals(getId.invoke(byName)), "Actual authlib offline profile constructor lost portable ownership");
            report.put("nativeOfflineProfileMappedToPortableUuid", true);
            if (entry.length == 3 && !entry[2].isBlank()) {
                Object tunnel = constructor.newInstance(UUID.fromString(entry[2]), "AotTunnelAlias");
                require(self.equals(getId.invoke(tunnel)), "Actual authlib Steam tunnel profile constructor lost portable ownership");
                report.put("nativeSteamTunnelUuidMappedToPortableUuid", true);
            } else report.put("nativeSteamTunnelUuidMappedToPortableUuid", false);
            checked = true;
        }
        require(checked, "Self is absent from production identity registry");
        String strangerName = "AotStranger";
        UUID strangerId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + strangerName).getBytes(StandardCharsets.UTF_8));
        Object stranger = constructor.newInstance(strangerId, strangerName);
        require(strangerId.equals(getId.invoke(stranger)), "Unknown offline profile was remapped into another player's ownership");
        report.put("unknownOfflineProfileOwnershipUnchanged", true);
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (Class<?> owner = target.getClass(); owner != null; owner = owner.getSuperclass()) {
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                boolean compatible = true;
                for (int i = 0; i < arguments.length; i++) if (arguments[i] != null && !box(method.getParameterTypes()[i]).isInstance(arguments[i])) compatible = false;
                if (compatible) { method.setAccessible(true); return method.invoke(target, arguments); }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
    private static Class<?> box(Class<?> type) { return type == int.class ? Integer.class : type == boolean.class ? Boolean.class : type; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
    private static void write(Path path, Map<String, Object> value) throws Exception {
        Class<?> gson = Class.forName("com.google.gson.Gson");
        Object serializer = gson.getConstructor().newInstance();
        String json = (String) gson.getMethod("toJson", Object.class).invoke(serializer, value);
        Files.writeString(path, json + "\n", StandardCharsets.UTF_8);
    }
}
