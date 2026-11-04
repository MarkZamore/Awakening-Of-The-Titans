import java.nio.file.*;
import java.lang.reflect.*;
import java.util.Arrays;
public final class YosbrFileCopyProbe {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);
  Class<?> provider=Class.forName("net.fabricmc.loader.impl.game.GameProvider");
  Object proxy=Proxy.newProxyInstance(provider.getClassLoader(),new Class<?>[]{provider},(object,method,values)->{
   if(method.getName().equals("getLaunchDirectory"))return root;
   if(method.getReturnType()==boolean.class)return false;
   if(method.getReturnType()==int.class)return 0;
   return null;
  });
  Class<?> loader=Class.forName("net.fabricmc.loader.impl.FabricLoaderImpl");
  Object instance=loader.getField("INSTANCE").get(null);
  loader.getMethod("setGameProvider",provider).invoke(instance,proxy);
  byte[] defaults=Files.readAllBytes(Path.of(args[1]));
  Path source=root.resolve("config/yosbr/options.txt");Files.createDirectories(source.getParent());Files.write(source,defaults);
  Class<?> yosbr=Class.forName("me.shedaniel.yosbr.YourOptionsShallBeRespected");
  yosbr.getConstructor().newInstance();Path target=root.resolve("options.txt");
  if(!Arrays.equals(defaults,Files.readAllBytes(target)))throw new AssertionError("Missing-file seed differs");
  byte[] personal="resourcePacks:[\"vanilla\"]\r\nrenderDistance:29\r\nsimulationDistance:23\r\nfov:0.8\r\nlang:en_us\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  Files.write(target,personal);long stamp=Files.getLastModifiedTime(target).toMillis();
  Files.writeString(source,"resourcePacks:[\"vanilla\",\"file/new-author.zip\"]\n");
  yosbr.getConstructor().newInstance();
  if(!Arrays.equals(personal,Files.readAllBytes(target)) || stamp!=Files.getLastModifiedTime(target).toMillis())throw new AssertionError("Existing personal options were changed");
  Files.delete(target);yosbr.getConstructor().newInstance();
  if(!Arrays.equals(Files.readAllBytes(source),Files.readAllBytes(target)))throw new AssertionError("Native new-file reinitialization failed");
  System.out.println("PASS_ACTUAL_YOSBR_MISSING_FILE_ONLY_AND_PERSONAL_BYTES_MTIME_PRESERVED");
 }
}
