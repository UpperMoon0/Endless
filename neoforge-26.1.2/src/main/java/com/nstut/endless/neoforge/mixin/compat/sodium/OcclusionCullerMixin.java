package com.nstut.endless.neoforge.mixin.compat.sodium;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller", remap = false)
public abstract class OcclusionCullerMixin {
    // Async culls hold an immutable viewport while the main camera can rebase.
    // Logical bounds remain stable; the manager's node graph stays bounded to
    // 32 sections. Reading mutable window bounds here would race old cull jobs.
    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinSectionY()I", remap = true))
    private int endless$minimum(Level level) {
        return com.nstut.endless.heights.EndlessLogicalHeights.isActive()
            ? com.nstut.endless.heights.EndlessLogicalHeights.minSection() : level.getMinSectionY();
    }
    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxSectionY()I", remap = true))
    private int endless$maximum(Level level) {
        return com.nstut.endless.heights.EndlessLogicalHeights.isActive()
            ? com.nstut.endless.heights.EndlessLogicalHeights.maxSectionExclusive() - 1 : level.getMaxSectionY();
    }
}
