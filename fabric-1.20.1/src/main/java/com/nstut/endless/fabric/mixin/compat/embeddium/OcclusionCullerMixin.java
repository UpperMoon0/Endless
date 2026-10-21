package com.nstut.endless.fabric.mixin.compat.embeddium;

import com.nstut.endless.fabric.compat.EmbeddiumWindowBounds;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller", remap = false)
public abstract class OcclusionCullerMixin implements EmbeddiumWindowBounds {
    @Unique private boolean endless$bounded;
    @Unique private int endless$minSection;
    @Unique private int endless$maxSection;

    @Override
    public void endless$setWindowBounds(int min, int max) {
        endless$bounded = true;
        endless$minSection = min;
        endless$maxSection = max;
    }

    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinSection()I", remap = true))
    private int endless$minimum(Level level) { return endless$bounded ? endless$minSection : level.getMinSection(); }

    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxSection()I", remap = true))
    private int endless$maximum(Level level) { return endless$bounded ? endless$maxSection : level.getMaxSection(); }

    // Keep the renderer's horizontal distance/frustum policy; the section graph bounds Y.
    @Group(name = "endless$verticalDistance", min = 1, max = 1)
    @ModifyArg(method = "isWithinRenderDistance", at = @At(value = "INVOKE",
        target = "Lorg/embeddedt/embeddium/api/render/chunk/RenderSectionDistanceFilter;isWithinDistance(FFFF)Z"), index = 1, require = 0)
    private static float endless$verticalDistance(float delta) {
        return com.nstut.endless.heights.EndlessLogicalHeights.isActive()
            ? com.nstut.endless.vertical.VerticalViewDistance.cullingDelta(delta,
                com.nstut.endless.config.EndlessConfig.getInstance().getRendering().getVerticalViewSections())
            : delta;
    }

    // Upstream Sodium 0.5 has the same culler but no Embeddium distance-filter API.
    @Group(name = "endless$verticalDistance", min = 1, max = 1)
    @ModifyArg(method = "isWithinRenderDistance", at = @At(value = "INVOKE",
        target = "Ljava/lang/Math;abs(F)F"), index = 0, require = 0)
    private static float endless$sodiumVerticalDistance(float delta) {
        return endless$verticalDistance(delta);
    }
}
