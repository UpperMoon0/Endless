package com.nstut.endless.mixin.accessor;

import com.nstut.endless.vertical.MinecraftVerticalWorld;
import com.nstut.endless.vertical.SparseVerticalColumn;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Internal accessor used by sparse runtime services without widening public API. */
@Mixin(value = MinecraftVerticalWorld.class, remap = false)
public interface MinecraftVerticalWorldAccessor {
    @Accessor("columns")
    Map<Long, SparseVerticalColumn<LevelChunkSection>> endless$getColumns();
}
