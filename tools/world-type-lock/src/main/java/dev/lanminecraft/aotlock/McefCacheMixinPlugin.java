package dev.lanminecraft.aotlock;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Two exact factory patches after real MCEF Mixin merging; all downloader/CEF code remains native. */
public final class McefCacheMixinPlugin implements IMixinConfigPlugin {
    public static final String MCEF_SHA256="b5270016b85a6c3c26cc88c11ca3c128b937e6b98d3c86605d48502693f81553";
    @Override public void onLoad(String mixinPackage) {
        if(FabricLoader.getInstance().getEnvironmentType()!=EnvType.CLIENT)
            throw new IllegalStateException("Client-only MCEF cache config was loaded on a server");
        try {
            var mod=FabricLoader.getInstance().getModContainer("mcef").orElseThrow(()->new IllegalStateException("Required exact MCEF base missing"));
            List<Path> paths=mod.getOrigin().getPaths();
            if(paths.size()!=1||!Files.isRegularFile(paths.get(0)))throw new IllegalStateException("MCEF must have one auditable original JAR");
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(paths.get(0))));
            if(!MCEF_SHA256.equals(actual))throw new IllegalStateException("Unsupported MCEF SHA256: "+actual);
        }catch(Exception failure){throw new IllegalStateException("Exact MCEF cache relocation verification failed",failure);}
    }
    @Override public void postApply(String targetName,ClassNode target,String mixinName,IMixinInfo mixinInfo) {
        if(mixinName.endsWith("McefLibrariesMarkerMixin"))relocateLibraries(target);
        else if(mixinName.endsWith("McefBrowserMarkerMixin"))relocateBrowser(target);
        else throw new IllegalStateException("Unexpected MCEF cache marker: "+mixinName);
    }
    /** Public only to let test code inspect the real, already merged native class. */
    public static void relocateLibraries(ClassNode target) {
        if(!target.name.equals("net/minecraft/class_1065"))throw new IllegalStateException("Wrong native MCEF library target");
        List<MethodNode> factories=target.methods.stream().filter(m->m.desc.equals("()V")&&m.name.contains("setupLibraryPath")
            &&countString(m,"mcef.libraries.path")==1&&countString(m,"jcef.path")==1).toList();
        if(factories.size()!=1)throw new IllegalStateException("Expected one exact merged MCEF setupLibraryPath factory");
        MethodNode factory=factories.get(0);
        int rawRoots=countString(factory,"mods/mcef-libraries/");
        if(rawRoots!=1||countString(factory,"../build")!=1||countString(factory,"mcef-libraries/")!=1)
            throw new IllegalStateException("MCEF library factory branches changed");
        List<MethodInsnNode> mkdirs=new ArrayList<>();
        for(AbstractInsnNode i:factory.instructions)if(i instanceof MethodInsnNode m&&m.owner.equals("java/io/File")&&m.name.equals("mkdirs")&&m.desc.equals("()Z"))mkdirs.add(m);
        if(mkdirs.size()!=1)throw new IllegalStateException("Expected one MCEF native root mkdirs");
        AbstractInsnNode rootLoad=mkdirs.get(0).getPrevious();
        if(!(rootLoad instanceof VarInsnNode v)||v.getOpcode()!=Opcodes.ALOAD||v.var!=0)
            throw new IllegalStateException("MCEF factory root local changed");
        InsnList replacement=new InsnList();
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/lanminecraft/aotlock/McefCachePaths","librariesDirectory","()Ljava/io/File;",false));
        replacement.add(new VarInsnNode(Opcodes.ASTORE,0));
        factory.instructions.insertBefore(rootLoad,replacement);
    }
    public static void relocateBrowser(ClassNode target) {
        if(!target.name.equals("com/cinemamod/mcef/CefUtil"))throw new IllegalStateException("Wrong native MCEF browser target");
        List<MethodNode> inits=target.methods.stream().filter(m->m.name.equals("<clinit>")&&m.desc.equals("()V")).toList();
        if(inits.size()!=1)throw new IllegalStateException("Expected one native CefUtil initializer");
        MethodNode init=inits.get(0);
        if(countString(init,"mods")!=1||countString(init,"mcef-cache")!=1)throw new IllegalStateException("Native MCEF browser factory changed");
        int writes=0;
        for(AbstractInsnNode i:init.instructions)if(i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTSTATIC&&f.owner.equals(target.name)&&f.name.equals("CACHE_PATH")&&f.desc.equals("Ljava/nio/file/Path;"))writes++;
        if(writes!=1)throw new IllegalStateException("Expected one native MCEF CACHE_PATH write");
        for(AbstractInsnNode i:init.instructions)if(i instanceof LdcInsnNode c){
            if("mods".equals(c.cst))c.cst="launcher-runtime";
            else if("mcef-cache".equals(c.cst))c.cst="mcef/browser-cache";
        }
    }
    private static int countString(MethodNode m,String value){int n=0;for(AbstractInsnNode i:m.instructions)if(i instanceof LdcInsnNode c&&value.equals(c.cst))n++;return n;}
    @Override public String getRefMapperConfig(){return null;}
    @Override public boolean shouldApplyMixin(String targetClassName,String mixinClassName){return true;}
    @Override public void acceptTargets(Set<String> myTargets,Set<String> otherTargets) { }
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String targetName,ClassNode target,String mixinName,IMixinInfo info) { }
}
