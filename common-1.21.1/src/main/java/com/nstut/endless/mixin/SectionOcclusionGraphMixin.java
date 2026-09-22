package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveRenderProbe;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records sparse render-graph traversal after ViewArea resolves a neighboring section. */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphMixin {
    @Inject(
        method = "getRelativeFrom(Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;Lnet/minecraft/core/Direction;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;",
        at = @At("RETURN")
    )
    private void endless$recordSparseGraphTraversal(
        BlockPos sectionPos,
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
