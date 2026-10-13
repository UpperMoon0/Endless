package com.nstut.endless.testing;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
/** Native regressions for the production section solve, invalidation and immutable render copies. */
public final class NativeSectionLightChecks {
 private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException("Section light: "+message);}
 public static void run(ServerLevel level,BlockPos source,boolean denseSeam){
  var engine=level.getChunkSource().getLightEngine();
  var blockListener=engine.getLayerListener(LightLayer.BLOCK);
  var skyListener=engine.getLayerListener(LightLayer.SKY);
  require(blockListener==engine.getLayerListener(LightLayer.BLOCK),"block listener is not reused");
  require(skyListener==engine.getLayerListener(LightLayer.SKY),"sky listener is not reused");
  require(blockListener!=skyListener,"block and sky delegates were conflated");
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
  require(blockListener.getLightValue(target)>=expected,"reused listener lost sparse emission");
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
  if(!denseSeam)checkHeightInvalidation(level,source.offset(48,0,48));
  System.out.println("ENDLESS_SECTION_LIGHT_CACHE_PASS seam="+denseSeam+" source="+source);
 }

 private static void checkHeightInvalidation(ServerLevel level,BlockPos pos){
  level.getChunk(pos.getX()>>4,pos.getZ()>>4);
  var world=EndlessVerticalEngine.world(level);
  var types=Heightmap.Types.values();
  for(var type:types)require(world.getExtendedHeight(type,pos.getX(),pos.getZ())==Integer.MIN_VALUE,"height fixture is not empty");
  var neighbor=pos.east();
  for(var type:types)world.getExtendedHeight(type,neighbor.getX(),neighbor.getZ());
  level.setBlock(pos,Blocks.STONE.defaultBlockState(),3);
  for(var type:types){
   require(world.getExtendedHeight(type,pos.getX(),pos.getZ())==pos.getY()+1,"cached empty column hid stone: "+type);
   require(world.getExtendedHeight(type,neighbor.getX(),neighbor.getZ())==Integer.MIN_VALUE,"edit changed neighboring height: "+type);
  }
  level.setBlock(pos,Blocks.WATER.defaultBlockState(),3);
  for(var type:types){
   boolean floor=type==Heightmap.Types.OCEAN_FLOOR || type==Heightmap.Types.OCEAN_FLOOR_WG;
   require(world.getExtendedHeight(type,pos.getX(),pos.getZ())==(floor?Integer.MIN_VALUE:pos.getY()+1),"height type retained old solid predicate: "+type);
  }
  level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
  for(var type:types)require(world.getExtendedHeight(type,pos.getX(),pos.getZ())==Integer.MIN_VALUE,"removed sparse height remained cached: "+type);
  System.out.println("ENDLESS_HEIGHT_CACHE_INVALIDATION_PASS pos="+pos);
 }
}
