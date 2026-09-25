package com.nstut.endless.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nstut.endless.debug.EndlessDebugTrace;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Traces the actual 26.1 block-entity render-state extraction/submission pipeline. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherDebugMixin {
    @Shadow private Vec3 cameraPos;

    @Shadow
    public abstract <E extends BlockEntity, S extends BlockEntityRenderState> BlockEntityRenderer<E, S> getRenderer(E blockEntity);

    @Shadow
    public abstract <E extends BlockEntity, S extends BlockEntityRenderState> BlockEntityRenderer<E, S> getRenderer(S renderState);

    @Inject(
        method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Lnet/minecraft/client/renderer/culling/Frustum;)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
        at = @At("HEAD")
    )
    private <E extends BlockEntity, S extends BlockEntityRenderState> void endless$traceExtractCheck(
        E blockEntity, float partialTick, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, Frustum frustum,
        CallbackInfoReturnable<S> cir
    ) {
        BlockPos pos = blockEntity.getBlockPos();
        if (!EndlessDebugTrace.enabled() || !EndlessDebugTrace.highY(pos.getY())) return;
        BlockEntityRenderer<E, S> renderer = getRenderer(blockEntity);
        BlockState cachedState = blockEntity.getBlockState();
        BlockState worldState = blockEntity.hasLevel() ? blockEntity.getLevel().getBlockState(pos) : cachedState;
        boolean validCached = blockEntity.getType().isValid(cachedState);
        AABB bounds = renderer == null ? null : renderer.getRenderBoundingBox(blockEntity);
        boolean frustumVisible = frustum == null || (bounds != null && frustum.isVisible(bounds));
        boolean shouldRender = renderer != null && cameraPos != null && renderer.shouldRender(blockEntity, cameraPos);
        EndlessDebugTrace.state("extract-check:" + pos, "BE_EXTRACT_CHECK",
            "pos=" + pos
                + " type=" + blockEntity.getType()
                + " renderer=" + (renderer == null ? "null" : renderer.getClass().getName())
                + " worldState=" + worldState
                + " cachedState=" + cachedState
                + " validCached=" + validCached
                + " hasLevel=" + blockEntity.hasLevel()
                + " removed=" + blockEntity.isRemoved()
                + " cameraPos=" + cameraPos
                + " bounds=" + bounds
                + " frustumVisible=" + frustumVisible
                + " shouldRender=" + shouldRender);
    }

    @Inject(
        method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Lnet/minecraft/client/renderer/culling/Frustum;)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
        at = @At("RETURN")
    )
    private <E extends BlockEntity, S extends BlockEntityRenderState> void endless$traceExtractResult(
        E blockEntity, float partialTick, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, Frustum frustum,
        CallbackInfoReturnable<S> cir
    ) {
        BlockPos pos = blockEntity.getBlockPos();
        if (!EndlessDebugTrace.enabled() || !EndlessDebugTrace.highY(pos.getY())) return;
        S state = cir.getReturnValue();
        EndlessDebugTrace.state("extract-result:" + pos, "BE_EXTRACT_RESULT",
            "pos=" + pos + " result=" + (state == null ? "null" : state.getClass().getName())
                + (state == null ? "" : " statePos=" + state.blockPos + " stateType=" + state.blockEntityType));
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends BlockEntityRenderState> void endless$traceSubmit(
        S renderState, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState, CallbackInfo ci
    ) {
        BlockPos pos = renderState.blockPos;
        if (pos == null || !EndlessDebugTrace.enabled() || !EndlessDebugTrace.highY(pos.getY())) return;
        BlockEntityRenderer<?, S> renderer = getRenderer(renderState);
        EndlessDebugTrace.state("submit:" + pos, "BE_SUBMIT",
            "pos=" + pos + " state=" + renderState.getClass().getName()
                + " type=" + renderState.blockEntityType
                + " renderer=" + (renderer == null ? "null" : renderer.getClass().getName()));
    }
}
