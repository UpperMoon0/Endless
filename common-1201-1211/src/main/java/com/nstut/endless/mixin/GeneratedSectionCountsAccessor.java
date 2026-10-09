package com.nstut.endless.mixin;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Only the worker-local uniform factory writes these fields; native sections are untouched. */
@Mixin(LevelChunkSection.class)
public interface GeneratedSectionCountsAccessor {
 @Accessor("nonEmptyBlockCount") void endless$nonempty(short count);
 @Accessor("tickingFluidCount") void endless$fluids(short count);
 @Accessor("nonEmptyBlockCount") short endless$nonempty();
 @Accessor("tickingBlockCount") short endless$blocks();
 @Accessor("tickingFluidCount") short endless$fluids();
}
