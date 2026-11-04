import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Explicit fixture-only native publish/stop requests, after an actual local player exists. */
public final class AotNativeQaControlProbe {
    private static String previous="";
    public static synchronized String tick(Object client,Object server,Object player,String reportPath)throws Exception{
        Path root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath();
        Path report=Path.of(reportPath).toAbsolutePath().normalize();
        if(!Files.isRegularFile(root.resolve(".aot-runtime-qa.json"))||!report.startsWith(root))throw new SecurityException("Marked disposable fixture required");
        Path input=report.getParent().resolve("native-control.txt");if(!Files.isRegularFile(input))return "waiting";
        String action=Files.readString(input).strip();if(action.equals(previous))return "already_processed";
        if(server==null||player==null)return "waiting_for_actual_player";
        if(((List<?>)call(call(server,"method_3760"),"method_14571")).size()!=1)throw new SecurityException("One QA fixture player required");
        if(action.equals("publish")){
            if((Boolean)call(server,"method_3860")){previous=action;return "already_published";}
            Class<?> mode=Class.forName("net.minecraft.class_1934",false,client.getClass().getClassLoader());
            Object result=call(server,"method_3763",mode.getField("field_9215").get(null),true,25579);
            if(!Boolean.TRUE.equals(result))throw new AssertionError("Native publish refused");
            Files.writeString(report,"{\"nativePublished\":true,\"physicalInput\":false}\n");previous=action;return "published";
        }
        if(action.equals("stop")){
            previous=action;Files.writeString(report,"{\"nativeShutdownRequested\":true,\"physicalInput\":false}\n");call(client,"method_1592");return "native_shutdown_requested";
        }
        throw new IllegalArgumentException("Unknown native fixture control");
    }
    private static Object call(Object o,String n,Object...args)throws Exception{for(Method m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length){m.trySetAccessible();return m.invoke(o,args);}for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(o,args);}throw new NoSuchMethodException(n);}
}
