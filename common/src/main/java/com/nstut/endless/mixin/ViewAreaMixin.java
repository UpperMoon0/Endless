package com.nstut.endless.mixin;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ViewArea.class)
public abstract class ViewAreaMixin {

    private static final int RENDER_SECTIONS = 24;

    /**
     * @author Endless
     * @reason Vanilla ViewArea creates a RenderChunk (with GPU VBOs) for EVERY section.
     * At extended heights this means millions of allocated GPU buffers — instant OOM.
     * Cap the render section count to a reasonable range so only visible sections
     * have RenderChunks. Blocks outside the render range are still placeable
     * and saved; they just aren't rendered.
     */
    @Redirect(
        method = "setSize",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelHeightAccessor;getSectionsCount()I")
    )
    private int capRenderSections(LevelHeightAccessor accessor) {
        return Math.min(accessor.getSectionsCount(), RENDER_SECTIONS);
    }
}
