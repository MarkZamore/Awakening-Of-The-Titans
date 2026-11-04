package dev.lanminecraft.aotlock;

import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Exact-base repair of GlobalSpawn's raw respawn position; uses its own native resolver and guard. */
public final class SafeRespawnPolicy implements ModInitializer {
    public static final String GLOBALSPAWN_SHA256="8c9085326528aa1b13d4a110065a80bc6abd2dfe49fd353618b23648e593f9a9";
    private static volatile boolean verified;

    @Override public void onInitialize() {
        try {
            var base=FabricLoader.getInstance().getModContainer("globalspawn").orElseThrow(
                ()->new IllegalStateException("Required exact GlobalSpawn base is absent"));
            List<Path> paths=base.getOrigin().getPaths();
            if(paths.size()!=1||!Files.isRegularFile(paths.get(0)))
                throw new IllegalStateException("GlobalSpawn must have one auditable original JAR");
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(paths.get(0))));
            if(!GLOBALSPAWN_SHA256.equals(actual))throw new IllegalStateException("Unsupported GlobalSpawn base SHA256: "+actual);
            verified=true;
        } catch(Exception failure){throw new IllegalStateException("Exact GlobalSpawn respawn repair verification failed",failure);}
    }

    /** Invoked only at the sole vanilla respawn-position validation call; never on joins or teleports. */
    public static Object position(Object player,Object targetWorld,Object original) {
        if(!verified)throw new IllegalStateException("GlobalSpawn exact-base verification did not run");
        try {
            Method nativeGuard=null;
            for(Class<?> c=player.getClass();c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())
                if(m.getName().equals("globalspawn$shouldOverrideRespawn")&&m.getParameterCount()==0&&m.getReturnType()==boolean.class){
                    if(nativeGuard!=null)throw new IllegalStateException("Ambiguous native GlobalSpawn guard");nativeGuard=m;
                }
            if(nativeGuard==null)throw new IllegalStateException("Required native GlobalSpawn guard is unavailable");
            nativeGuard.setAccessible(true);
            if(!((Boolean)nativeGuard.invoke(player)))return original; // Personal valid bed/anchor, or disabled global spawn.
            ClassLoader loader=player.getClass().getClassLoader();
            Object point=Class.forName("de.dafuqs.globalspawn.GlobalSpawnManager",false,loader).getMethod("getGlobalRespawnPoint").invoke(null);
            if(!Objects.equals(original,call(point,"getPos")))throw new IllegalStateException("Unexpected GlobalSpawn validation position");
            if(!Objects.equals(call(targetWorld,"method_27983"),call(point,"getDimension")))throw new IllegalStateException("Unexpected GlobalSpawn validation dimension");
            Field serverField=null;
            for(Class<?> c=player.getClass();c!=null;c=c.getSuperclass())try{serverField=c.getDeclaredField("field_13995");break;}catch(NoSuchFieldException ignored){}
            if(serverField==null)throw new NoSuchFieldException("ServerPlayer.server");serverField.setAccessible(true);
            Object result=call(point,"getFinalSpawnPos",serverField.get(player));
            if(result==null)throw new IllegalStateException("Native GlobalSpawn safe resolver returned no position");
            return result;
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native GlobalSpawn safe respawn resolution failed",failure);}
    }

    private static Object call(Object target,String name,Object...args)throws ReflectiveOperationException {
        for(Method method:target.getClass().getMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){
            method.trySetAccessible();return method.invoke(target,args);
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
}
