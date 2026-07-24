package com.nstut.endless.mixin;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(LevelReader.class)
public interface LevelReaderMixin {

    /**
     * @author Endless
     * @reason Replace default min build height with configured value
     */
    @Overwrite
    default int getMinBuildHeight() {
        return EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
    }

    /**
     * @author Endless
     * @reason Replace default height with configured value for section allocation
     */
    @Overwrite
    default int getHeight() {
        EndlessConfig.BuildHeightConfig config = EndlessConfig.getInstance().getBuildHeight();
        int rawHeight = config.getMaxBuildHeight() - config.getMinBuildHeight();
        return Math.min(rawHeight, EndlessConfig.MAX_SECTIONS * 16);
    }
}
