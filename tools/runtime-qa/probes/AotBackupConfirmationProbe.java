import java.lang.reflect.*;import java.nio.file.*;
/** Native "proceed without backup" only for the marked disposable QA world. */
public final class AotBackupConfirmationProbe {
 private static boolean done;
 public static void tick(Object client,Object server,Object player,String report)throws Exception{
  if(done)return;Object screen=client.getClass().getField("field_1755").get(client);
  if(screen==null||!screen.getClass().getName().equals("net.minecraft.class_405"))return;
  Path root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath();
  if(!Files.isRegularFile(root.resolve(".aot-runtime-qa.json")))throw new SecurityException("Unmarked QA fixture");
  Method skip=screen.getClass().getDeclaredMethod("method_19792",Class.forName("net.minecraft.class_4185",false,client.getClass().getClassLoader()));
  skip.setAccessible(true);done=true;skip.invoke(screen,new Object[]{null});
  Files.writeString(Path.of(report),"{\"nativeBackupJoinSkipInvoked\":true,\"eraseCache\":false,\"personalWorld\":false,\"physicalInputUsed\":false}");
 }
}
