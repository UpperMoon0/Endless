package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keep the visibility tree around the camera, just like the sparse render grid. */
@Mixin(targets = "net.minecraft.client.renderer.SectionOcclusionGraph$GraphStorage")
public abstract class SectionOcclusionGraphStorageMixin {
    @ModifyArg(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Octree;<init>(Lnet/minecraft/core/SectionPos;III)V"),
        index = 3
    )
    private int endless$cameraRelativeTreeBase(
        SectionPos cameraSection, int renderDistance, int sectionsPerChunk, int minBlockY
    ) {
        // At distance >= 8 the power-of-two tree spans our 32-section grid.
        // Vanilla then anchors its Y bounds to the dense world's minimum,
        // culling high-Y leaves even though their render sections exist.
        return EndlessLogicalHeights.isActive()
            ? cameraSection.minBlockY() - renderDistance * 16
            : minBlockY;
    }
}
