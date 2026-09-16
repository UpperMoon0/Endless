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
     * @reason Waystones 1.20.1 uses Level#getHeight() as though it were an
     * absolute top Y while checking the two-block-tall placement. Endless keeps
     * that vanilla accessor bounded to the dense core, so Waystones must use the
     * configured logical max instead. The external Waystones method selector is
     * intentionally not remapped; the vanilla Level invocation is explicitly
     * remapped so the hook also resolves in production jars.
     */
    @Redirect(
        method = "getStateForPlacement",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getHeight()I",
            remap = true
        ),
        require = 1,
        remap = false
    )
    private int redirectGetHeight(Level level) {
        return EndlessHeights.getMaxBuildHeight();
    }
}
