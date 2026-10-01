package com.nstut.endless.testing;

import com.nstut.endless.compat.create.CreateDestructionPositions;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.lang.reflect.Field;
import java.util.List;
import java.util.SortedSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Native renderer + Create water-wheel extra-position hook, with simultaneous packed aliases. */
public final class LiveCreateDestructionTest {
    public static final String PASS = "ENDLESS_CREATE_DESTRUCTION_POSITIONS_PASS";
    private LiveCreateDestructionTest() {}
    @SuppressWarnings("unchecked")
    public static void run(Minecraft mc) throws ReflectiveOperationException {
        Field keysField = mc.levelRenderer.getClass().getDeclaredField("endless$crackPositions"); keysField.setAccessible(true);
        CreateDestructionPositions keys = (CreateDestructionPositions) keysField.get(mc.levelRenderer);
        Field mapField = mc.levelRenderer.getClass().getDeclaredField("destructionProgress"); mapField.setAccessible(true);
        Long2ObjectMap<SortedSet<BlockDestructionProgress>> progress = (Long2ObjectMap<SortedSet<BlockDestructionProgress>>) mapField.get(mc.levelRenderer);
        int baseline = keys.size();
        BlockState master = block("large_water_wheel").setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
        BlockState structural = block("water_wheel_structure").setValue(BlockStateProperties.FACING, Direction.DOWN);
        for (int y : new int[]{-2049, 2047, -1_000_448, 1_000_448}) {
            BlockPos a = new BlockPos(10, y, 10), b = a.above(4096);
            try {
                for (BlockPos pos : List.of(a, b)) {
                    mc.level.setBlock(pos, master, 18);
                    mc.level.setBlock(pos.above(), structural, 18);
                }
                mc.levelRenderer.destroyBlockProgress(90101, a.above(), 2);
                mc.levelRenderer.destroyBlockProgress(90102, b.above(), 7);
                for (BlockPos pos : List.of(a, a.above(), b, b.above())) {
                    SortedSet<BlockDestructionProgress> set = progress.get(keys.lookup(pos));
                    require(set != null && set.size() == 1, "Create crack position aliased or missing: " + pos);
                    require(set.first().getProgress() == (pos.getY() < b.getY() ? 2 : 7), "crack stage stolen by packed alias");
                    require(keys.position(keys.lookup(pos)).equals(pos), "renderer inverse lookup shifted crack position");
                }
                require(!progress.containsKey(a.asLong()) && !progress.containsKey(a.above().asLong()), "Create extras retained packed keys");
                // Removing/updating one breaker must preserve the other's main and extra positions.
                mc.levelRenderer.destroyBlockProgress(90101, a.above(), -1);
                require(progress.get(keys.lookup(b)).first().getProgress() == 7, "removing first crack erased alias");
                mc.levelRenderer.destroyBlockProgress(90102, b.above(), 4);
                require(progress.get(keys.lookup(b)).first().getProgress() == 4, "native crack update left stale extras");
                // Reuse the same breaker on a vanilla block: old Create extras must disappear.
                mc.level.setBlock(b.east(), Blocks.STONE.defaultBlockState(), 18);
                mc.levelRenderer.destroyBlockProgress(90102, b.east(), 1);
                require(keys.lookup(b) == Long.MIN_VALUE && keys.lookup(b.above()) == Long.MIN_VALUE, "retargeting retained structure crack");
                mc.levelRenderer.destroyBlockProgress(90102, b.east(), -1);
                require(keys.size() == baseline, "destruction coordinate keys leaked after cleanup");
            } finally {
                mc.levelRenderer.destroyBlockProgress(90101, a.above(), -1);
                mc.levelRenderer.destroyBlockProgress(90102, b.above(), -1);
                for (BlockPos pos : List.of(a, a.above(), b, b.above(), b.east())) mc.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
            }
        }
        System.out.println(PASS + " nativeRenderer=true nativeCreateExtras=true simultaneousAliases=true updates=true removal=true retarget=true renderCoordinates=true cleanup=true");
    }
    private static BlockState block(String id) { return BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:" + id)).defaultBlockState(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
