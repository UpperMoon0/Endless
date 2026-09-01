package com.nstut.endless.mixin.compat;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.blay09.mods.waystones.block.WaystoneBlockBase", remap = false)
public abstract class WaystoneBlockBaseMixin {

    /**
     * @author Endless
     * @reason Waystones uses level.getHeight() which returns dimension height (384).
     * With expanded build limits, this prevents placement above the vanilla max.
     * Replace with the effective build range's max: the world-persisted,
     * possibly server-authoritative range, not the live file config (which
     * may have been shrunk after the world was created).
     */
    @Redirect(
        method = "getStateForPlacement",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getHeight()I"),
        require = 0
    )
    private int redirectGetHeight(Level level) {
        return EndlessHeights.getMaxBuildHeight();
    }
}
