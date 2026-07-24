package com.nstut.endless.mixin;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkAccess.class)
public abstract class SectionInitMixin {

    /**
     * @author Endless
     * @reason Vanilla fills every null section slot with a new LevelChunkSection
     * (even pure air) in the ChunkAccess constructor. At extended heights this means
     * thousands of 4KB+ section objects per chunk — instant OOM during world gen.
     * Only create sections within the world generation range.
     */
    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;fillSectionArray(Lnet/minecraft/core/Registry;[Lnet/minecraft/world/level/chunk/LevelChunkSection;)V"),
        require = 0
    )
    private static void fillSectionArray(Registry<Biome> biomeRegistry, LevelChunkSection[] sections) {
        if (sections == null || sections.length == 0) return;
        int minSection = EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight() >> 4;
        int maxSection = (EndlessConfig.getInstance().getBuildHeight().getMaxBuildHeight() >> 4) - 1;
        int genMinSection = -4;   // y=-64
        int genMaxSection = 19;   // y=319 (vanilla terrain range)

        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null) continue;
            int absSection = minSection + i;
            // Only create sections within the vanilla terrain generation range
            // Sections outside this range stay null — they work as AIR when queried
            if (absSection >= genMinSection && absSection <= genMaxSection) {
                sections[i] = new LevelChunkSection(biomeRegistry);
            }
        }
    }
}
