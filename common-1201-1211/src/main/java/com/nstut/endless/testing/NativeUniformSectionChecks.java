package com.nstut.endless.testing;
import com.nstut.endless.vertical.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
/** Native 1.20.1/1.21.1 palette, counter, isolation and codec regression. */
public final class NativeUniformSectionChecks {
 private static volatile net.minecraft.world.level.chunk.LevelChunkSection sink;
 private static void require(boolean ok,String message){if(!ok)throw new AssertionError("Uniform section: "+message);}
 public static void run(ServerLevel level) {
  var biomes=level.registryAccess().registryOrThrow(Registries.BIOME);
  for(var block:new net.minecraft.world.level.block.Block[]{Blocks.AIR,Blocks.WATER,Blocks.DEEPSLATE,Blocks.BRAIN_CORAL_BLOCK}) {
   var state=block.defaultBlockState();var section=VerticalSectionFactory.uniform(biomes,state);
   var reference=new net.minecraft.world.level.chunk.LevelChunkSection(biomes);
   for(int x=0;x<16;x++)for(int y=0;y<16;y++)for(int z=0;z<16;z++)reference.setBlockState(x,y,z,state,false);
   var actual=(com.nstut.endless.mixin.GeneratedSectionCountsAccessor)section;
   var expected=(com.nstut.endless.mixin.GeneratedSectionCountsAccessor)reference;
   require(actual.endless$nonempty()==expected.endless$nonempty(),"exact nonempty count");
   require(actual.endless$blocks()==expected.endless$blocks(),"exact block count");
   require(actual.endless$fluids()==expected.endless$fluids(),"exact fluid count");
   require(section.hasOnlyAir()==reference.hasOnlyAir(),"nonempty count");
   require(section.isRandomlyTickingBlocks()==reference.isRandomlyTickingBlocks(),"block ticking count");
   require(section.isRandomlyTickingFluids()==reference.isRandomlyTickingFluids(),"fluid ticking count");
   var restored=VerticalPageCodec.decodeSection(level,VerticalPageCodec.encodeSection(section));
   for(int x=0;x<16;x++)for(int y=0;y<16;y++)for(int z=0;z<16;z++)require(restored.getBlockState(x,y,z)==state,"codec value");
   var other=VerticalSectionFactory.uniform(biomes,state);section.setBlockState(3,4,5,Blocks.GOLD_BLOCK.defaultBlockState());
   require(other.getBlockState(3,4,5)==state,"shared mutable palette");
  }
  System.out.println("ENDLESS_UNIFORM_SECTION_PASS counters, codec, mutation isolation");
  // Serial ABBA microbenchmark of the API itself, independent of terrain noise.
  for(int i=0;i<64;i++){make(biomes,false);make(biomes,true);}
  for(boolean bulk:new boolean[]{false,true,true,false}) {
   long begin=System.nanoTime();for(int i=0;i<256;i++)make(biomes,bulk);
   System.out.println("ENDLESS_UNIFORM_SECTION_BENCH bulk="+bulk+" sections=256 ns="+(System.nanoTime()-begin));
  }
 }
 private static void make(net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes,boolean bulk) {
  var state=Blocks.WATER.defaultBlockState();
  var section=bulk?VerticalSectionFactory.uniform(biomes,state):new net.minecraft.world.level.chunk.LevelChunkSection(biomes);
  if(!bulk)for(int x=0;x<16;x++)for(int y=0;y<16;y++)for(int z=0;z<16;z++)section.setBlockState(x,y,z,state,false);
  sink=section;
 }
 private NativeUniformSectionChecks(){}
}
