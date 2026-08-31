package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(LevelReader.class)
public interface LevelReaderMixin {

    /**
     * @author Endless
     * @reason Replace default min build height with the effective value
     */
    @Overwrite
    default int getMinBuildHeight() {
        return EndlessHeights.getMinBuildHeight();
    }

    /**
     * @author Endless
     * @reason Replace default height with the effective value for section allocation
     */
    @Overwrite
    default int getHeight() {
        return EndlessHeights.getHeight();
    }
}
