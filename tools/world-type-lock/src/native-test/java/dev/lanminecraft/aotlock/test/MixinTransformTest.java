package dev.lanminecraft.aotlock.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

/** Native Fabric1.21.1 target transformation; no pretend Minecraft fixture classes. */
public final class MixinTransformTest {
    private static final String POLICY = "dev/lanminecraft/aotlock/WorldTypePolicy";
    private record Target(String name, String sha256, String method, String descriptor,
                          String policyHook, boolean cancellableHead) { }
    private static final List<Target> TARGETS = List.of(
            new Target("net.minecraft.class_8100",
                    "09c0c5381c97e4e7d9d4a8547844d626a9ac14432a4be5c5dcec3e9d85dab557",
                    "method_48735", "()V", "refresh", false),
            new Target("net.minecraft.class_525",
                    "89c4ee6012ae40d4c8a65f18e80a7e12d65fd50a368b7b2ff0b063d26ddea9d5",
                    "method_2736", "()V", "beforeCreate", true),
            new Target("net.minecraft.class_8100$class_8101",
                    "c2b56640654f6b798f547e93bdacd18b1b59bdf4d99f9d2fdb4eca3860fc6fc3",
                    "method_48737", "()Lnet/minecraft/class_2561;", "unavailableLabel", true));

    private MixinTransformTest() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Usage: MixinTransformTest OUTPUT_DIRECTORY");
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        checkConfig();
        MixinBootstrap.init();
        if (!(MixinService.getService() instanceof OfflineMixinService))
            throw new AssertionError("Real Mixin did not choose this test's offline service");
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("aot_world_type_lock.mixins.json");
        Mixins.addConfiguration("aot_safe_respawn.mixins.json");
        var type = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        IMixinTransformer transformer = (IMixinTransformer) constructor.newInstance();
        List<String> report = new ArrayList<>();
        auditConstructorPhaseGuard(report);
        auditSafeRespawn(transformer, output, report);
        for (Target target : TARGETS) {
            byte[] original = bytes(target.name());
            if (!hash(original).equals(target.sha256()))
                throw new AssertionError("Missing exact Fabric1.21.1 target bytes for " + target.name()
                        + "; classpath selected " + hash(original));
            byte[] transformed = transformer.transformClassBytes(target.name(), target.name(), original);
            if (transformed == null || Arrays.equals(original, transformed))
                throw new AssertionError("Required Mixin did not transform " + target.name());
            ClassNode before = node(original);
            ClassNode after = node(transformed);
            auditTarget(before, after, target, report);
            if (target.policyHook().equals("refresh")) auditConstructorFinish(before, after, report);
            Files.write(output.resolve(target.name().substring(target.name().lastIndexOf('.') + 1) + ".class"), transformed);
            report.add(target.name() + " original=" + hash(original) + " transformed=" + hash(transformed));
        }
        report.add("PASS: real installed Mixin transformed the four exact Fabric1.21.1 targets. This is offline hook placement proof, not a client startup/world-entry PASS.");
        Files.writeString(output.resolve("mixin-transform-report.txt"), String.join("\n", report) + "\n",
                StandardCharsets.UTF_8);
        report.forEach(System.out::println);
    }


    private static void auditSafeRespawn(IMixinTransformer transformer, Path output, List<String> report) throws Exception {
        String name="net.minecraft.class_3222";
        byte[] original=bytes(name);
        if(!hash(original).equals("5e273a05155e48ae753b22e364a646a0f2ee70930528aecf9b2a0760bf76ff07"))throw new AssertionError("Exact native ServerPlayer bytes changed");
        byte[] transformed=transformer.transformClassBytes(name,name,original);
        if(transformed==null||Arrays.equals(original,transformed))throw new AssertionError("Mandatory safe respawn Mixin did not transform ServerPlayer");
        ClassNode before=node(original),after=node(transformed);
        String descriptor="(ZLnet/minecraft/class_5454$class_9823;)Lnet/minecraft/class_5454;";
        MethodNode caller=method(after,"method_60590",descriptor);
        if(calls(method(before,"method_60590",descriptor),after.name,"method_60588")!=1||calls(caller,after.name,"method_60588")!=1)
            throw new AssertionError("Respawn validation target is not unique or was replaced");
        List<MethodNode> handlers=after.methods.stream().filter(m->calls(m,"dev/lanminecraft/aotlock/SafeRespawnPolicy","position")==1).toList();
        if(handlers.size()!=1||calls(caller,after.name,handlers.get(0).name)!=1)
            throw new AssertionError("Expected exactly one safe-respawn argument handler at the native respawn caller");
        if(callIndex(caller,after.name,handlers.get(0).name)>=callIndex(caller,after.name,"method_60588"))
            throw new AssertionError("Safe position must resolve before native validation");
        Files.write(output.resolve("class_3222-safe-respawn.class"),transformed);
        report.add("ServerPlayer.method_60590: exact native original="+hash(original)+" transformed="+hash(transformed)+"; sole method_60588 invocation preserved; exactly one Args handler before native validation. Native author guard/resolver require actual Fabric runtime proof.");
    }

    private static void auditConstructorFinish(ClassNode before, ClassNode after, List<String> report) {
        String descriptor = "(Ljava/nio/file/Path;Lnet/minecraft/class_7193;Ljava/util/Optional;Ljava/util/OptionalLong;)V";
        MethodNode original = method(before, "<init>", descriptor);
        MethodNode changed = method(after, "<init>", descriptor);
        List<MethodNode> handlers = after.methods.stream()
                .filter(candidate -> calls(candidate, POLICY, "finishInitialization") == 1).toList();
        if (handlers.size() != 1)
            throw new AssertionError("Expected exactly one merged constructor finish handler");
        MethodNode handler = handlers.get(0);
        if (calls(changed, after.name, handler.name) != 1)
            throw new AssertionError("Expected exactly one constructor finish hook");
        int hook = callIndex(changed, after.name, handler.name);
        for (String field : List.of("field_42216", "field_42217", "field_42218")) {
            int initialized = -1;
            for (int i = 0; i < changed.instructions.size(); i++) {
                if (changed.instructions.get(i) instanceof FieldInsnNode write
                        && write.getOpcode() == Opcodes.PUTFIELD && write.owner.equals(after.name)
                        && write.name.equals(field)) initialized = i;
            }
            if (initialized < 0 || hook <= initialized)
                throw new AssertionError("Constructor finish runs before seed/options field initialization: " + field);
        }
        long originalReturns = Arrays.stream(original.instructions.toArray())
                .filter(instruction -> instruction.getOpcode() == Opcodes.RETURN).count();
        long changedReturns = Arrays.stream(changed.instructions.toArray())
                .filter(instruction -> instruction.getOpcode() == Opcodes.RETURN).count();
        if (originalReturns != 1 || changedReturns != 1 || hook >= lastReturn(changed, Opcodes.RETURN))
            throw new AssertionError("Constructor finish must precede its sole RETURN");
        report.add("WorldCreationUiState.<init>: finishInitialization exactlyOnce=true at RETURN after native seed/generateStructures/bonusChest fields are initialized.");
    }

    private static void auditConstructorPhaseGuard(List<String> report) throws Exception {
        ClassNode policy = node(bytes(POLICY.replace('/', '.')));
        MethodNode refresh = method(policy, "refresh", "(Ljava/lang/Object;)Z");
        int seedLookup = -1;
        int nativeSetterName = -1;
        int nativeSetterCall = -1;
        for (int i = 0; i < refresh.instructions.size(); i++) {
            AbstractInsnNode instruction = refresh.instructions.get(i);
            if (instruction instanceof LdcInsnNode literal && "method_48725".equals(literal.cst)) seedLookup = i;
            if (instruction instanceof LdcInsnNode literal && "method_48705".equals(literal.cst)) nativeSetterName = i;
            if (nativeSetterName >= 0 && i > nativeSetterName && instruction instanceof MethodInsnNode call
                    && call.owner.equals(POLICY) && call.name.equals("callWithArgument")) nativeSetterCall = i;
        }
        if (seedLookup < 0 || nativeSetterName <= seedLookup || nativeSetterCall <= nativeSetterName)
            throw new AssertionError("Production refresh must inspect native constructor seed sentinel before setWorldType");
        boolean skipsEarlySetter = false;
        for (int i = seedLookup + 1; i < nativeSetterName; i++) {
            if (refresh.instructions.get(i) instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNULL
                    && refresh.instructions.indexOf(jump.label) > nativeSetterCall) skipsEarlySetter = true;
        }
        if (!skipsEarlySetter)
            throw new AssertionError("Null constructor seed sentinel does not skip native setWorldType/onChanged");
        MethodNode finish = policy.methods.stream().filter(candidate -> candidate.name.equals("finishInitialization"))
                .findFirst().orElseThrow(() -> new AssertionError("Missing production constructor completion policy"));
        if (calls(finish, POLICY, "refresh") != 1)
            throw new AssertionError("Constructor completion must invoke refresh exactly once");
        report.add("WorldTypePolicy.refresh: native null seed sentinel branches past setWorldType during partial construction; finishInitialization reapplies after constructor completion.");
    }

    private static void auditTarget(ClassNode before, ClassNode after, Target target, List<String> report) {
        MethodNode original = method(before, target.method(), target.descriptor());
        MethodNode changed = method(after, target.method(), target.descriptor());
        List<MethodNode> handlers = after.methods.stream()
                .filter(candidate -> calls(candidate, POLICY, target.policyHook()) == 1).toList();
        if (handlers.size() != 1)
            throw new AssertionError("Expected one merged policy handler for " + target.policyHook());
        MethodNode handler = handlers.get(0);
        if (calls(changed, after.name, handler.name) != 1)
            throw new AssertionError("Expected exactly one native-site policy handler call for " + target.method());
        int hook = callIndex(changed, after.name, handler.name);
        MethodInsnNode firstOriginalCall = Arrays.stream(original.instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode)
                .map(instruction -> (MethodInsnNode) instruction).findFirst().orElseThrow();
        int firstOriginal = callIndex(changed, firstOriginalCall.owner, firstOriginalCall.name);
        if (firstOriginal < 0) throw new AssertionError("Original native call disappeared: " + target.method());
        if (target.cancellableHead()) {
            if (hook >= firstOriginal)
                throw new AssertionError("Creation/label guard does not precede native code: " + target.method());
            int returnOpcode = target.descriptor().endsWith(")V") ? Opcodes.RETURN : Opcodes.ARETURN;
            boolean conditionalReturn = false;
            boolean cancellationCheck = false;
            for (int i = hook + 1; i < firstOriginal; i++) {
                AbstractInsnNode instruction = changed.instructions.get(i);
                conditionalReturn |= instruction.getOpcode() == returnOpcode;
                if (instruction instanceof MethodInsnNode call && call.name.equals("isCancelled")
                        && call.owner.startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo"))
                    cancellationCheck = true;
            }
            if (!conditionalReturn || !cancellationCheck)
                throw new AssertionError("Native HEAD guard cannot return before original effects: " + target.method());
            String callback = returnOpcode == Opcodes.RETURN ? "cancel" : "setReturnValue";
            boolean setsCancellation = Arrays.stream(handler.instructions.toArray())
                    .anyMatch(instruction -> instruction instanceof MethodInsnNode call
                            && call.owner.startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")
                            && call.name.equals(callback));
            if (!setsCancellation)
                throw new AssertionError("Merged handler does not cancel/set required result: " + target.method());
        } else {
            if (hook <= firstOriginal)
                throw new AssertionError("Refresh hook did not run after native list construction");
            long nativeReturns = Arrays.stream(original.instructions.toArray())
                    .filter(instruction -> instruction.getOpcode() == Opcodes.RETURN).count();
            long transformedReturns = Arrays.stream(changed.instructions.toArray())
                    .filter(instruction -> instruction.getOpcode() == Opcodes.RETURN).count();
            if (nativeReturns != 1 || transformedReturns != 1
                    || hook >= lastReturn(changed, Opcodes.RETURN))
                throw new AssertionError("Expected one TAIL refresh before the original sole return");
        }
        report.add(target.method() + target.descriptor() + " policy=" + target.policyHook()
                + " exactlyOnce=true placement=" + (target.cancellableHead() ? "HEAD/cancellable" : "TAIL"));
    }

    private static void checkConfig() throws Exception {
        String config;
        try (InputStream input = MixinTransformTest.class.getClassLoader().getResourceAsStream("aot_world_type_lock.mixins.json")) {
            if (input == null) throw new AssertionError("Missing production Mixin config");
            config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject value = JsonParser.parseString(config).getAsJsonObject();
        if (!value.get("required").getAsBoolean()
                || value.getAsJsonObject("injectors").get("defaultRequire").getAsInt() != 1)
            throw new AssertionError("Production Mixin injections must be mandatory");
        if (value.has("mixins") && !value.getAsJsonArray("mixins").isEmpty())
            throw new AssertionError("Client targets must not be in common/server mixin list");
        List<String> names = new ArrayList<>();
        value.getAsJsonArray("client").forEach(item -> names.add(item.getAsString()));
        if (!names.containsAll(List.of("WorldCreationUiStateMixin", "CreateWorldScreenMixin", "WorldTypeEntryMixin"))
                || names.size() != 3)
            throw new AssertionError("Expected exactly three client-only helper mixins");
    }

    private static int lastReturn(MethodNode method, int opcode) {
        for (int i = method.instructions.size() - 1; i >= 0; i--)
            if (method.instructions.get(i).getOpcode() == opcode) return i;
        return -1;
    }

    private static long calls(MethodNode method, String owner, String name) {
        return Arrays.stream(method.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name)).count();
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        for (int i = 0; i < method.instructions.size(); i++)
            if (method.instructions.get(i) instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) return i;
        return -1;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(value -> value.name.equals(name) && value.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError("Missing native method " + node.name + "." + name + descriptor));
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] bytes(String name) throws Exception {
        try (InputStream input = MixinTransformTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (input == null) throw new AssertionError("Missing actual native class " + name);
            return input.readAllBytes();
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
