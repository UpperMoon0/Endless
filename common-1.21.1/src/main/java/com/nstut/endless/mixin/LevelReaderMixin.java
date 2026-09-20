package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Keeps LevelReader's inherited LevelHeightAccessor geometry anchored to the
 * bounded vanilla-compatible dense core. Logical buildability is handled by
 * the explicit LevelHeightAccessor build-bound checks and sparse routing; it
 * must never move vanilla section-array indices away from the dense core.
 */
@Mixin(LevelReader.class)
public interface LevelReaderMixin {

    /** @author Endless @reason Keep vanilla section-array origin on the persisted dense core. */
    @Overwrite
    default int getMinBuildHeight() {
        return EndlessHeights.getDenseMinBuildHeight();
    }

    /** @author Endless @reason Keep vanilla section-array height bounded to the persisted dense core. */
    @Overwrite
    default int getHeight() {
        return EndlessHeights.getHeight();
    }
}
