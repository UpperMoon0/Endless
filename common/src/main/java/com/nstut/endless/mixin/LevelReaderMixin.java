package com.nstut.endless.mixin;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin that modifies the LevelReader interface to use custom min build height values.
 */
@Mixin(LevelReader.class)
public interface LevelReaderMixin {

    /**
     * @author Endless
     * @reason Replace default min build height with configured value
     
    @Overwrite
    default int getMinBuildHeight() {
        return EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
    }
    */
}
