import java.nio.file.*;
import java.util.*;
import net.minecraft.*;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.*;
import software.bernie.geckolib.renderer.layer.*;
import software.bernie.geckolib.cache.object.*;
import software.bernie.geckolib.util.RenderUtil;

/** Read-only exact native head-quad geometry observer; zero draw calls and no flag writes. */
@SuppressWarnings({"rawtypes","unchecked"})
public final class AotHeadGeometryProbe {
 static class_310 client;static Path output;static Set<Object> installed=Collections.newSetFromMap(new IdentityHashMap<>());static Map<String,Long> sampled=new HashMap<>();
 public static void tick(Object c,Object s,Object p,String path)throws Exception{
  client=(class_310)c;output=Path.of(path);Path root=Path.of(System.getProperty("lanminecraft.qa.root")).toRealPath();if(!Files.isRegularFile(root.resolve(".aot-runtime-qa.json"))||!output.toAbsolutePath().normalize().startsWith(root))throw new SecurityException("QA fixture only");
  if(p==null)return;class_1297 v=((class_1297)p).method_5854();if(v==null)return;Object r=client.method_1561().method_3953(v);if(r instanceof GeoRenderer&&installed.add(r)){((GeoRenderer)r).getRenderLayers().add(new Layer((GeoRenderer)r));write("geometry_observer_installed",Map.of("renderer",r.getClass().getName(),"zeroDraw",true,"noFlagWrites",true));}
 }
 static final class Layer extends GeoRenderLayer {
  Layer(GeoRenderer r){super(r);}
  @Override public void renderForBone(class_4587 pose,GeoAnimatable entity,GeoBone bone,class_1921 type,class_4597 consumers,class_4588 vertex,float tick,int light,int overlay){
   if(client.field_1724==null||client.field_1724.method_5854()!=entity)return;
   boolean head=false;for(GeoBone b=bone;b!=null;b=b.getParent())if(b.getName().equals("head")||b.getName().equals("head2"))head=true;
   if(!head||bone.getCubes().isEmpty())return;
   String phase=client.field_1690.method_31044()+"/"+entity.getClass().getName()+"/dismount="+dismount(entity),key=phase+"/"+bone.getName();
   long now=System.nanoTime();Long prev=sampled.get(key);if(prev!=null&&now-prev<5_000_000_000L)return;sampled.put(key,now);
   try{
    Matrix4f model=new Matrix4f(pose.method_23760().method_23761()),mv=new Matrix4f(RenderSystem.getModelViewMatrix()),projection=new Matrix4f(RenderSystem.getProjectionMatrix());
    double[] min={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},max={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};int quads=0,nearCross=0,centerCover=0;double nearestRay=Double.POSITIVE_INFINITY;
    for(GeoCube cube:bone.getCubes()){
     class_4587 local=new class_4587();local.method_34425(new Matrix4f(model));RenderUtil.translateToPivotPoint(local,cube);RenderUtil.rotateMatrixAroundCube(local,cube);RenderUtil.translateAwayFromPivotPoint(local,cube);
     Matrix4f view=new Matrix4f(mv).mul(local.method_23760().method_23761()),clipMatrix=new Matrix4f(projection).mul(view);
     for(GeoQuad q:cube.quads())if(q!=null){quads++;boolean before=false,after=false;Vector3f[] vs=new Vector3f[q.vertices().length];Vector4f[] clips=new Vector4f[vs.length];
      for(int i=0;i<vs.length;i++){Vector3f v=view.transformPosition(new Vector3f(q.vertices()[i].position()));vs[i]=v;double[] xyz={v.x,v.y,v.z};for(int k=0;k<3;k++){min[k]=Math.min(min[k],xyz[k]);max[k]=Math.max(max[k],xyz[k]);}Vector3f pos=q.vertices()[i].position();Vector4f clip=clipMatrix.transform(new Vector4f(pos.x,pos.y,pos.z,1));clips[i]=clip;if(clip.z+clip.w<0)before=true;else after=true;}
      if(before&&after)nearCross++;if(vs.length==4){for(int[] tri:new int[][]{{0,1,2},{0,2,3}}){double hit=ray(vs[tri[0]],vs[tri[1]],vs[tri[2]]);if(hit>0){centerCover++;nearestRay=Math.min(nearestRay,hit);}}}
     }
    }
    class_4184 camera=client.field_1773.method_19418();class_243 pos=camera.method_19326();Map<String,Object> row=new LinkedHashMap<>();row.put("phase",phase);row.put("bone",bone.getName());row.put("hidden",bone.isHidden());row.put("childrenHidden",bone.isHidingChildren());row.put("quads",quads);row.put("viewMin",min);row.put("viewMax",max);row.put("nearPlaneCrossingQuads",nearCross);row.put("centerRayTriangleHits",centerCover);row.put("nearestCenterRayDistance",Double.isFinite(nearestRay)?nearestRay:null);row.put("cameraWorld",new double[]{pos.field_1352,pos.field_1351,pos.field_1350});row.put("boneTrackingNative",bone.isTrackingMatrices());row.put("boneWorldMatrix",arr(bone.getWorldSpaceMatrix()));row.put("nativePoseMatrix",arr(model));row.put("modelViewMatrix",arr(mv));row.put("projectionMatrix",arr(projection));row.put("interpretation","Actual native head quad geometry and active RenderSystem matrices; not an image or global occlusion verdict");write("native_head_geometry",row);
   }catch(Throwable e){write("geometry_observer_failure",Map.of("exception",e.toString()));}
  }
 }
 static boolean dismount(Object o){try{return (boolean)o.getClass().getMethod("isDismounting").invoke(o);}catch(Exception e){return false;}}
 static double ray(Vector3f a,Vector3f b,Vector3f c){Vector3f dir=new Vector3f(0,0,-1),e1=new Vector3f(b).sub(a),e2=new Vector3f(c).sub(a),h=new Vector3f(dir).cross(e2);float det=e1.dot(h);if(java.lang.Math.abs(det)<1e-7)return -1;float f=1/det;Vector3f s=new Vector3f(a).negate();float u=f*s.dot(h);if(u<0||u>1)return -1;Vector3f q=new Vector3f(s).cross(e1);float v=f*dir.dot(q);if(v<0||u+v>1)return -1;return f*e2.dot(q);}
 static float[] arr(Matrix4f m){return m.get(new float[16]);}
 static synchronized void write(String event,Map<String,?> data){try{Map<String,Object> row=new LinkedHashMap<>();row.put("time",java.time.Instant.now().toString());row.put("event",event);row.putAll(data);Files.writeString(output,json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}}
 static String json(Object v){if(v==null)return "null";if(v instanceof Boolean||v instanceof Number)return v.toString();if(v instanceof double[] a){StringJoiner j=new StringJoiner(",","[","]");for(double x:a)j.add(Double.isFinite(x)?Double.toString(x):"null");return j.toString();}if(v instanceof float[] a){StringJoiner j=new StringJoiner(",","[","]");for(float x:a)j.add(Float.isFinite(x)?Float.toString(x):"null");return j.toString();}if(v instanceof Map<?,?> m){StringJoiner j=new StringJoiner(",","{","}");for(var e:m.entrySet())j.add(json(e.getKey().toString())+":"+json(e.getValue()));return j.toString();}return "\""+v.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
}
