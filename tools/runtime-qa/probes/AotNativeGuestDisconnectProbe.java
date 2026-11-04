import java.lang.reflect.*;import java.nio.file.*;import java.io.*;
/** Native ordinary guest disconnect, with full private QA diagnostic on failure. */
public final class AotNativeGuestDisconnectProbe {
 private static boolean done;
 public static void tick(Object client,Object server,Object player,String report)throws Exception{
  if(done||player==null)return;done=true;
  String step="guard";
  try{
   Path root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath();
   if(!Files.isRegularFile(root.resolve(".aot-runtime-qa.json"))||server!=null)throw new SecurityException("Only marked native guest fixture");
   step="world.disconnect";Object world=client.getClass().getField("field_1687").get(client);
   world.getClass().getMethod("method_8525").invoke(world);
   step="client.disconnect";client.getClass().getMethod("method_18099").invoke(client);
   step="native.title";var loader=client.getClass().getClassLoader();
   Class<?> screen=Class.forName("net.minecraft.class_437",false,loader),title=Class.forName("net.minecraft.class_442",false,loader);
   client.getClass().getMethod("method_1507",screen).invoke(client,title.getConstructor().newInstance());
   Files.writeString(Path.of(report),"{\"nativeGuestDisconnectInvoked\":true,\"physicalInputUsed\":false,\"playerCleared\":"+(client.getClass().getField("field_1724").get(client)==null)+",\"serverCleared\":"+(client.getClass().getMethod("method_1576").invoke(client)==null)+"}");
  }catch(Throwable failure){var sw=new StringWriter();failure.printStackTrace(new PrintWriter(sw));Files.writeString(Path.of(report),"Step="+step+"\n"+sw);}
 }
}
