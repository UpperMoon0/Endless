package com.nstut.endless.testing;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
/** Native regressions for the production section solve, invalidation and immutable render copies. */
public final class NativeSectionLightChecks {
 private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException("Section light: "+message);}
 public static void run(ServerLevel level,BlockPos source,boolean denseSeam){
  var target=denseSeam?source.below(2):source.east(2);
  level.getChunk(source.getX()>>4,source.getZ()>>4);
  level.getChunk(target.getX()>>4,target.getZ()>>4);
  for(int dx=-2;dx<=2;dx++)for(int dy=-2;dy<=2;dy++)for(int dz=-2;dz<=2;dz++)
   level.setBlock(source.offset(dx,dy,dz),Blocks.AIR.defaultBlockState(),3);
  var world=EndlessVerticalEngine.world(level);
  int baseline=world.getBrightness(LightLayer.BLOCK,target);
  int expected=Math.max(13,baseline);
  level.setBlock(source,Blocks.GLOWSTONE.defaultBlockState(),3);
  require(world.getBrightness(LightLayer.BLOCK,target)==expected,"cross-section or dense/sparse propagation: expected="+expected+" actual="+world.getBrightness(LightLayer.BLOCK,target));
  var copy=world.copyRenderBlockLight(SectionPos.of(target));
  copy.set(target.getX()&15,target.getY()&15,target.getZ()&15,0);
  require(world.getBrightness(LightLayer.BLOCK,target)==expected,"render copy mutated shared cache");
  for(var direction:Direction.values())level.setBlock(source.relative(direction),Blocks.STONE.defaultBlockState(),3);
  require(world.getBrightness(LightLayer.BLOCK,target)<=baseline,"opaque enclosure did not invalidate cached light");
  for(var direction:Direction.values())level.setBlock(source.relative(direction),Blocks.AIR.defaultBlockState(),3);
  require(world.getBrightness(LightLayer.BLOCK,target)==expected,"removing enclosure did not relight");
  level.setBlock(source,Blocks.AIR.defaultBlockState(),3);
  require(world.getBrightness(LightLayer.BLOCK,target)==baseline,"removed emitter remained cached");
  var sky=world.copyRenderSkyLight(SectionPos.of(target));
  require(sky.get(target.getX()&15,target.getY()&15,target.getZ()&15)==world.getBrightness(LightLayer.SKY,target),"sky snapshot with unloaded dense halo");
  System.out.println("ENDLESS_SECTION_LIGHT_CACHE_PASS seam="+denseSeam+" source="+source);
 }
}
