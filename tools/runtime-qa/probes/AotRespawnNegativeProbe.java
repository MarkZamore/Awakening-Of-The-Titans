import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;

/** Test-only personal-bed/anchor/disabled-global checks through actual respawn target code. */
public final class AotRespawnNegativeProbe {
    private static boolean started,done;private static String status="waiting";
    private static ClassLoader loader;private static Path output,root;
    public static synchronized String tick(Object client,Object server,Object clientPlayer,String reportPath){
        if(done||started)return status;if(server==null||clientPlayer==null)return status;
        try{
            root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath();
            output=Path.of(reportPath).toAbsolutePath().normalize();
            if(!Files.isRegularFile(root.resolve(".aot-runtime-qa.json"))||!output.startsWith(root))throw new SecurityException("Marked QA fixture required");
            loader=server.getClass().getClassLoader();UUID id=(UUID)call(clientPlayer,"method_5667");started=true;
            ((Executor)server).execute(()->{try{run(server,id);}catch(Throwable e){while(e.getCause()!=null)e=e.getCause();status="FAIL "+e;done=true;try{Files.writeString(output,"{\"status\":"+json(status)+"}\n");}catch(Exception ignored){}}});
        }catch(Exception e){status="FAIL "+e;done=true;}return status;
    }
    private static void run(Object server,UUID id)throws Exception{
        Object player=call(call(server,"method_3760"),"method_14602",id);if(player==null)throw new AssertionError("No matching fixture player");
        Map<Field,Object> spawn=new LinkedHashMap<>();for(String name:List.of("field_23191","field_23192","field_23193","field_26353")){Field f=field(player.getClass(),name);spawn.put(f,f.get(player));}
        Object manager=type("de.dafuqs.globalspawn.GlobalSpawnManager");Object global=call(manager,"getGlobalRespawnPoint");
        Object dispatcher=call(call(server,"method_3734"),"method_9235"),source=call(server,"method_3739");
        Object noop=type("net.minecraft.class_5454").getField("field_52245").get(null);
        Path game=root.resolve(System.getProperty("lanminecraft.qa.game")).normalize();
        Path config=game.resolve("config/GlobalSpawn.json5");if(!game.startsWith(root)||Files.isSymbolicLink(config))throw new SecurityException("Independent fixture config required");
        byte[] configBytes=Files.readAllBytes(config);List<Object[]> changed=new ArrayList<>();Map<String,Object> report=new LinkedHashMap<>();
        try{
            for(String dim:List.of("minecraft:overworld","minecraft:the_nether")){
                Object world=world(server,dim);boolean bed=dim.equals("minecraft:overworld");
                // A tiny disposable QA platform; no production terrain/config is an input.
                for(int x=30;x<=34;x++)for(int z=30;z<=34;z++)for(int y=119;y<=122;y++)snapshot(changed,world,x,y,z);
                command(dispatcher,source,"execute in "+dim+" run fill 30 119 30 34 119 34 minecraft:stone");
                boolean needsClearing=false;
                for(int x=30;x<=34;x++)for(int z=30;z<=34;z++)for(int y=120;y<=122;y++)
                    if(!(Boolean)call(call(world,"method_8320",pos(x,y,z)),"method_26215"))needsClearing=true;
                if(needsClearing)command(dispatcher,source,"execute in "+dim+" run fill 30 120 30 34 122 34 minecraft:air");
                if(bed){
                    command(dispatcher,source,"execute in "+dim+" run setblock 32 120 32 minecraft:red_bed[facing=north,part=foot]");
                    command(dispatcher,source,"execute in "+dim+" run setblock 32 120 31 minecraft:red_bed[facing=north,part=head]");
                }else command(dispatcher,source,"execute in "+dim+" run setblock 32 120 32 minecraft:respawn_anchor[charges=4]");
                Object pos=pos(32,120,bed?31:32),key=call(world,"method_27983");
                call(player,"method_26284",key,pos,0f,false,false);
                require(!(Boolean)call(player,"globalspawn$shouldOverrideRespawn"),"Native author guard must preserve valid "+(bed?"bed":"anchor"));
                Optional<?> valid=(Optional<?>)call(type("net.minecraft.class_3222"),"method_60588",world,pos,0f,false,true);
                require(valid.isPresent(),"Native personal spawn validation failed");
                Object expected=call(valid.get(),"comp_2811"),target=call(player,"method_60590",true,noop);
                require(call(target,"comp_2820")==world&&expected.equals(call(target,"comp_2821")),"Helper changed native personal "+(bed?"bed":"anchor")+" target");
                report.put(bed?"nativePersonalBedPreserved":"nativePersonalChargedAnchorPreserved",true);
                if(!bed){String block=String.valueOf(call(world,"method_8320",pos));require(block.contains("charges=4"),"Validation consumed anchor charge");report.put("nativeAnchorValidationDidNotConsumeCharge",true);}
            }
            Object overworld=world(server,"minecraft:overworld");
            call(player,"method_26284",call(overworld,"method_27983"),null,0f,false,false);
            call(manager,"unsetGlobalSpawnPoint"); // Native author configuration API, fixture only; restored below.
            require(!(Boolean)call(manager,"isGlobalSpawnPointActive",server),"GlobalSpawn author disabled state not reached");
            require(!(Boolean)call(player,"globalspawn$shouldOverrideRespawn"),"Native guard must return false with global disabled");
            Object target=call(player,"method_60590",true,noop);
            require(call(target,"comp_2820")==overworld,"Disabled global spawn was overridden");
            report.put("nativeDisabledGlobalFallbackPreserved",true);
            report.put("nativeAuthorConfigApiExercisedOnDisposableFixtureOnly",true);
        }finally{
            call(manager,"setGlobalSpawnPoint",global);
            Files.write(config,configBytes);
            for(var e:spawn.entrySet())e.getKey().set(player,e.getValue());
            Collections.reverse(changed);for(Object[] b:changed)call(b[0],"method_8652",b[1],b[2],2);
        }
        require(Arrays.equals(configBytes,Files.readAllBytes(config)),"Fixture author config bytes not restored");
        report.put("fixtureAuthorConfigBytesRestored",true);report.put("productionFilesChanged",false);report.put("status","PASS");
        Files.writeString(output,json(report)+"\n",StandardCharsets.UTF_8);status="PASS native personal bed/anchor/disabled-global";done=true;
    }
    private static void snapshot(List<Object[]> rows,Object world,int x,int y,int z)throws Exception{Object p=pos(x,y,z);if(call(world,"method_8321",p)!=null)throw new AssertionError("QA platform would touch an existing BlockEntity");rows.add(new Object[]{world,p,call(world,"method_8320",p)});}
    private static void command(Object dispatcher,Object source,String c)throws Exception{Number result=(Number)call(dispatcher,"execute",c,source);require(result.intValue()>0,"Native command failed: "+c);}
    private static Object world(Object server,String dim)throws Exception{Object registry=type("net.minecraft.class_7924").getField("field_41223").get(null);Object identifier=call(type("net.minecraft.class_2960"),"method_60654",dim);Object key=call(type("net.minecraft.class_5321"),"method_29179",registry,identifier);Object world=call(server,"method_3847",key);if(world==null)throw new AssertionError("Missing native dimension "+dim);return world;}
    private static Object pos(int x,int y,int z)throws Exception{return type("net.minecraft.class_2338").getConstructor(int.class,int.class,int.class).newInstance(x,y,z);}
    private static Class<?> type(String n)throws Exception{return Class.forName(n,false,loader);}
    private static void require(boolean c,String m){if(!c)throw new AssertionError(m);}
    private static Field field(Class<?> c,String n)throws Exception{for(;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}
    private static Object call(Object o,String n,Object...args)throws Exception{Class<?> owner=o instanceof Class<?> c?c:o.getClass();for(Class<?> c=owner;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(n)&&match(m.getParameterTypes(),args)){m.setAccessible(true);return m.invoke(o instanceof Class<?>?null:o,args);}for(Method m:owner.getMethods())if(m.getName().equals(n)&&match(m.getParameterTypes(),args)){m.trySetAccessible();return m.invoke(o instanceof Class<?>?null:o,args);}throw new NoSuchMethodException(owner+"."+n);}
    private static boolean match(Class<?>[] ts,Object[]args){if(ts.length!=args.length)return false;for(int i=0;i<ts.length;i++){Class<?> t=ts[i];if(t.isPrimitive())t=t==float.class?Float.class:t==boolean.class?Boolean.class:t==int.class?Integer.class:t;if(args[i]!=null&&!t.isInstance(args[i]))return false;}return true;}
    private static String json(Object v){if(v instanceof Boolean||v instanceof Number)return v.toString();if(v instanceof Map<?,?>m){StringJoiner j=new StringJoiner(",","{","}");m.forEach((k,x)->j.add(json(k.toString())+":"+json(x)));return j.toString();}return "\""+String.valueOf(v).replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
}
