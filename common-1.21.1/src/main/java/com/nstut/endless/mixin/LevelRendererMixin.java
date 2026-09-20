package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveRenderProbe;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep vanilla's render graph inside the logical sparse range, not the dense section-array core. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Redirect(
        method = {"initializeQueueForFullUpdate", "updateRenderChunks", "getRelativeFrom"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinBuildHeight()I")
    )
    private int endless$logicalRenderMin(ClientLevel level) {
        return EndlessLogicalHeights.isActive() ? EndlessHeights.getMinBuildHeight() : level.getMinBuildHeight();
    }

    @Redirect(
        method = {"initializeQueueForFullUpdate", "updateRenderChunks", "getRelativeFrom"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMaxBuildHeight()I")
    )
    private int endless$logicalRenderMax(ClientLevel level) {
        return EndlessLogicalHeights.isActive() ? EndlessHeights.getMaxBuildHeight() : level.getMaxBuildHeight();
    }

    @Inject(method = "getRelativeFrom", at = @At("RETURN"))
    private void endless$recordSparseGraphTraversal(
        BlockPos cameraOrigin,
        SectionRenderDispatcher.RenderSection source,
        Direction direction,
        CallbackInfoReturnable<SectionRenderDispatcher.RenderSection> cir
    ) {
        SectionRenderDispatcher.RenderSection result = cir.getReturnValue();
        if (EndlessLogicalHeights.isActive() && result != null) {
            LiveRenderProbe.recordRenderGraph(result.getOrigin());
        }
    }
}
