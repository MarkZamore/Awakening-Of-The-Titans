import java.lang.reflect.*;import java.nio.file.*;import java.util.*;
/** Read-only actual GPU atlas/committed sprite geometry inspection. */
public final class AotGpuAtlasProbe {
 private static boolean done;
 private static Object value(Class<?> type,Object instance,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(instance);}
 public static void tick(Object client,Object server,Object player,String report)throws Exception{
  if(done||player==null)return;done=true;var out=new LinkedHashMap<String,Object>();
  try{
   Path root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath();if(!Files.isRegularFile(root.resolve(".aot-runtime-qa.json")))throw new SecurityException("Unmarked fixture");
   var loader=client.getClass().getClassLoader();Class<?> gl=Class.forName("org.lwjgl.opengl.GL11",false,loader),atlasType=Class.forName("net.minecraft.class_1059",false,loader),spriteType=Class.forName("net.minecraft.class_1058",false,loader),contentType=Class.forName("net.minecraft.class_7764",false,loader);
   Method integer=gl.getMethod("glGetInteger",int.class),bind=gl.getMethod("glBindTexture",int.class,int.class),param=gl.getMethod("glGetTexLevelParameteri",int.class,int.class,int.class);
   int maximum=(Integer)integer.invoke(null,3379),previous=(Integer)integer.invoke(null,32873);boolean valid=maximum>0;
   Object manager=client.getClass().getMethod("method_1531").invoke(client);Class<?> managerType=Class.forName("net.minecraft.class_1060",false,loader);
   Map<?,?> textures=(Map<?,?>)value(managerType,manager,"field_5286");var atlases=new ArrayList<Map<String,Object>>();
   try{for(var entry:textures.entrySet())if(atlasType.isInstance(entry.getValue())){
    Object atlas=entry.getValue();int width=(Integer)value(atlasType,atlas,"field_43113"),height=(Integer)value(atlasType,atlas,"field_43114"),id=(Integer)atlasType.getMethod("method_4624").invoke(atlas);
    bind.invoke(null,3553,id);int gpuW=(Integer)param.invoke(null,3553,0,4096),gpuH=(Integer)param.invoke(null,3553,0,4097);boolean exists=(Boolean)gl.getMethod("glIsTexture",int.class).invoke(null,id);
    Map<?,?> sprites=(Map<?,?>)value(atlasType,atlas,"field_5280");boolean inside=true;int maxW=0,maxH=0;long area=0;
    for(Object sprite:sprites.values()){int x=(Integer)value(spriteType,sprite,"field_5258"),y=(Integer)value(spriteType,sprite,"field_5256");Object content=value(spriteType,sprite,"field_40553");int w=(Integer)value(contentType,content,"field_40537"),h=(Integer)value(contentType,content,"field_40538");inside&=x>=0&&y>=0&&w>0&&h>0&&x+w<=width&&y+h<=height;maxW=Math.max(maxW,w);maxH=Math.max(maxH,h);area+=(long)w*h;}
    boolean pass=exists&&width==gpuW&&height==gpuH&&width>0&&height>0&&width<=maximum&&height<=maximum&&inside&&!sprites.isEmpty();valid&=pass;
    var row=new LinkedHashMap<String,Object>();row.put("atlas",String.valueOf(entry.getKey()));row.put("width",width);row.put("height",height);row.put("gpuWidth",gpuW);row.put("gpuHeight",gpuH);row.put("gpuTextureExists",exists);row.put("spriteCount",sprites.size());row.put("largestSpriteWidth",maxW);row.put("largestSpriteHeight",maxH);row.put("logicalSpriteAreaPixels",area);row.put("allCommittedSpritesWithinAtlas",inside);row.put("pass",pass);atlases.add(row);
   }}finally{bind.invoke(null,3553,previous);}
   out.put("status",valid&&!atlases.isEmpty()?"PASS":"FAIL");out.put("gpuMaxTextureSize",maximum);out.put("renderer",gl.getMethod("glGetString",int.class).invoke(null,7937));out.put("textureManagerClass",manager.getClass().getName());out.put("atlases",atlases);out.put("renderQualityChanged",false);out.put("textureBindingRestored",true);out.put("scope","Actual uploaded native atlas textures and committed sprite bounds after resource reload/world entry. Area alone is not a packing proof; shader and depth-blit compatibility are separate checks.");
  }catch(Throwable failure){out.put("status","FAIL");out.put("error",failure.toString());}
  Class<?> gson=Class.forName("com.google.gson.Gson",true,client.getClass().getClassLoader());Files.writeString(Path.of(report),(String)gson.getMethod("toJson",Object.class).invoke(gson.getConstructor().newInstance(),out));
 }
}
