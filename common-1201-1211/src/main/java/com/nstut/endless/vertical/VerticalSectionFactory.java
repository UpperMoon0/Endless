package com.nstut.endless.vertical;

import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import com.nstut.endless.mixin.GeneratedSectionCountsAccessor;

/** Worker-local native section construction; no world admission or shared mutable templates. */
public final class VerticalSectionFactory {
 public static LevelChunkSection uniform(Registry<Biome> biomes,BlockState state) {
  var section=new LevelChunkSection(
      new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,state,PalettedContainer.Strategy.SECTION_STATES),
      new PalettedContainer<>(biomes.asHolderIdMap(),biomes.getHolderOrThrow(Biomes.PLAINS),PalettedContainer.Strategy.SECTION_BIOMES));
  // Match 4,096 setBlockState writes from an empty section. Native recalc instead
  // double-counts water as nonempty and counts only randomly ticking fluids.
  var counts=(GeneratedSectionCountsAccessor)section;
  // Keep each version's native definition of an empty/modded block and its
  // block-ticking count; remove only recalc's extra nonempty fluid contribution.
  counts.endless$nonempty((short)(counts.endless$nonempty()-(state.getFluidState().isEmpty()?0:4096)));
  counts.endless$fluids((short)(state.getFluidState().isEmpty()?0:4096));
  return section;
 }
 private VerticalSectionFactory() {}
}
