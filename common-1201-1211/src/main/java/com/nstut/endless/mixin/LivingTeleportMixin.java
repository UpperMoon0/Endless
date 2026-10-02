package com.nstut.endless.mixin;

import com.nstut.endless.compat.create.CreateLogicalGeometry;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Same 1.20.1/1.21.1 native landing/collision/effects, searching only allocated vertical intervals. */
@Mixin(LivingEntity.class)
public abstract class LivingTeleportMixin {
    @Inject(method = "randomTeleport", at = @At("HEAD"), cancellable = true)
    private void endless$allocatedTeleportFloor(double x, double y, double z, boolean particles, CallbackInfoReturnable<Boolean> cir) {
        if (!EndlessLogicalHeights.isActive()) return;
        LivingEntity self = (LivingEntity) (Object) this;
        Level level = self.level();
        double oldX = self.getX(), oldY = self.getY(), oldZ = self.getZ();
        if (!level.hasChunkAt(BlockPos.containing(x, y, z))) { cir.setReturnValue(false); return; }
        int floor = CreateLogicalGeometry.teleportFloor(level, x, y, z);
        if (floor == Integer.MIN_VALUE) { cir.setReturnValue(false); return; }
        // Native decrements the candidate by one for each air cell. Preserve its
        // fractional part while skipping unallocated air intervals.
        double landingY = floor + 1d + (y - Math.floor(y));
        self.teleportTo(x, landingY, z);
        if (!level.noCollision(self) || level.containsAnyLiquid(self.getBoundingBox())) {
            self.teleportTo(oldX, oldY, oldZ);
            cir.setReturnValue(false); return;
        }
        if (particles) level.broadcastEntityEvent(self, (byte) 46);
        if (self instanceof PathfinderMob mob) mob.getNavigation().stop();
        cir.setReturnValue(true);
    }
}
