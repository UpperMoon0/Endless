package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
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

    /** @author Endless @reason Keep native horizontal chunk availability while admitting logical Y. */
    @Overwrite
    default boolean hasChunksAt(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        LevelReader self = (LevelReader) this;
        int min = EndlessLogicalHeights.isActive() ? EndlessHeights.getMinBuildHeight() : self.getMinBuildHeight();
        int max = EndlessLogicalHeights.isActive() ? EndlessHeights.getMaxBuildHeight() : self.getMaxBuildHeight();
        return toY >= min && fromY < max && self.hasChunksAt(fromX, fromZ, toX, toZ);
    }

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
