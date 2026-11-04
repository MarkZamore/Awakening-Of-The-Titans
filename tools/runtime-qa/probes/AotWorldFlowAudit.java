import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Test-only native creation/reload/recreate audit. Never installed as a mod. */
public final class AotWorldFlowAudit {
    private static final String PRESET="attack_on_titan:paradis";
    private static final Map<String,Object> report=new LinkedHashMap<>();
    private static Object client, screen, state, oldContext, selectScreen;
    private static ClassLoader gameLoader;
    private static Path output, saves, recreatedDat;
    private static byte[] recreatedHash;
    private static Set<String> originalSaves;
    private static boolean callback;
    private static int phase,ticks;
    private static String target;

    public static synchronized String verify(Object uiState,String resources,String authorJar,String reportJson) throws Exception {
        if(System.getProperty("lanminecraft.qa.worldFlowProbe.started")!=null) return "native flow audit already scheduled";
        gameLoader=uiState.getClass().getClassLoader();
        client=call(type("net.minecraft.class_310"),"method_1551");
        if(call(client,"method_1576")!=null)throw new SecurityException("World-flow audit requires a stopped integrated server");
        output=Path.of(reportJson).toAbsolutePath().getParent();
        if(!Files.isRegularFile(output.resolve("hold-create.flag"))) throw new SecurityException("hold-create.flag is mandatory");
        Path root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath();
        Path game=root.resolve(System.getProperty("lanminecraft.qa.game")).normalize();
        if(!game.startsWith(root)||!Files.isRegularFile(root.resolve(".aot-runtime-qa.json")))throw new SecurityException("Marked disposable QA instance required");
        target=System.getProperty("lanminecraft.qa.recreateWorld","");
        if(target.isEmpty()&&Files.isRegularFile(output.resolve("recreate-world.txt")))
            target=Files.readString(output.resolve("recreate-world.txt"),StandardCharsets.UTF_8).strip();
        if(!target.matches("AotQa[A-Za-z0-9_-]+"))throw new SecurityException("Explicit AotQa fixture save required");
        saves=game.resolve("saves"); recreatedDat=saves.resolve(target).resolve("level.dat");
        if(!Files.isRegularFile(recreatedDat))throw new SecurityException("Closed recreate fixture level.dat missing");
        screen=field(client.getClass(),"field_1755").get(client);state=uiState;
        if(!type("net.minecraft.class_525").isInstance(screen)||call(screen,"method_48657")!=state)
            throw new SecurityException("Current native fresh CreateWorldScreen required");
        // Preserve all previous codec/constructor/policy checks in the same native process.
        Class.forName("dev.lanminecraft.aotlock.test.NativeResourcesTest",true,AotWorldFlowAudit.class.getClassLoader())
            .getMethod("verify",Object.class,String.class,String.class,String.class).invoke(null,state,resources,authorJar,reportJson);
        originalSaves=saveNames();recreatedHash=sha(recreatedDat);
        System.setProperty("lanminecraft.qa.worldFlowProbe.started","true");
        report.put("started",java.time.Instant.now().toString());report.put("recreateSource",target);
        report.put("productionFilesChanged",false);
        try { missingPresetGate(); beginReload(); registerTicks(); }
        catch(Throwable error){fail(error);throw error;}
        return json(report);
    }

    private static void missingPresetGate()throws Exception {
        Object context=call(state,"method_48728");
        Object access=call(context,"method_45689");
        Object presetKey=type("net.minecraft.class_7924").getField("field_41250").get(null);
        Object registry=call(access,"method_30530",presetKey);
        Object lifecycle=call(type("com.mojang.serialization.Lifecycle"),"stable");
        Object replacement=type("net.minecraft.class_2370").getConstructor(type("net.minecraft.class_5321"),type("com.mojang.serialization.Lifecycle"))
            .newInstance(presetKey,lifecycle);
        int removed=0;
        for(Object raw:(Set<?>)call(registry,"method_29722")) {
            Map.Entry<?,?> e=(Map.Entry<?,?>)raw;
            if(PRESET.equals(String.valueOf(call(e.getKey(),"method_29177")))){removed++;continue;}
            call(type("net.minecraft.class_2378"),"method_39197",replacement,e.getKey(),e.getValue());
        }
        require(removed==1,"Mandatory preset must exist exactly once before negative test");
        call(replacement,"method_40276");
        Object layered=call(context,"comp_1029");
        List<?> keys=(List<?>)field(layered.getClass(),"field_40581").get(layered);
        List<?> layers=(List<?>)field(layered.getClass(),"field_40582").get(layered);
        List<Object> replaced=new ArrayList<>();int replacedLayers=0;
        for(Object layer:layers) {
            List<Object> entries=new ArrayList<>();boolean changed=false;
            try(Stream<?> stream=(Stream<?>)call(layer,"method_40311")) {
                for(Object e:stream.toList()) {
                    Object key=call(e,"comp_350"),value=call(e,"comp_351");
                    if(presetKey.equals(key)){value=replacement;changed=true;}
                    entries.add(value);
                }
            }
            if(changed){
                Object registryAccess=type("net.minecraft.class_5455$class_6891").getConstructor(List.class).newInstance(entries);
                replaced.add(call(registryAccess,"method_40316"));replacedLayers++;
            }else replaced.add(layer);
        }
        require(replacedLayers==1,"World-preset registry must be replaced in exactly one native registry layer");
        Constructor<?> layeredCtor=type("net.minecraft.class_7780").getDeclaredConstructor(List.class,List.class);layeredCtor.setAccessible(true);
        Object negativeLayers=layeredCtor.newInstance(keys,replaced);
        Object negativeContext=null;
        for(Constructor<?> c:context.getClass().getConstructors()) if(c.getParameterCount()==6)
            negativeContext=c.newInstance(call(context,"comp_616"),call(context,"comp_1027"),call(context,"comp_1028"),negativeLayers,call(context,"comp_619"),call(context,"comp_1030"));
        require(negativeContext!=null,"Canonical native creation context constructor missing");
        Path fixture=output.resolve("native-missing-preset-state");Files.createDirectories(fixture);
        Object negative=type("net.minecraft.class_8100").getConstructor(Path.class,type("net.minecraft.class_7193"),Optional.class,OptionalLong.class)
            .newInstance(fixture,negativeContext,Optional.empty(),OptionalLong.of(93284712L));
        for(String getter:List.of("method_48732","method_48733")) {
            List<?> entries=(List<?>)call(negative,getter);
            require(entries.size()==1&&call(entries.get(0),"comp_1238")==null,"Missing preset must produce only the unavailable placeholder");
        }
        require(call(call(negative,"method_48730"),"comp_1238")==null,"Missing preset selection must be unavailable");
        Field screenState=field(screen.getClass(),"field_42172");Object original=screenState.get(screen);
        try {
            screenState.set(screen,negative);
            call(screen,"method_2736"); // Actual native onCreate HEAD; must cancel before any save allocation.
            Object current=field(client.getClass(),"field_1755").get(client);
            require(type("net.minecraft.class_403").isInstance(current),"Real negative onCreate must display the helper AlertScreen");
            require(call(client,"method_1576")==null,"Missing-preset onCreate unexpectedly started a server");
            require(originalSaves.equals(saveNames()),"Missing-preset onCreate allocated a game save");
            report.put("nativeMissingPresetConstructorFailClosed",true);
            report.put("nativeMissingPresetOnCreateCancelledBeforeSave",true);
            report.put("nativeMissingPresetAlertDisplayed",true);
            report.put("missingMandatoryResourceStartupReloadTested",false);
        } finally {screenState.set(screen,original);call(client,"method_1507",screen);}
    }

    private static void beginReload()throws Exception {
        call(state,"method_48716","839472928314");call(state,"method_48717",false);call(state,"method_48720",true);
        oldContext=call(state,"method_48728");Object config=call(oldContext,"comp_1030");
        Object pair=call(screen,"method_30296",config);Object repository=call(pair,"getSecond");
        callback=false;phase=1;ticks=0;
        Consumer<Object> completed=value->{callback=true;};
        call(screen,"method_45679",repository,config,completed); // Native async WorldLoader, not a setSettings shortcut.
        report.put("nativeAsyncDatapackReloadStarted",true);save();
    }

    private static void registerTicks()throws Exception {
        Class<?> events=type("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents");
        Field eventField=events.getField("END_CLIENT_TICK");Object event=eventField.get(null);
        Class<?> callbackType=(Class<?>)((ParameterizedType)eventField.getGenericType()).getActualTypeArguments()[0];
        Object listener=Proxy.newProxyInstance(gameLoader,new Class<?>[]{callbackType},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return method.getName().equals("toString")?"AotWorldFlowAudit":method.getName().equals("hashCode")?System.identityHashCode(proxy):proxy==args[0];
            tick();return null;
        });
        call(event,"register",listener);
    }

    private static void tick() {
        if(phase<1||phase>=90)return;
        try {
            ticks++;if(ticks>3600)throw new AssertionError("Native flow stage timed out: "+phase);
            Object current=field(client.getClass(),"field_1755").get(client);
            if(phase==1&&current==screen&&call(state,"method_48728")!=oldContext) {
                require(call(state,"method_48728")!=oldContext,"Async native reload did not replace WorldCreationContext");
                restricted(state);options(state,839472928314L,false,true);
                report.put("nativeAsyncDatapackReloadCompletionReturnedToCreationScreen",true);
                report.put("nativeAsyncDatapackReloadFailureChoiceCallbackInvoked",callback);
                report.put("nativeAsyncDatapackReloadReplacedContext",true);
                report.put("nativeAsyncDatapackReloadRestrictsNormalAndShift",true);
                report.put("nativeAsyncDatapackReloadPreservesSeedStructureBonus",true);
                selectScreen=type("net.minecraft.class_526").getConstructor(type("net.minecraft.class_437")).newInstance(screen);
                call(client,"method_1507",selectScreen);phase=2;ticks=0;save();return;
            }
            if(phase==2&&current==selectScreen) {
                Object list=field(selectScreen.getClass(),"field_3218").get(selectScreen);
                if(list==null)return;
                for(Object entry:(List<?>)call(list,"method_25396")) {
                    if(!type("net.minecraft.class_528$class_4272").isInstance(entry))continue;
                    Object summary=field(entry.getClass(),"field_19138").get(entry);
                    if(!target.equals(String.valueOf(call(summary,"method_248"))))continue;
                    report.put("nativeRecreateInvokedWorldListEntry",true);
                    call(entry,"method_20173");phase=3;ticks=0;save();return;
                }
            }
            if(phase==3&&type("net.minecraft.class_410").isInstance(current)) {
                // Only the known native customized-world confirmation for this explicit QA target.
                report.put("nativeRecreateCustomizedConfirmationObserved",true);
                for(Field f:current.getClass().getDeclaredFields()) if(f.getType().getName().equals("it.unimi.dsi.fastutil.booleans.BooleanConsumer")){
                    f.setAccessible(true);Object consumer=f.get(current);call(consumer,"accept",true);return;
                }
                throw new AssertionError("Unknown native recreate confirmation callback");
            }
            if(phase==3&&type("net.minecraft.class_525").isInstance(current)) {
                require((Boolean)field(current.getClass(),"field_42502").get(current),"Recreate did not enter actual recreated CreateWorldScreen");
                Object recreated=call(current,"method_48657");restricted(recreated);
                report.put("nativeRecreateScreenFlag",true);
                report.put("nativeRecreateRestrictsSelectionNormalAndShift",true);
                report.put("recreatedSeed",call(recreated,"method_48725"));
                report.put("recreatedStructures",call(recreated,"method_48726"));
                report.put("recreatedBonusChest",call(recreated,"method_48727"));
                require(Arrays.equals(recreatedHash,sha(recreatedDat)),"Native recreate changed source level.dat");
                require(originalSaves.equals(saveNames()),"Policy audit unexpectedly created a new save");
                require(call(client,"method_1576")==null,"Native recreate unexpectedly started a server");
                report.put("nativeRecreateSourceLevelDatUnchanged",true);
                report.put("nativeRecreateAllocatedNewSave",false);
                report.put("status","PASS");report.put("finished",java.time.Instant.now().toString());phase=99;save();
            }
        } catch(Throwable failure){fail(failure);}
    }

    private static void options(Object s,long seed,boolean structures,boolean bonus)throws Exception {
        Object options=call(call(s,"method_48728"),"comp_616");
        require(Long.toString(seed).equals(call(s,"method_48725"))&&(Boolean)call(s,"method_48726")==structures&&(Boolean)call(s,"method_48727")==bonus,"Creation UI options changed");
        require((Long)call(options,"method_28028")==seed&&(Boolean)call(options,"method_28029")==structures&&(Boolean)call(options,"method_28030")==bonus,"Native WorldOptions changed");
    }
    private static void restricted(Object s)throws Exception {
        for(String getter:List.of("method_48732","method_48733")){List<?> list=(List<?>)call(s,getter);require(list.size()==1&&PRESET.equals(id(list.get(0))),"Native list not restricted: "+getter);}
        require(PRESET.equals(id(call(s,"method_48730"))),"Selected preset not restricted");
    }
    private static String id(Object e)throws Exception {Object h=call(e,"comp_1238");if(h==null)return "<unavailable>";Optional<?> k=(Optional<?>)call(h,"method_40230");return k.isEmpty()?"<unbound>":String.valueOf(call(k.get(),"method_29177"));}
    private static Set<String> saveNames()throws Exception {if(!Files.exists(saves))return Set.of();try(Stream<Path> s=Files.list(saves)){return new TreeSet<>(s.map(p->p.getFileName().toString()).toList());}}
    private static byte[] sha(Path p)throws Exception{return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));}
    private static void fail(Throwable error){phase=90;while(error instanceof InvocationTargetException&&error.getCause()!=null)error=error.getCause();report.put("status","FAIL");report.put("error",error.toString());try{save();}catch(Exception ignored){}error.printStackTrace();}
    private static void save()throws Exception{Files.writeString(output.resolve("native-world-flow-report.json"),json(report)+"\n",StandardCharsets.UTF_8);}
    private static String json(Object v){if(v==null)return "null";if(v instanceof Boolean||v instanceof Number)return v.toString();if(v instanceof Map<?,?> m){List<String>a=new ArrayList<>();m.forEach((k,x)->a.add(json(k.toString())+":"+json(x)));return "{"+String.join(",",a)+"}";}return "\""+v.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    private static void require(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static Class<?> type(String n)throws ClassNotFoundException{return Class.forName(n,false,gameLoader);}
    private static Field field(Class<?> c,String n)throws Exception{for(;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}
    private static Object call(Object o,String n,Object...args)throws Exception{
        Class<?> c=o instanceof Class<?> cl?cl:o.getClass();
        for(Class<?> p=c;p!=null;p=p.getSuperclass())for(Method m:p.getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length){
            boolean valid=true;Class<?>[] ts=m.getParameterTypes();for(int i=0;i<ts.length;i++)if(args[i]!=null&&!box(ts[i]).isInstance(args[i]))valid=false;
            if(valid){m.setAccessible(true);return m.invoke(o instanceof Class<?>?null:o,args);}
        }
        for(Method m:c.getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length){m.trySetAccessible();return m.invoke(o instanceof Class<?>?null:o,args);}
        throw new NoSuchMethodException(c.getName()+"."+n);
    }
    private static Class<?> box(Class<?> c){if(c==boolean.class)return Boolean.class;if(c==long.class)return Long.class;if(c==int.class)return Integer.class;return c;}
}
