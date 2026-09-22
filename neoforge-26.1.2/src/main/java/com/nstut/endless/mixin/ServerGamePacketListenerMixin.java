package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import java.util.Set;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real player use/break packets must use the logical ceiling, not dense array geometry. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow public abstract void resetPosition();

    @Inject(
        method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V",
        at = @At("RETURN"))
    private void endless$rebaseTeleport(PositionMoveRotation destination, Set<Relative> relative, CallbackInfo ci) {
        if (EndlessLogicalHeights.isActive()) {
            resetPosition();
        }
    }

    @Redirect(method = {"handleUseItemOn", "handlePlayerAction"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxY()I"))
    private int endless$interactionCeiling(Level level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMaxBuildHeight() - 1 : level.getMaxY();
    }

    @Redirect(method = "handleUseItemOn",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private int endless$interactionFloor(Level level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMinBuildHeight() : level.getMinY();
    }
}
