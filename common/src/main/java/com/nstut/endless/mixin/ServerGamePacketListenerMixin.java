package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Real player use/break packets must use the logical ceiling, not dense array geometry. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Redirect(method = {"handleUseItemOn", "handlePlayerAction"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int endless$interactionCeiling(Level level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMaxBuildHeight() : level.getMaxBuildHeight();
    }
}
