package com.nstut.endless.forge.mixin.compat.embeddium;

import com.nstut.endless.compat.EmbeddiumSections;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache", remap = false)
public abstract class ClonedChunkSectionCacheMixin {
    @Shadow @Final private Level world;

    @Inject(method = "clone", at = @At("HEAD"), cancellable = true)
    private void endless$clone(int x, int y, int z, CallbackInfoReturnable<ClonedChunkSection> cir) {
        LevelChunk chunk = world.getChunk(x, z);
        if (chunk == null) throw new IllegalStateException("Chunk is not loaded at: " + SectionPos.asLong(x, y, z));
        var section = EmbeddiumSections.get(world, chunk, y);
        var pos = SectionPos.of(x, y, z);
        EmbeddiumSections.prepareBlockEntities(chunk, section, pos);
        cir.setReturnValue(new ClonedChunkSection(world, chunk, section, pos));
    }
}
