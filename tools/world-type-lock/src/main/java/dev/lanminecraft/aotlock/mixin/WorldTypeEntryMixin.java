package dev.lanminecraft.aotlock.mixin;

import dev.lanminecraft.aotlock.WorldTypePolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.class_8100$class_8101", remap = false)
public abstract class WorldTypeEntryMixin {
    @Inject(method = "method_48737", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void aot$describeUnavailable(CallbackInfoReturnable<Object> callback) {
        Object label = WorldTypePolicy.unavailableLabel(this);
        if (label != null) callback.setReturnValue(label);
    }
}
