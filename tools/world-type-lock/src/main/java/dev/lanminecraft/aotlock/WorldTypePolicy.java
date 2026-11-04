package dev.lanminecraft.aotlock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Client-only policy reached exclusively by the client mixins. Fabric intermediary names
 * are pinned to Minecraft1.21.1/Fabric0.19.5 and verified against their real bytes.
 * Reflection lets the deterministic CI build avoid redistributing Minecraft jars.
 */
public final class WorldTypePolicy {
    public static final String PRESET_ID = "attack_on_titan:paradis";
    private static final Map<Object, String> failures = Collections.synchronizedMap(new WeakHashMap<>());
    private static final List<WeakReference<Object>> unavailable = new ArrayList<>();

    private WorldTypePolicy() { }

    /** Rebuild both ordinary and Shift lists after native initialization/reload. */
    public static boolean refresh(Object state) {
        try {
            Object entry = requiredEntry(state);
            if (entry == null) {
                markUnavailable(state, "required-preset-missing");
                return false;
            }
            replaceLists(state, entry);
            // Native construction refreshes lists before initializing seed,
            // structure and bonus-chest fields. Calling setWorldType there
            // invokes onChanged and would overwrite options with false defaults.
            // The mandatory constructor-RETURN hook applies after initialization.
            if (call(state, "method_48725") != null)
                callWithArgument(state, "method_48705", entry);
            failures.remove(state);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            markUnavailable(state, "native-api-unavailable:" + failure.getClass().getSimpleName());
            return false;
        }
    }

    public static void finishInitialization(Object state) {
        refresh(state);
    }

    /** Called before onCreate reads/bakes dimensions or allocates any save directory. */
    public static boolean beforeCreate(Object screen) {
        try {
            Object state = call(screen, "method_48657"); // getUiState
            if (call(state, "method_48725") == null)
                throw new IllegalStateException("World creation state is not initialized");
            if (refresh(state)) return true;
            showFailure(screen);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            System.err.println("[AotWorldTypeLock] Creation refused: " + failure.getClass().getSimpleName());
            try { showFailure(screen); }
            catch (ReflectiveOperationException | RuntimeException alertFailure) {
                System.err.println("[AotWorldTypeLock] Error screen unavailable: "
                        + alertFailure.getClass().getSimpleName());
            }
        }
        return false;
    }

    /** Returns a component only for our unbound unavailable placeholder. */
    public static Object unavailableLabel(Object entry) {
        if (!isUnavailable(entry)) return null;
        try {
            return translated(entry.getClass().getClassLoader(), "aot_world_type_lock.unavailable");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot label the unavailable required world preset", failure);
        }
    }

    private static Object requiredEntry(Object state) throws ReflectiveOperationException {
        Object settings = call(state, "method_48728");
        Object registryAccess = call(settings, "method_45689");
        ClassLoader loader = state.getClass().getClassLoader();
        Class<?> registryKeys = Class.forName("net.minecraft.class_7924", false, loader);
        Object presetRegistryKey = publicField(registryKeys, "field_41250").get(null);
        Object registry = callWithArgument(registryAccess, "method_30530", presetRegistryKey);
        Set<?> entries = (Set<?>) call(registry, "method_29722");
        for (Object value : entries) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) value;
            if (PRESET_ID.equals(String.valueOf(call(entry.getKey(), "method_29177")))) {
                Optional<?> holder = (Optional<?>) callWithArgument(registry, "method_40264", entry.getKey());
                if (holder.isPresent()) return newEntry(state, holder.get());
            }
        }
        return null;
    }

    private static Object newEntry(Object state, Object holder) throws ReflectiveOperationException {
        Class<?> entryClass = Class.forName(
                "net.minecraft.class_8100$class_8101",
                false, state.getClass().getClassLoader());
        Class<?> holderClass = Class.forName("net.minecraft.class_6880", false, entryClass.getClassLoader());
        return entryClass.getConstructor(holderClass).newInstance(holder);
    }

    private static void markUnavailable(Object state, String reason) {
        String oldReason = failures.put(state, reason);
        if (!reason.equals(oldReason))
            System.err.println("[AotWorldTypeLock] Required preset unavailable; new world creation is blocked ("
                    + reason + ").");
        try {
            Object placeholder = newEntry(state, null);
            synchronized (unavailable) {
                unavailable.removeIf(reference -> reference.get() == null);
                unavailable.add(new WeakReference<>(placeholder));
            }
            replaceLists(state, placeholder);
            // Null-holder native entries do not replace dimensions. They cannot be
            // created: beforeCreate independently resolves the required holder.
            callWithArgument(state, "method_48705", placeholder);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // An unexpected API mismatch must still fail closed in beforeCreate.
            System.err.println("[AotWorldTypeLock] Unable to display the unavailable placeholder: "
                    + failure.getClass().getSimpleName());
        }
    }

    private static boolean isUnavailable(Object entry) {
        synchronized (unavailable) {
            unavailable.removeIf(reference -> reference.get() == null);
            return unavailable.stream().anyMatch(reference -> reference.get() == entry);
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceLists(Object state, Object entry) throws ReflectiveOperationException {
        for (String getter : List.of("method_48732", "method_48733")) {
            List<Object> list = (List<Object>) call(state, getter);
            list.clear();
            list.add(entry);
        }
    }

    private static void showFailure(Object previousScreen) throws ReflectiveOperationException {
        ClassLoader loader = previousScreen.getClass().getClassLoader();
        Class<?> minecraftClass = Class.forName("net.minecraft.class_310", false, loader);
        Object minecraft = publicMethod(minecraftClass, "method_1551").invoke(null);
        Class<?> screenClass = Class.forName("net.minecraft.class_437", false, loader);
        Method setScreen = minecraftClass.getMethod("method_1507", screenClass);
        Runnable returnToCreation = () -> {
            try { setScreen.invoke(minecraft, previousScreen); }
            catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot return to world creation", failure);
            }
        };
        Class<?> componentClass = Class.forName("net.minecraft.class_2561", false, loader);
        Class<?> alertClass = Class.forName("net.minecraft.class_403", false, loader);
        Constructor<?> constructor = alertClass.getConstructor(Runnable.class, componentClass, componentClass);
        Object alert = constructor.newInstance(returnToCreation,
                translated(loader, "aot_world_type_lock.error.title"),
                translated(loader, "aot_world_type_lock.error.body"));
        setScreen.invoke(minecraft, alert);
    }

    private static Object translated(ClassLoader loader, String key) throws ReflectiveOperationException {
        Class<?> component = Class.forName("net.minecraft.class_2561", false, loader);
        return component.getMethod("method_43471", String.class).invoke(null, key);
    }

    private static Object call(Object receiver, String name) throws ReflectiveOperationException {
        return publicMethod(receiver.getClass(), name).invoke(receiver);
    }

    private static Object callWithArgument(Object receiver, String name, Object argument)
            throws ReflectiveOperationException {
        for (Method method : receiver.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && (argument == null || method.getParameterTypes()[0].isInstance(argument))) {
                method.trySetAccessible();
                return method.invoke(receiver, argument);
            }
        }
        throw new NoSuchMethodException(receiver.getClass().getName() + "." + name);
    }

    private static Method publicMethod(Class<?> type, String name) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        method.trySetAccessible();
        return method;
    }

    private static Field publicField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getField(name);
        field.trySetAccessible();
        return field;
    }
}
