import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.*;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.cache.object.*;

/** Passive disposable-fixture observer. Draws no geometry and changes no native flags. */
@SuppressWarnings({"rawtypes","unchecked"})
public final class AotCameraMeshProbe {
 static class_310 client; static Path report; static long frames; static String lastPhase="";
 static final Set<Object> installed=Collections.newSetFromMap(new IdentityHashMap<>());
 static final Set<String> samples=new HashSet<>();
 static int humanRenders,titanRenders; static boolean eventInstalled;
 static final Map<String,Long> counts=new TreeMap<>();
 public static void tick(Object c,Object server,Object player,String path)throws Exception {
  client=(class_310)c; report=Path.of(path);
  Path root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath();
  if(!report.toAbsolutePath().normalize().startsWith(root)||!Files.isRegularFile(root.resolve(".aot-runtime-qa.json")))throw new SecurityException("QA fixture required");
  if(!eventInstalled){installEvent();eventInstalled=true;write("observer_installed", "\"noGeometry\":true,\"noRenderFlagsChanged\":true,\"physicalVisual\":false");}
  if(player==null)return;
  class_898 dispatcher=client.method_1561();
  Object r=dispatcher.method_3953((class_1297)player); installHuman(r,dispatcher);
  class_1297 vehicle=((class_1297)player).method_5854();
  if(vehicle!=null){Object vr=dispatcher.method_3953(vehicle);installGeo(vr);}
  String phase=phase();if(!phase.equals(lastPhase)){lastPhase=phase;write("phase", "\"phase\":"+q(phase)+",\"frames\":"+frames);}
  write("summary","\"phase\":"+q(phase)+",\"frames\":"+frames+",\"humanRenderCalls\":"+humanRenders+",\"titanRenderCalls\":"+titanRenders+",\"counts\":"+map(counts));
 }
 static void installEvent()throws Exception{
  ClassLoader l=client.getClass().getClassLoader();
  Class<?> e=Class.forName("net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents",true,l);
  Object event=e.getField("END").get(null);
  Class<?> listener=Class.forName("net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents$End",true,l);
  Object proxy=Proxy.newProxyInstance(l,new Class[]{listener},(p,m,a)->{if(m.getDeclaringClass()==Object.class){if(m.getName().equals("toString"))return "PassiveCameraObserver";if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];}frames++;counts.merge("frames/"+phase(),1L,Long::sum);return null;});
  Class.forName("net.fabricmc.fabric.api.event.Event",true,l).getMethod("register",Object.class).invoke(event,proxy);
 }
 static void installHuman(Object r,class_898 d)throws Exception{
  if(!(r instanceof class_1007)||!installed.add(r))return;
  Field f=class_922.class.getDeclaredField("field_4738");f.setAccessible(true);
  ((List)f.get(r)).add(new Human((class_3883)r,d.method_43336()));
  write("player_layer_installed","\"renderer\":"+q(r.getClass().getName()));
 }
 static void installGeo(Object r)throws Exception{
  if(!(r instanceof GeoRenderer)||!installed.add(r))return;
  ((GeoRenderer)r).getRenderLayers().add(new Titan((GeoRenderer)r));
  write("titan_layer_installed","\"renderer\":"+q(r.getClass().getName()));
 }
 static final class Human extends class_5697 {
  Human(class_3883 r,class_759 item){super(r,item);}
  @Override public void method_17162(class_4587 pose,class_4597 consumers,int light,class_1309 entity,float a,float b,float c,float d,float e,float f){
   if(entity!=client.field_1724)return;
   humanRenders++; class_572 model=(class_572)method_17165();
   boolean head=model.field_3398.field_3665,hat=model.field_3394.field_3665;
   String phase=phase(),key="human/"+phase+"/head="+head+"/hat="+hat;
   counts.merge(key,1L,Long::sum);
   if(samples.add(key))write("native_player_render","\"phase\":"+q(phase)+",\"headVisible\":"+head+",\"hatVisible\":"+hat+",\"headSkipDraw\":"+model.field_3398.field_38456+",\"hatSkipDraw\":"+model.field_3394.field_38456+",\"kosmxFirstPersonPass\":"+fp("dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode")+",\"zigyFirstPersonPass\":"+fp("com.zigythebird.playeranimcore.api.firstPerson.FirstPersonMode"));
  }
 }
 static final class Titan extends GeoRenderLayer {
  Titan(GeoRenderer r){super(r);}
  @Override public void render(class_4587 pose,GeoAnimatable entity,BakedGeoModel baked,class_1921 type,class_4597 consumers,class_4588 vertex,float tick,int light,int overlay){
   if(client.field_1724==null||client.field_1724.method_5854()!=entity)return;
   titanRenders++;for(String name:new String[]{"head","head2"}){
    GeoBone bone=getGeoModel().getAnimationProcessor().getBone(name);if(bone==null)continue;
    String phase=phase(),key="titan/"+phase+"/"+name+"/hidden="+bone.isHidden();counts.merge(key,1L,Long::sum);
    if(samples.add(key))write("native_titan_render","\"phase\":"+q(phase)+",\"bone\":"+q(name)+",\"hidden\":"+bone.isHidden()+",\"childrenHidden\":"+bone.isHidingChildren()+",\"note\":\"visibility observation; no camera intersection or image claim\"");
   }
  }
 }
 static boolean fp(String name){try{return (boolean)Class.forName(name,true,client.getClass().getClassLoader()).getMethod("isFirstPersonPass").invoke(null);}catch(Exception e){return false;}}
 static String phase(){try{if(client.field_1724==null)return "no-player";String perspective=client.field_1690.method_31044().toString();class_1297 v=client.field_1724.method_5854();String who=v==null?"human":v.getClass().getName();return perspective+"/"+who+"/alive="+client.field_1724.method_5805();}catch(Throwable e){return "unknown";}}
 static void write(String event,String fields){try{Files.writeString(report,"{\"time\":"+q(java.time.Instant.now().toString())+",\"event\":"+q(event)+","+fields+"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}}
 static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
 static String map(Map<String,Long> m){StringJoiner s=new StringJoiner(",","{","}");for(var e:m.entrySet())s.add(q(e.getKey())+":"+e.getValue());return s.toString();}
}
