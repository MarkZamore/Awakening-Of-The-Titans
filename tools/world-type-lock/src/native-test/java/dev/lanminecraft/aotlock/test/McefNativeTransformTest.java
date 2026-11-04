package dev.lanminecraft.aotlock.test;
import dev.lanminecraft.aotlock.McefCacheMixinPlugin;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** Real author Mixin merge, then the exact production postApply factories. No fixture game classes. */
public final class McefNativeTransformTest {
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectories(out);
        MixinBootstrap.init();MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("aot_mcef_merge_test.mixins.json");
        var ctor=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();ctor.setAccessible(true);
        IMixinTransformer transformer=(IMixinTransformer)ctor.newInstance();
        String name="net.minecraft.class_1065";byte[] original=bytes(name);
        byte[] merged=transformer.transformClassBytes(name,name,original);
        if(merged==null||Arrays.equals(original,merged))throw new AssertionError("Author MCEF Mixin did not merge into real ClientPackSource");
        ClassNode nativeMerged=node(merged);McefCacheMixinPlugin.relocateLibraries(nativeMerged);
        long hooks=nativeMerged.methods.stream().flatMap(m->Arrays.stream(m.instructions.toArray())).filter(i->i instanceof MethodInsnNode call&&call.owner.equals("dev/lanminecraft/aotlock/McefCachePaths")&&call.name.equals("librariesDirectory")).count();
        if(hooks!=1)throw new AssertionError("Expected exactly one library factory path hook");
        byte[] cef=bytes("com.cinemamod.mcef.CefUtil");ClassNode browser=node(cef);McefCacheMixinPlugin.relocateBrowser(browser);
        MethodNode init=browser.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();
        int root=0,leaf=0,old=0;for(AbstractInsnNode i:init.instructions)if(i instanceof LdcInsnNode c){if("launcher-runtime".equals(c.cst))root++;if("mcef/browser-cache".equals(c.cst))leaf++;if("mods".equals(c.cst)||"mcef-cache".equals(c.cst))old++;}
        if(root!=1||leaf!=1||old!=0)throw new AssertionError("Browser cache factory did not move exactly outside mods");
        ClassWriter writer=new ClassWriter(0);nativeMerged.accept(writer);Files.write(out.resolve("class_1065-mcef-cache.class"),writer.toByteArray());
        writer=new ClassWriter(0);browser.accept(writer);Files.write(out.resolve("CefUtil-mcef-cache.class"),writer.toByteArray());
        // Every method outside the single factory remains byte-for-byte ASM-identical in each patched class.
        ClassNode before=node(merged);assertOtherMethods(before,nativeMerged,"setupLibraryPath");
        assertOtherMethods(node(cef),browser,"<clinit>");
        String proof="PASS real CefDownloadMixin merged into native class_1065; original="+hash(original)+"; merged="+hash(merged)+". Exactly one library factory hook and one CefUtil browser factory relocated to launcher-runtime/mcef. All other methods unchanged. Runtime plugin ordering, exact-container guard and full CEF initialization require loaded-client QA.";
        Files.writeString(out.resolve("mcef-factory-transform-report.txt"),proof+"\n");System.out.println(proof);
    }
    private static void assertOtherMethods(ClassNode before,ClassNode after,String excluded){
        for(MethodNode original:before.methods){if(original.name.contains(excluded))continue;MethodNode changed=after.methods.stream().filter(m->m.name.equals(original.name)&&m.desc.equals(original.desc)).findFirst().orElseThrow();
            if(!fingerprint(original).equals(fingerprint(changed)))throw new AssertionError("Unexpected MCEF method change: "+original.name);}
    }
    private static String fingerprint(MethodNode m){ClassNode c=new ClassNode();c.version=65;c.access=1;c.name="Audit";c.superName="java/lang/Object";c.methods.add(m);ClassWriter w=new ClassWriter(0);c.accept(w);return hash(w.toByteArray());}
    private static byte[] bytes(String n)throws Exception{try(var in=McefNativeTransformTest.class.getClassLoader().getResourceAsStream(n.replace('.','/')+".class")){if(in==null)throw new AssertionError("Missing actual native class "+n);return in.readAllBytes();}}
    private static ClassNode node(byte[] b){ClassNode n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
    private static String hash(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new RuntimeException(e);}}
}
