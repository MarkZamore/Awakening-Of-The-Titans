package dev.lanminecraft.aotlock.mixin;

import dev.lanminecraft.aotlock.WorldTypePolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.class_8100", remap = false)
public abstract class WorldCreationUiStateMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 1, remap = false)
    private void aot$finishInitialization(CallbackInfo callback) {
        WorldTypePolicy.finishInitialization(this);
    }

    @Inject(method = "method_48735()V", at = @At("RETURN"), require = 1, remap = false)
    private void aot$restrictPresets(CallbackInfo callback) {
        WorldTypePolicy.refresh(this);
    }
}
