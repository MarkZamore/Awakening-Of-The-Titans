import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;

/** Exercise the unmodified author's ParadisPortalBlock collision twice, with its native cooldown. */
public final class AotNativePortalProbe {
    private static boolean scheduled,terminal;private static int phase,waitTicks;private static String status="waiting";
    private static Path report;private static ClassLoader loader;private static UUID playerId;
    public static synchronized String tick(Object client,Object server,Object clientPlayer,String reportPath){
        if(terminal||scheduled||server==null||clientPlayer==null)return status;
        try{
            Path root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath();report=Path.of(reportPath).toAbsolutePath().normalize();
            if(!Files.isRegularFile(root.resolve(".aot-runtime-qa.json"))||!report.startsWith(root))throw new SecurityException("Marked independent QA fixture required");
            loader=server.getClass().getClassLoader();playerId=(UUID)call(clientPlayer,"method_5667");scheduled=true;
            ((Executor)server).execute(()->{try{step(server);}catch(Throwable e){fail(e);}finally{scheduled=false;}});
        }catch(Throwable e){fail(e);}return status;
    }
    private static void step(Object server)throws Exception{
        List<?> online=(List<?>)call(call(server,"method_3760"),"method_14571");
        if(online.size()!=1)throw new SecurityException("Exactly one disposable fixture player required");
        Object player=call(call(server,"method_3760"),"method_14602",playerId);if(player==null)throw new AssertionError("No fixture player");
        Object world=call(player,"method_37908");String dim=dimension(world);
        if(phase==0){
            require(dim.equals("dannys-aot:paradis"),"Portal test must begin in Paradis");
            collision(player,world);require(dimension(call(player,"method_37908")).equals("minecraft:overworld"),"Author portal did not transition Paradis to Overworld");
            event("native_portal_outbound",Map.of("source",dim,"actualDestination",dimension(call(player,"method_37908")),"teleportCommands",false,"nativeCollisionCallback",true));
            phase=1;status="waiting_for_native_portal_cooldown";return;
        }
        if(phase==1){
            require(dim.equals("minecraft:overworld"),"Unexpected dimension during native portal cooldown");
            if((Boolean)call(player,"method_30230")){if(++waitTicks>100)throw new AssertionError("Native portal cooldown did not expire within bounded polls");return;}
            collision(player,world);String destination=dimension(call(player,"method_37908"));
            require(destination.equals("dannys-aot:paradis"),"Author portal did not return Overworld to Paradis");
            event("native_portal_return",Map.of("source",dim,"actualDestination",destination,"teleportCommands",false,"nativeCollisionCallback",true,"cooldownWasAllowedToExpire",true));
            event("native_portal_final",Map.of("status","PASS","bothDirectionsObserved",true,"authorClass","daot.ParadisPortalBlock","productionFilesChanged",false));
            terminal=true;status="PASS native author portal round-trip";
        }
    }
    private static void collision(Object player,Object world)throws Exception{
        Object pos=call(player,"method_24515");if(call(world,"method_8321",pos)!=null)throw new AssertionError("Portal fixture would replace existing BlockEntity");
        Object old=call(world,"method_8320",pos);
        Object blocks=type("net.minecraft.class_7923").getField("field_41175").get(null);
        Object id=call(type("net.minecraft.class_2960"),"method_60654","dannys-aot:paradis_portal");
        Object block=call(blocks,"method_10223",id);
        require(block.getClass().getName().equals("daot.ParadisPortalBlock"),"Native registered author portal block is missing");
        Object state=call(block,"method_9564");
        try{
            call(world,"method_8652",pos,state,2);
            require(call(world,"method_8321",pos)!=null,"Author portal BlockEntity did not initialize");
            call(block,"method_9548",state,world,pos,player);
        }finally{call(world,"method_8652",pos,old,2);}
    }
    private static String dimension(Object world)throws Exception{return String.valueOf(call(call(world,"method_27983"),"method_29177"));}
    private static Class<?> type(String name)throws Exception{return Class.forName(name,false,loader);}
    private static void event(String kind,Map<String,?> values)throws Exception{Map<String,Object> row=new LinkedHashMap<>();row.put("kind",kind);row.put("utc",java.time.Instant.now().toString());row.putAll(values);Files.writeString(report,json(row)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static void fail(Throwable e){while(e.getCause()!=null)e=e.getCause();terminal=true;status="FAIL "+e;try{event("native_portal_failure",Map.of("status",status));}catch(Exception ignored){}}
    private static void require(boolean c,String m){if(!c)throw new AssertionError(m);}
    private static Object call(Object o,String n,Object...args)throws Exception{Class<?> owner=o instanceof Class<?> c?c:o.getClass();for(Class<?> c=owner;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(n)&&match(m.getParameterTypes(),args)){m.setAccessible(true);return m.invoke(o instanceof Class<?>?null:o,args);}for(Method m:owner.getMethods())if(m.getName().equals(n)&&match(m.getParameterTypes(),args)){m.trySetAccessible();return m.invoke(o instanceof Class<?>?null:o,args);}throw new NoSuchMethodException(owner+"."+n);}
    private static boolean match(Class<?>[]ts,Object[]args){if(ts.length!=args.length)return false;for(int i=0;i<ts.length;i++){Class<?> t=ts[i];if(t.isPrimitive())t=t==int.class?Integer.class:t==boolean.class?Boolean.class:t;if(args[i]!=null&&!t.isInstance(args[i]))return false;}return true;}
    private static String json(Object v){if(v instanceof Boolean||v instanceof Number)return v.toString();if(v instanceof Map<?,?>m){StringJoiner j=new StringJoiner(",","{","}");m.forEach((k,x)->j.add(json(k.toString())+":"+json(x)));return j.toString();}return "\""+String.valueOf(v).replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
}
