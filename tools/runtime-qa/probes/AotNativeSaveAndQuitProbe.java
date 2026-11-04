import java.lang.reflect.*;import java.nio.file.*;import java.io.*;
/** Explicit native pause-menu Save & Quit in a marked disposable fixture. */
public final class AotNativeSaveAndQuitProbe {
 private static boolean done;
 public static void tick(Object client,Object server,Object player,String report)throws Exception{
  if(done||player==null)return;
  Path root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath(),ev=Path.of(System.getProperty("lanminecraft.qa.evidence")).toRealPath();
  if(!ev.startsWith(root)||!Files.isRegularFile(root.resolve(".aot-runtime-qa.json")))throw new SecurityException("Unmarked QA fixture");
  if(!Files.isRegularFile(ev.resolve("native-disconnect.flag")))return;
  Object current=client.getClass().getField("field_1755").get(client);
  if(current!=null&&current.getClass().getName().startsWith("com.cinemamod.mcef.")){
   Files.writeString(Path.of(report),"{\"status\":\"WAITING_FOR_AUTHOR_MCEF_INITIALIZATION\",\"guardBypassed\":false}");return;
  }
  String step="native.pause.init";
  try{
   var loader=client.getClass().getClassLoader();Class<?> screen=Class.forName("net.minecraft.class_437",false,loader),pauseType=Class.forName("net.minecraft.class_433",false,loader);
   Object pause=pauseType.getConstructor(boolean.class).newInstance(true);
   client.getClass().getMethod("method_1507",screen).invoke(client,pause);
   if(client.getClass().getField("field_1755").get(client)!=pause){Files.writeString(Path.of(report),"{\"status\":\"NATIVE_PAUSE_INTERCEPTED\",\"guardBypassed\":false}");return;}
   step="native.pause.saveQuit";Method quit=pauseType.getDeclaredMethod("method_47632");quit.setAccessible(true);done=true;quit.invoke(pause);
   Files.writeString(Path.of(report),"{\"status\":\"PASS\",\"nativePauseSaveAndQuitInvoked\":true,\"physicalInputUsed\":false,\"playerCleared\":"+(client.getClass().getField("field_1724").get(client)==null)+",\"serverCleared\":"+(client.getClass().getMethod("method_1576").invoke(client)==null)+"}");
  }catch(Throwable failure){done=true;var sw=new StringWriter();failure.printStackTrace(new PrintWriter(sw));Files.writeString(Path.of(report),"Step="+step+"\n"+sw);}
 }
}
