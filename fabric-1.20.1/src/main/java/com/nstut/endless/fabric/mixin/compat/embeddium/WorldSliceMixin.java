package com.nstut.endless.fabric.mixin.compat.embeddium;

import com.nstut.endless.compat.EmbeddiumSections;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.world.WorldSlice", remap = false)
public abstract class WorldSliceMixin {
    @Redirect(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;", remap = true))
    private static LevelChunkSection[] endless$originSection(LevelChunk chunk, Level world, SectionPos origin, ClonedChunkSectionCache cache) {
        return new LevelChunkSection[] { EmbeddiumSections.get(world, chunk, origin.getY()) };
    }

    @Redirect(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionIndexFromSectionY(I)I", remap = true))
    private static int endless$originIndex(Level world, int sectionY) { return 0; }
}
