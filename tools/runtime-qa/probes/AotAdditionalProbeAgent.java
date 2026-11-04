import java.lang.instrument.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.concurrent.*;import java.net.*;
/** Additional immutable QA probe classloader; no transformations or physical input. */
public class AotAdditionalProbeAgent {
public static void agentmain(String args,Instrumentation inst)throws Exception{
 String[] a=args.split("\\|",3);if(a.length!=3||!a[2].matches("[A-Za-z0-9_-]{1,48}"))throw new IllegalArgumentException("JAR|CLASS|LABEL required");
 Path root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath(),ev=Path.of(System.getProperty("lanminecraft.qa.evidence")).toRealPath();
 if(!Files.isRegularFile(root.resolve(".aot-runtime-qa.json"))||!ev.startsWith(root))throw new SecurityException("Not a marked QA fixture");
 Path jar=Path.of(a[0]).toRealPath();if(!jar.toString().contains("artifacts"))throw new SecurityException("QA jar outside artifacts");
 Class<?> mc=null;for(Class<?> c:inst.getAllLoadedClasses())if(c.getName().equals("net.minecraft.class_310")){mc=c;break;}if(mc==null)throw new IllegalStateException("No native client");
 Object client=mc.getMethod("method_1551").invoke(null);Class<?> type=mc;var loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},mc.getClassLoader());
 Method probe=Class.forName(a[1],true,loader).getMethod("tick",Object.class,Object.class,Object.class,String.class);
 Files.writeString(ev.resolve(a[2]+"-bridge.txt"),"Test-only attached probe; no transformations/physical input; "+a[1]+"\n");
 var worker=new Thread(()->{try{long until=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);while(System.nanoTime()<until){
  var done=new CompletableFuture<Void>();((Executor)client).execute(()->{try{Object player=type.getField("field_1724").get(client);Object server=type.getMethod("method_1576").invoke(client);probe.invoke(null,client,server,player,ev.resolve(a[2]+"-report.json").toString());done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});
  done.get(120,TimeUnit.SECONDS);Thread.sleep(3000);
 }}catch(Throwable e){try{Files.writeString(ev.resolve(a[2]+"-bridge-failure.txt"),e.toString());}catch(Exception ignored){}}},"AOT-extra-"+a[2]);worker.setDaemon(true);worker.start();
}}
