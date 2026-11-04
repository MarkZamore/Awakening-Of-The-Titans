import java.lang.instrument.*;import java.lang.reflect.*;import java.nio.file.*;
public class ServerStopAgent{public static void agentmain(String args,Instrumentation inst)throws Exception{
Path dir=Path.of(System.getProperty("user.dir")).toRealPath();if(!dir.toString().contains("artifacts")||!Files.isRegularFile(dir.resolve("qa-source-snapshot.json")))throw new SecurityException("Not a marked QA server");
for(Class<?> c:inst.getAllLoadedClasses())if(c.getName().equals("net.fabricmc.loader.api.FabricLoader")){
Object loader=c.getMethod("getInstance").invoke(null);Object server=c.getMethod("getGameInstance").invoke(loader);if(server==null)throw new IllegalStateException("No actual server");
Method stop=null;for(Class<?> t=server.getClass();t!=null;t=t.getSuperclass())try{stop=t.getDeclaredMethod("method_3747",boolean.class);break;}catch(NoSuchMethodException ignored){}
if(stop==null)throw new NoSuchMethodException("MinecraftServer.stop");stop.setAccessible(true);stop.invoke(server,false);Files.writeString(dir.resolve("qa-native-stop.txt"),"MinecraftServer.method_3747(false), normal save shutdown requested\n");return;
}throw new IllegalStateException("No actual FabricLoader");}}
