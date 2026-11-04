package dev.lanminecraft.aotlock.mixin;

import dev.lanminecraft.aotlock.SafeRespawnPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(targets="net.minecraft.class_3222",remap=false)
public abstract class ServerPlayerSafeRespawnMixin {
    @ModifyArgs(method="method_60590(ZLnet/minecraft/class_5454$class_9823;)Lnet/minecraft/class_5454;",
        at=@At(value="INVOKE",target="Lnet/minecraft/class_3222;method_60588(Lnet/minecraft/class_3218;Lnet/minecraft/class_2338;FZZ)Ljava/util/Optional;",ordinal=0),
        require=1,expect=1,allow=1,remap=false)
    private void aotlock$resolveNativeGlobalRespawn(Args args) {
        args.set(1,SafeRespawnPolicy.position(this,args.get(0),args.get(1)));
    }
}
