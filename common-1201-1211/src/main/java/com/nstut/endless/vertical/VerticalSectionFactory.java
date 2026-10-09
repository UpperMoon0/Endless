package com.nstut.endless.vertical;

import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/** Worker-local native section construction; no world admission or shared mutable templates. */
public final class VerticalSectionFactory {
 public static LevelChunkSection uniform(Registry<Biome> biomes,BlockState state) {
  // Native constructor recalculates nonempty/block/fluid counts from the palette.
  return new LevelChunkSection(
      new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,state,PalettedContainer.Strategy.SECTION_STATES),
      new PalettedContainer<>(biomes.asHolderIdMap(),biomes.getHolderOrThrow(Biomes.PLAINS),PalettedContainer.Strategy.SECTION_BIOMES));
 }
 private VerticalSectionFactory() {}
}
