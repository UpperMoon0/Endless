package com.nstut.endless.neoforge.mixin.compat.sodium;

import com.nstut.endless.neoforge.compat.EmbeddiumWindowBounds;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller", remap = false)
public abstract class OcclusionCullerMixin implements EmbeddiumWindowBounds {
    @Unique private boolean endless$bounded;
    @Unique private int endless$minSection;
    @Unique private int endless$maxSection;

    @Override
    public void endless$setWindowBounds(int min, int max) {
        endless$bounded = true;
        endless$minSection = min;
        endless$maxSection = max;
    }

    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinSection()I", remap = true))
    private int endless$minimum(Level level) { return endless$bounded ? endless$minSection : level.getMinSection(); }

    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxSection()I", remap = true))
    private int endless$maximum(Level level) { return endless$bounded ? endless$maxSection : level.getMaxSection(); }
}
