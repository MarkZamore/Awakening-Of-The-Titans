package dev.lanminecraft.aotlock.mixin;
import org.spongepowered.asm.mixin.Mixin;
/** The plugin audits and changes the real MCEF method only after it has merged. */
@Mixin(targets="net.minecraft.class_1065",priority=500,remap=false)
public abstract class McefLibrariesMarkerMixin { }
