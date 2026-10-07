package com.nstut.endless.forge.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.RenderBoundsDistance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Create 6's elevator renderer inherits the 64-block origin distance limit. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class CreateElevatorRopeDistanceMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;shouldRender(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/phys/Vec3;)Z"))
    private boolean endless$distanceToElevatorRope(BlockEntityRenderer<BlockEntity> renderer, BlockEntity entity, Vec3 camera) {
        if (!EndlessLogicalHeights.isActive() || !renderer.getClass().getName().equals("com.simibubi.create.content.contraptions.elevator.ElevatorPulleyRenderer")) return renderer.shouldRender(entity, camera);
        var bounds = entity.getRenderBoundingBox();
        return RenderBoundsDistance.within(camera.x, camera.y, camera.z,
            bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, renderer.getViewDistance());
    }
}
