import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Test-only native lifecycle/observation requests. No bytecode transformation. */
public final class AotQaAgent {
    private final Path root, game, evidence, ready, stop;
    private final String freshWorld;
    private boolean created, modsRecorded, loggingRecorded;
    private int menuSamples;

    private AotQaAgent() throws Exception {
        root = Path.of(required("root")).toRealPath();
        boolean underArtifacts = false;
        for (Path component : root) if (component.toString().equalsIgnoreCase("artifacts")) underArtifacts = true;
        if (!underArtifacts) throw new SecurityException("QA root must stay below artifacts");
        if (!Files.isRegularFile(root.resolve(".aot-runtime-qa.json")))
            throw new SecurityException("Not a marked Attack on Titan QA fixture");
        game = child(required("game")); evidence = child(required("evidence"));
        ready = evidence.resolve("ready.flag"); stop = evidence.resolve("stop.flag");
        freshWorld = System.getProperty("lanminecraft.qa.freshWorld");
        if (freshWorld != null && !freshWorld.matches("AotQa[A-Za-z0-9_-]{0,48}"))
            throw new SecurityException("Unsafe QA world name");
        if (freshWorld != null && Files.exists(game.resolve("saves").resolve(freshWorld), LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("Fresh QA world already exists");
        if (Files.exists(stop)) throw new SecurityException("Refusing a stopped evidence run");
        event("waiting_for_coordinator", Map.of("classTransformation", false,
            "gameReflectionBeforeReady", false, "freshWorldRequested", freshWorld != null));
    }

    public static void premain(String ignored, Instrumentation instrumentation) {
        try {
            var agent = new AotQaAgent();
            var worker = new Thread(() -> agent.run(instrumentation), "AOT-QA-lifecycle");
            worker.setDaemon(true); worker.start();
        } catch (Throwable failure) { System.err.println("[AOT QA] Agent refused: " + failure); }
    }

    public static void agentmain(String ignored, Instrumentation instrumentation) { premain(ignored,instrumentation); }

    private void run(Instrumentation instrumentation) {
        var originalContext = Thread.currentThread().getContextClassLoader();
        try {
            Class<?> minecraft = null;
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(45);
            while (System.nanoTime() < deadline) {
                // Instrumentation enumeration and game lookup happen only after
                // the coordinator has observed a fully loaded native title menu.
                if (!Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS)) { Thread.sleep(1000); continue; }
                if (minecraft == null) {
                    for (Class<?> loaded : instrumentation.getAllLoadedClasses())
                        if (loaded.getName().equals("net.minecraft.class_310")) {
                            Thread.currentThread().setContextClassLoader(loaded.getClassLoader());
                            minecraft = loaded; break;
                        }
                }
                if (minecraft != null) {
                    Object client = method(minecraft, "method_1551").invoke(null);
                    if (client != null) {
                        var result = new CompletableFuture<Boolean>();
                        ((Executor) client).execute(() -> {
                            var previous = Thread.currentThread().getContextClassLoader();
                            try {
                                Thread.currentThread().setContextClassLoader(client.getClass().getClassLoader());
                                result.complete(inspect(client));
                            } catch (Throwable failure) { result.completeExceptionally(failure); }
                            finally { Thread.currentThread().setContextClassLoader(previous); }
                        });
                        // Native datapack loading/world creation can hold the
                        // client request for minutes; a short observation timeout
                        // must not terminate the lifecycle worker mid-creation.
                        while (!result.isDone()) {
                            if (System.nanoTime() >= deadline) throw new TimeoutException("Native client request exceeded QA deadline");
                            Thread.sleep(250);
                        }
                        if (result.get()) return;
                    }
                }
                Thread.sleep(3000);
            }
            event("observation_timeout", Map.of("worldOrSteamPassClaimed", false));
        } catch (Throwable failure) {
            while ((failure instanceof InvocationTargetException || failure instanceof ExecutionException) && failure.getCause() != null)
                failure = failure.getCause();
            try { event("agent_failure", Map.of("exception", failure.getClass().getName(), "message", String.valueOf(failure.getMessage()))); }
            catch (IOException ignored) { }
        } finally { Thread.currentThread().setContextClassLoader(originalContext); }
    }

    private boolean inspect(Object client) throws Exception {
        Class<?> mc = client.getClass();
        File actual = (File) field(mc, "field_1697").get(client);
        if (!actual.toPath().toRealPath().equals(game)) throw new SecurityException("Wrong actual game directory");
        if (Files.isRegularFile(stop, LinkOption.NOFOLLOW_LINKS)) {
            event("native_stop_requested", Map.of("method", "Minecraft.method_1592", "forcedKill", false));
            method(mc, "method_1592").invoke(client);
            return true;
        }
        Object player = field(mc, "field_1724").get(client);
        Object level = field(mc, "field_1687").get(client);
        Object screen = field(mc, "field_1755").get(client);
        Object overlay = method(mc, "method_18506").invoke(client);
        Object server = method(mc, "method_1576").invoke(client);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("playerPresent", player != null); state.put("levelPresent", level != null);
        state.put("screenClass", className(screen)); state.put("overlayClass", className(overlay));
        state.put("integratedServerPresent", server != null);
        if (server != null) {
            String aliases = System.getProperty("minecraft.portable.identity.isPublishedMethods", "method_3860");
            for (String alias : aliases.split(",")) try {
                state.put("serverPublished", method(server.getClass(), alias.trim()).invoke(server)); break;
            } catch (NoSuchMethodException ignored) { }
        }
        state.put("nativeTitleFactory",method(mc,"method_24287").invoke(client));
        state.put("windowTitleEnabled",System.getProperty("minecraft.portable.identity.windowTitleEnabled"));
        state.put("actualSteamGuestConnectionVerified", false);
        event("client_state", state);
        if(level!=null && !loggingRecorded){
            var row=new LinkedHashMap<String,Object>();row.put("jvmSelector",System.getProperty("log4j.configurationFile"));
            Class<?> manager=Class.forName("org.apache.logging.log4j.LogManager",false,mc.getClassLoader());
            Object context=manager.getMethod("getContext",boolean.class).invoke(null,false);
            Object config=method(context.getClass(),"getConfiguration").invoke(context);
            Object source=method(config.getClass(),"getConfigurationSource").invoke(config);
            row.put("configurationSource",String.valueOf(source));
            Object lc=method(config.getClass(),"getLoggerConfig",String.class).invoke(config,"net.minecraft.class_2535");
            row.put("connectionLogger",method(lc.getClass(),"getName").invoke(lc));
            row.put("connectionLevel",String.valueOf(method(lc.getClass(),"getLevel").invoke(lc)));
            row.put("connectionFilter",String.valueOf(method(lc.getClass(),"getFilter").invoke(lc)));
            event("live_logging_configuration",row);loggingRecorded=true;
        }
        if (level != null) {
            Object key = method(level.getClass(), "method_27983").invoke(level);
            event("world_dimension", Map.of("id", String.valueOf(method(key.getClass(), "method_29177").invoke(key))));
        }
        if(screen!=null && screen.getClass().getName().equals("net.minecraft.class_405")){
            // Verified 1.21.1 BackupConfirmScreen's native skip-backup button;
            // this is a disposable marked QA save, never a personal backup.
            Class<?> button=Class.forName("net.minecraft.class_4185",false,mc.getClassLoader());
            Method skip=screen.getClass().getDeclaredMethod("method_19792",button);skip.setAccessible(true);
            skip.invoke(screen,new Object[]{null});
            event("native_qa_backup_confirmation",Map.of("backupCreated",false,"personalWorld",false));return false;
        }
        if(screen!=null && screen.getClass().getName().equals("net.minecraft.class_8032")){
            Object callback=field(screen.getClass(),"field_46008").get(screen);
            method(screen.getClass(),"method_53552",boolean.class,Runnable.class).invoke(screen,true,callback);
            event("native_accessibility_onboarding_completed",Map.of("qaOptionsOnly",true));
        }
        if(worldCreateInvoked && screen!=null && screen.getClass().getName().equals("net.minecraft.class_410")) {
            for(Field f:screen.getClass().getDeclaredFields())if(f.getType().getName().equals("it.unimi.dsi.fastutil.booleans.BooleanConsumer")){
                f.setAccessible(true);Object callback=f.get(screen);
                Class.forName("it.unimi.dsi.fastutil.booleans.BooleanConsumer",false,mc.getClassLoader()).getMethod("accept",boolean.class).invoke(callback,true);
                event("native_experimental_confirmation",Map.of("accepted",true));break;
            }
        }
        runAction(client, server, player);
        if(gameplayEnabled && server!=null && player!=null) gameplayTick(client,server,player);
        if(identityEnabled && player!=null) extraProbe("identity",client,server,player);
        if(mapsEnabled && player!=null) extraProbe("maps",client,server,player);
        if(progressEnabled && server!=null && player!=null) extraProbe("progress",client,server,player);
        if(pairedEnabled && server!=null && player!=null) extraProbe("paired",client,server,player);
        if (!modsRecorded) {
            Class<?> fabric = Class.forName("net.fabricmc.loader.api.FabricLoader", false, mc.getClassLoader());
            Object fl = fabric.getMethod("getInstance").invoke(null);
            var ids = new TreeMap<String, String>();
            for (Object container : (Iterable<?>) fabric.getMethod("getAllMods").invoke(fl)) {
                Object info = Class.forName("net.fabricmc.loader.api.ModContainer",false,mc.getClassLoader()).getMethod("getMetadata").invoke(container);
                String id = String.valueOf(Class.forName("net.fabricmc.loader.api.metadata.ModMetadata",false,mc.getClassLoader()).getMethod("getId").invoke(info));
                Object version = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata",false,mc.getClassLoader()).getMethod("getVersion").invoke(info);
                String ver = String.valueOf(Class.forName("net.fabricmc.loader.api.Version",false,mc.getClassLoader()).getMethod("getFriendlyString").invoke(version));
                if (ids.putIfAbsent(id, ver) != null) throw new IllegalStateException("Duplicate loaded mod ID " + id);
            }
            event("loaded_mod_ids", Map.of("count", ids.size(), "ids", ids)); modsRecorded = true;
        }
        if (created && screen != null && screen.getClass().getName().equals("net.minecraft.class_525")) {
            Object uiState = method(screen.getClass(), "method_48657").invoke(screen);
            if (!worldCreateInvoked) {
                method(uiState.getClass(), "method_48710", String.class).invoke(uiState, freshWorld);
                method(uiState.getClass(), "method_48713", boolean.class).invoke(uiState, true);
                Class<?> mode=Class.forName("net.minecraft.class_8100$class_4539",false,mc.getClassLoader());
                method(uiState.getClass(),"method_48704",mode).invoke(uiState,mode.getField("field_20626").get(null));

                presetState(uiState, "before_create");
                verifyCodec(uiState);
                if (Files.isRegularFile(evidence.resolve("hold-create.flag"))) return false;
                worldCreateInvoked = true;
                event("native_create_button_requested", Map.of("world", freshWorld));
                method(screen.getClass(), "method_2736").invoke(screen);
                event("native_create_button_returned", Map.of("joinedWorldVerified", false));
            }
        }
        if (freshWorld == null || created) return false;
        boolean title = screen != null && Class.forName("net.minecraft.class_442", false, mc.getClassLoader()).isInstance(screen);
        if (!title || overlay != null) { menuSamples = 0; return false; }
        if (player != null || level != null || server != null) throw new SecurityException("Refusing fresh creation with active world");
        if (++menuSamples < 3) return false;
        createFresh(client);
        return false;
    }

    private boolean worldCreateInvoked, gameplayEnabled, identityEnabled, mapsEnabled, progressEnabled, pairedEnabled;
    private final Map<String,Method> extraProbes=new HashMap<>();
    private void extraProbe(String name,Object client,Object server,Object player)throws Exception {
        Method m=extraProbes.get(name);
        if(m==null){
            String jar=required(name+"ProbeJar");
            var loader=new java.net.URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()},client.getClass().getClassLoader());
            Class<?> probe=Class.forName(required(name+"ProbeClass"),true,loader);
            m=probe.getMethod("tick",Object.class,Object.class,Object.class,String.class);extraProbes.put(name,m);
        }
        m.invoke(null,client,server,player,evidence.resolve(name+"-report.json").toString());
    }
    private Method gameplay;
    private void gameplayTick(Object client,Object server,Object player)throws Exception{
        if(gameplay==null){
            String jar=required("probeJar");
            var loader=new java.net.URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()},client.getClass().getClassLoader());
            Class<?> probe=Class.forName(System.getProperty("lanminecraft.qa.probeClass","AotGameplayProbe"),true,loader);
            gameplay=probe.getMethod("tick",Object.class,Object.class,Object.class,String.class);
        }
        gameplay.invoke(null,client,server,player,evidence.resolve("gameplay-report.json").toString());
    }

    private void verifyCodec(Object state) throws Exception {
        String jar=System.getProperty("lanminecraft.qa.codecJar");
        if(jar==null)return;
        var loader=new java.net.URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, state.getClass().getClassLoader());
        Class<?> proof=Class.forName(System.getProperty("lanminecraft.qa.codecClass", "dev.lanminecraft.aotlock.test.NativeResourcesTest"),true,loader);
        proof.getMethod("verify",Object.class,String.class,String.class,String.class).invoke(null,state,
            required("codecResources"), required("codecAuthorJar"),evidence.resolve("native-codec-report.json").toString());
        event("native_codec_verified",Map.of("report","native-codec-report.json"));
    }

    private void createFresh(Object client) throws Exception {
        if (Files.exists(game.resolve("saves").resolve(freshWorld), LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("Fresh world collision before native creation");
        ClassLoader loader = client.getClass().getClassLoader();
        Class<?> create = Class.forName("net.minecraft.class_525", false, loader);
        Class<?> screen = Class.forName("net.minecraft.class_437", false, loader);
        Object parent = field(client.getClass(), "field_1755").get(client);
        created = true;
        event("native_create_screen_requested", Map.of("world", freshWorld, "method", "CreateWorldScreen.method_31130", "nbtTemplatePatched", false));
        method(create, "method_31130", client.getClass(), screen).invoke(null, client, parent);
    }

    private void presetState(Object state, String phase) throws Exception {
        Object normal = method(state.getClass(), "method_48732").invoke(state);
        Object extended = method(state.getClass(), "method_48733").invoke(state);
        Object selected = method(state.getClass(), "method_48730").invoke(state);
        var row = new LinkedHashMap<String,Object>();
        row.put("phase", phase); row.put("normal", String.valueOf(normal)); row.put("extended",String.valueOf(extended)); row.put("selected", String.valueOf(selected));
        row.put("seed", method(state.getClass(), "method_48725").invoke(state));
        row.put("structures", method(state.getClass(), "method_48726").invoke(state));
        row.put("bonusChest", method(state.getClass(), "method_48727").invoke(state));
        event("preset_state", row);
    }

    private void runAction(Object client, Object server, Object player) throws Exception {
        Path action = evidence.resolve("action.txt");
        if (!Files.isRegularFile(action, LinkOption.NOFOLLOW_LINKS)) return;
        String input = Files.readString(action).trim();
        if(input.equals("publish")&&(server==null||player==null)){
            event("qa_action_deferred",Map.of("action","publish","reason","actual_player_and_server_required"));
            return;
        }
        Files.move(action, evidence.resolve("action-"+System.nanoTime()+".txt"));
        event("action_requested", Map.of("action",input));
        if(input.startsWith("probe ")){
            String[] parts=input.substring(6).split("\\|",3);
            if(parts.length!=3||!Set.of("identity","maps","progress","paired").contains(parts[0]))throw new IllegalArgumentException("Invalid probe configuration");
            Path jar=Path.of(parts[1]).toRealPath();if(!jar.toString().contains("artifacts"))throw new SecurityException("QA probe must stay in artifacts");
            System.setProperty("lanminecraft.qa."+parts[0]+"ProbeJar",jar.toString());System.setProperty("lanminecraft.qa."+parts[0]+"ProbeClass",parts[2]);
            if(extraProbes.containsKey(parts[0]))throw new IllegalStateException("Probe already loaded");
            if(parts[0].equals("identity"))identityEnabled=true;else if(parts[0].equals("maps"))mapsEnabled=true;else if(parts[0].equals("progress"))progressEnabled=true;else pairedEnabled=true;
            event("native_probe_enabled",Map.of("name",parts[0]));
        } else if(input.startsWith("maps-receipt ")){
            Path path=Path.of(input.substring(13)).toAbsolutePath();if(!path.startsWith(root))throw new SecurityException("Receipt outside QA fixture");
            System.setProperty("aot.qa.mapsProbe.receipt",path.toString());event("maps_receipt_configured",Map.of("fixtureOnly",true));
        } else if(input.equals("local-tcp-fixture")){
            if(server==null || !(Boolean)method(server.getClass(),"method_3860").invoke(server))throw new IllegalStateException("Publish native QA LAN first");
            Object before=method(server.getClass(),"method_3828").invoke(server);
            method(server.getClass(),"method_3864",boolean.class).invoke(server,false);
            event("private_loopback_auth_fixture",Map.of("usesAuthenticationBefore",before,"usesAuthenticationAfter",method(server.getClass(),"method_3828").invoke(server),"actualSteamAuthenticationVerified",false,"productionConfigChanged",false));
        } else if(input.equals("oneclick-lan")){
            if(server==null)throw new IllegalStateException("No integrated server");
            Class<?> pauseClass=Class.forName("net.minecraft.class_433",false,client.getClass().getClassLoader());
            Object pause=pauseClass.getConstructor(boolean.class).newInstance(true);
            Class<?> screenClass=Class.forName("net.minecraft.class_437",false,client.getClass().getClassLoader());
            method(client.getClass(),"method_1507",screenClass).invoke(client,pause);
            Object share=field(pauseClass,"field_41627").get(null);boolean pressed=false;
            for(Object child:(List<?>)method(pauseClass,"method_25396").invoke(pause))try{
                Object message=method(child.getClass(),"method_25369").invoke(child);
                if(message.equals(share)){method(child.getClass(),"method_25306").invoke(child);pressed=true;break;}
            }catch(NoSuchMethodException ignored){}
            if(!pressed)throw new IllegalStateException("Native Open to LAN button not found");
            event("native_pause_open_to_lan_pressed",Map.of("physicalInputClaimed",false,"shareStartButtonPressed",false));
        } else if(input.equals("identity")){identityEnabled=true;event("identity_probe_started",Map.of("fixtureOnly",true));
        } else if(input.equals("maps")){mapsEnabled=true;event("maps_probe_started",Map.of("fixtureOnly",true));
        } else if(input.equals("gameplay")){gameplayEnabled=true;event("gameplay_started",Map.of("fixtureOnly",true));
        } else if (input.equals("publish")) {
            if (server == null) throw new IllegalStateException("No integrated server");
            Class<?> mode = Class.forName("net.minecraft.class_1934", false, client.getClass().getClassLoader());
            Object survival = enumValue(mode, "SURVIVAL");
            Object result = method(server.getClass(), "method_3763", mode, boolean.class,int.class).invoke(server,survival,true,25579);
            event("lan_published", Map.of("result",result,"port",method(server.getClass(),"method_3756").invoke(server),"actualSteamGuestConnectionVerified",false));
        } else if (input.equals("preset")) {
            Object screen = field(client.getClass(), "field_1755").get(client);
            presetState(method(screen.getClass(), "method_48657").invoke(screen),"action");
        } else if (input.equals("respawn")) {
            method(player.getClass(), "method_7331").invoke(player);
            event("respawn_requested", Map.of("native",true));
        } else if (input.startsWith("command ")) {
            if (server == null) throw new IllegalStateException("No integrated server");
            String cmd = input.substring(8);
            ((Executor)server).execute(()->{
                try {
                    Object manager=method(server.getClass(),"method_3734").invoke(server);
                    Object source=method(server.getClass(),"method_3739").invoke(server);
                    Method execute=Arrays.stream(manager.getClass().getDeclaredMethods()).filter(m->m.getName().equals("method_44252")&&m.getParameterCount()==2).findFirst().orElseThrow();
                    execute.setAccessible(true);execute.invoke(manager,source,cmd);
                    event("server_command_returned",Map.of("command",cmd));
                } catch(Exception failure){try{event("action_failure",Map.of("exception",failure.toString()));}catch(Exception ignored){}}
            });
        } else throw new IllegalArgumentException("Unknown QA action");
    }
    private static Object enumValue(Class<?> type,String name) {
        for(Object v:type.getEnumConstants())if(((Enum<?>)v).name().equals(name))return v;
        throw new IllegalArgumentException("Missing enum "+name);
    }

    private Path child(String value) throws IOException {
        Path path = Path.of(value).toRealPath();
        if (!path.startsWith(root) || Files.isSymbolicLink(path)) throw new SecurityException("QA path escaped marked fixture");
        return path;
    }
    private static String required(String key) {
        String value = System.getProperty("lanminecraft.qa." + key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing QA property " + key);
        return value;
    }
    private static Method method(Class<?> type, String name, Class<?>... arguments) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            try { Method value = current.getDeclaredMethod(name, arguments); value.setAccessible(true); return value; }
            catch (NoSuchMethodException ignored) { }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for(Class<?> c=type;c!=null;c=c.getSuperclass()) try { Field value = c.getDeclaredField(name); value.setAccessible(true); return value; }catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(type.getName()+"."+name);
    }
    private static String className(Object value) { return value == null ? null : value.getClass().getName(); }
    private synchronized void event(String kind, Map<String, ?> values) throws IOException {
        var row = new LinkedHashMap<String, Object>(); row.put("kind", kind); row.put("utc", Instant.now().toString()); row.putAll(values);
        Files.writeString(evidence.resolve("observations.jsonl"), json(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("[AOT QA] " + kind);
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var out = new StringJoiner(",", "{", "}");
            for (var entry : map.entrySet()) out.add(json(entry.getKey().toString()) + ":" + json(entry.getValue()));
            return out.toString();
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) switch (c) {
            case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
            default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
        }
        return out.append('"').toString();
    }
}
