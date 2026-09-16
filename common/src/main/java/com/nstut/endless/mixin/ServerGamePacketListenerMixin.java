package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.entity.RelativeMovement;
import java.util.Set;

/** Real player use/break packets must use the logical ceiling, not dense array geometry. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow public abstract void resetPosition();

    /**
     * A teleport can be acknowledged before the next connection tick. Vanilla
     * updates lastGood on acknowledgement but leaves firstGood at the old
     * location, so the following move packet can trigger a second teleport and
     * reject an otherwise valid use packet. Rebase both movement origins when
     * the server teleports; pending-teleport and distance checks remain intact.
     */
    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("RETURN"))
    private void endless$rebaseTeleport(double x, double y, double z, float yaw, float pitch,
                                      Set<RelativeMovement> relative, CallbackInfo ci) {
        if (EndlessLogicalHeights.isActive()) {
            resetPosition();
        }
    }

    @Redirect(method = {"handleUseItemOn", "handlePlayerAction"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int endless$interactionCeiling(Level level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMaxBuildHeight() : level.getMaxBuildHeight();
    }
}
