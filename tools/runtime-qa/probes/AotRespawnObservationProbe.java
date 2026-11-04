import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;

/** Observe actual death/respawn for200 native END_SERVER_TICK callbacks, without teleporting. */
public final class AotRespawnObservationProbe {
    private static volatile boolean started,terminal,requestRespawn;
    private static volatile String status="not_started";
    private static ClassLoader loader;
    private static Object expectedServer,listener;
    private static UUID playerId;
    private static Path report;
    private static int ticksAfterAlive,ticksWaiting;
    private static String lastDimension="";
    private AotRespawnObservationProbe(){}

    public static synchronized String tick(Object client,Object server,Object clientPlayer,String reportPath){
        if(terminal)return status;
        try{
            if(server==null||clientPlayer==null)return "waiting_for_integrated_server";
            if(requestRespawn){requestRespawn=false;call(clientPlayer,"method_7331");event("native_client_respawn_requested",Map.of("teleportIssued",false));}
            if(!started){
                guard(reportPath);loader=server.getClass().getClassLoader();expectedServer=server;
                playerId=(UUID)call(clientPlayer,"method_5667");started=true;
                ((Executor)server).execute(()->{try{start(server);}catch(Throwable e){fail(e);}});
            }else if(ticksAfterAlive>0){
                Object level=call(clientPlayer,"method_37908");
                event("client_dimension_sample",Map.of("serverNativeTicksAfterAlive",ticksAfterAlive,"dimension",dimension(level),"alive",call(clientPlayer,"method_5805")));
            }
        }catch(Throwable e){fail(e);}return status;
    }

    private static void start(Object server)throws Exception{
        Object manager=call(server,"method_3760"),player=call(manager,"method_14602",playerId);
        if(player==null)throw new SecurityException("ClientUUID is not an active fixture serverplayer");
        String name=String.valueOf(call(call(player,"method_7334"),"getName"));
        if(!name.matches("[A-Za-z0-9_]{1,16}"))throw new SecurityException("Unsafe fixture profile name");
        Class<?> events=Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents",true,loader);
        Field end=events.getField("END_SERVER_TICK");Object event=end.get(null);
        Class<?> callback=(Class<?>)((ParameterizedType)end.getGenericType()).getActualTypeArguments()[0];
        listener=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return switch(method.getName()){case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->"AotRespawnObservationProbe";};
            if(!terminal&&args!=null&&args.length==1&&args[0]==expectedServer)try{observe(args[0]);}catch(Throwable e){fail(e);}return null;
        });
        call(event,"register",listener);
        sample(player,"before_death");
        inspectNativeSpawnPoint(server);
        Object dispatcher=call(call(server,"method_3734"),"method_9235");
        Object result=call(dispatcher,"execute","kill "+name,call(server,"method_3739"));
        event("native_death_command",Map.of("result",String.valueOf(result),"teleportIssued",false,"configOverrides",false,"inventoryCleared",false));
        requestRespawn=true;status="waiting_for_native_respawn";
    }

    private static void inspectNativeSpawnPoint(Object server)throws Exception{
        Class<?> manager=Class.forName("de.dafuqs.globalspawn.GlobalSpawnManager",true,loader);
        Object point=manager.getMethod("getGlobalRespawnPoint").invoke(null);
        Object target=call(server,"method_3847",call(point,"getDimension"));
        if(target==null)throw new AssertionError("Configured native respawn dimension is missing");
        Object raw=call(point,"getPos"),safe=call(point,"getFinalSpawnPos",server);
        Class<?> playerType=Class.forName("net.minecraft.class_3222",false,loader);
        Method validate=playerType.getDeclaredMethod("method_60588",Class.forName("net.minecraft.class_3218",false,loader),Class.forName("net.minecraft.class_2338",false,loader),float.class,boolean.class,boolean.class);
        validate.setAccessible(true);
        float angle=((Number)call(point,"getAngle")).floatValue();
        Optional<?> rawResult=(Optional<?>)validate.invoke(null,target,raw,angle,true,true);
        Optional<?> safeResult=(Optional<?>)validate.invoke(null,target,safe,angle,true,true);
        Object up=call(raw,"method_10084");
        event("native_globalspawn_validation",Map.of("dimension",dimension(target),"rawPosition",String.valueOf(raw),"safeCriterionPosition",String.valueOf(safe),"rawBlock",String.valueOf(call(target,"method_8320",raw)),"rawAboveBlock",String.valueOf(call(target,"method_8320",up)),"rawForcedRespawnAccepted",rawResult.isPresent(),"nativeSafeForcedRespawnAccepted",safeResult.isPresent(),"safeAlgorithm","GlobalSpawnPoint.getFinalSpawnPos","configOverrides",false));
    }

    private static void observe(Object server)throws Exception{
        Object player=call(call(server,"method_3760"),"method_14602",playerId);
        if(player==null||!(Boolean)call(player,"method_5805")){
            if(++ticksWaiting>600)throw new AssertionError("No living fixture player after600 native server ticks");return;
        }
        ticksAfterAlive++;
        String now=dimension(call(player,"method_37908"));
        if(!now.equals(lastDimension)||Set.of(1,5,20,40,100,200).contains(ticksAfterAlive))sample(player,"post_respawn");
        lastDimension=now;
        if(ticksAfterAlive>=200){
            boolean pass=now.equals("dannys-aot:paradis");
            event("native_respawn_final",Map.of("pass",pass,"serverDimension",now,"nativeTicksAfterAlive",ticksAfterAlive,"teleportIssued",false,"configOverrides",false));
            terminal=true;status=pass?"PASS native_respawn_paradis_after200_real_ticks":"FAIL native_respawn_final_dimension="+now;
        }
    }

    private static void sample(Object player,String phase)throws Exception{
        Object level=call(player,"method_37908");Map<String,Object> row=new LinkedHashMap<>();
        row.put("phase",phase);row.put("nativeTicksAfterAlive",ticksAfterAlive);row.put("dimension",dimension(level));row.put("gameTime",call(level,"method_8510"));
        row.put("x",call(player,"method_23317"));row.put("y",call(player,"method_23318"));row.put("z",call(player,"method_23321"));row.put("alive",call(player,"method_5805"));
        event("server_dimension_sample",row);
    }
    private static String dimension(Object level)throws Exception{return String.valueOf(call(call(level,"method_27983"),"method_29177"));}
    private static void guard(String value)throws Exception{
        String raw=System.getProperty("lanminecraft.qa.root");if(raw==null)throw new SecurityException("No QA root");Path root=Path.of(raw).toRealPath();
        if(!Files.isRegularFile(root.resolve(".aot-runtime-qa.json")))throw new SecurityException("Unmarked fixture");
        boolean artifact=false;for(Path part:root)if(part.toString().equalsIgnoreCase("artifacts"))artifact=true;if(!artifact)throw new SecurityException("QA root outside artifacts");
        report=Path.of(value).toAbsolutePath().normalize();if(!report.startsWith(root))throw new SecurityException("Report outside fixture");Files.createDirectories(report.getParent());
    }
    private static Object call(Object obj,String name,Object...args)throws Exception{
        for(Class<?> c=obj.getClass();c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&match(m.getParameterTypes(),args)){m.setAccessible(true);return m.invoke(obj,args);}
        for(Method m:obj.getClass().getMethods())if(m.getName().equals(name)&&match(m.getParameterTypes(),args)){m.setAccessible(true);return m.invoke(obj,args);}throw new NoSuchMethodException(obj.getClass()+"."+name);
    }
    private static boolean match(Class<?>[] types,Object[] args){if(types.length!=args.length)return false;for(int i=0;i<args.length;i++){Class<?> t=types[i];if(t.isPrimitive())t=t==int.class?Integer.class:t==long.class?Long.class:t==boolean.class?Boolean.class:t;if(args[i]!=null&&!t.isInstance(args[i]))return false;}return true;}
    private static synchronized void event(String kind,Map<String,?> data)throws Exception{Map<String,Object> row=new LinkedHashMap<>();row.put("kind",kind);row.put("utc",Instant.now().toString());row.putAll(data);Files.writeString(report,json(row)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static void fail(Throwable e){Throwable cause=e instanceof InvocationTargetException inv?inv.getTargetException():e;status="FAIL "+cause;terminal=true;try{event("respawn_probe_failure",Map.of("pass",false,"exception",cause.toString()));}catch(Exception ignored){}}
    private static String json(Object value){if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();if(value instanceof Map<?,?> map){StringJoiner s=new StringJoiner(",","{","}");for(var e:map.entrySet())s.add(json(e.getKey().toString())+":"+json(e.getValue()));return s.toString();}return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
}
