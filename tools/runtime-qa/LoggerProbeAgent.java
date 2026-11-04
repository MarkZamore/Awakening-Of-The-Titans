import java.lang.instrument.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.*;
public class LoggerProbeAgent{public static void agentmain(String ignored,Instrumentation inst)throws Exception{
Path dir=Path.of(System.getProperty("user.dir")).toRealPath();if(!dir.toString().contains("artifacts")||!Files.isRegularFile(dir.resolve("qa-source-snapshot.json")))throw new SecurityException("Not a marked QA server");
for(Class<?> c:inst.getAllLoadedClasses())if(c.getName().equals("net.minecraft.class_2535")){var names=new ArrayList<String>();for(Field f:c.getDeclaredFields())if(Modifier.isStatic(f.getModifiers())&&f.getType().getName().equals("org.slf4j.Logger")){f.setAccessible(true);Object log=f.get(null);names.add((String)f.getType().getMethod("getName").invoke(log));}Files.writeString(dir.resolve("qa-native-logger-names.json"),"{\"connectionLoggers\":[\""+String.join("\",\"",names)+"\"]}\n");return;}
throw new IllegalStateException("Connection not yet loaded");}}
