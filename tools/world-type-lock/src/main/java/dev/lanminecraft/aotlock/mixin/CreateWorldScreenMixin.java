package dev.lanminecraft.aotlock.mixin;

import dev.lanminecraft.aotlock.WorldTypePolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.class_525", remap = false)
public abstract class CreateWorldScreenMixin {
    @Inject(method = "method_2736()V", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void aot$enforceCreation(CallbackInfo callback) {
        if (!WorldTypePolicy.beforeCreate(this)) callback.cancel();
    }
}
