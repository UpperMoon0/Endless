package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreateEjectorCacheKeyTest {
    private static BlockHitResult hit(BlockPos pos, Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
    }

    @Test void placementTracksAdjacentCellForEveryFace() {
        Object world = new Object();
        BlockPos pos = new BlockPos(4, 1_000_000, 7);
        for (Direction face : Direction.values()) {
            assertEquals(pos.relative(face), CreateEjectorCacheKey.of(world, hit(pos, face), false).position());
            assertEquals(pos, CreateEjectorCacheKey.of(world, hit(pos, face), true).position());
        }
    }

    @Test void packedAliasesInvalidateBothModes() {
        Object world = new Object();
        BlockPos pos = new BlockPos(4, 1_000_000, 7);
        for (boolean wrench : new boolean[]{false, true}) {
            var a = CreateEjectorCacheKey.of(world, hit(pos, Direction.UP), wrench);
            var b = CreateEjectorCacheKey.of(world, hit(pos.above(4096), Direction.UP), wrench);
            assertEquals(a.position().asLong(), b.position().asLong());
            assertFalse(a.sameContext(b));
            assertTrue(a.sameContext(CreateEjectorCacheKey.of(world, hit(pos, Direction.UP), wrench)));
        }
    }

    @Test void placementFaceChangeUsesEffectiveCoordinateNotRawHit() {
        Object world = new Object();
        BlockPos pos = new BlockPos(4, 1_000_000, 7);
        var a = CreateEjectorCacheKey.of(world, hit(pos, Direction.UP), false);
        var b = CreateEjectorCacheKey.of(world, hit(pos.above(4098), Direction.DOWN), false);
        assertNotEquals(pos.asLong(), pos.above(4098).asLong());
        assertEquals(a.position().asLong(), b.position().asLong());
        assertFalse(a.sameContext(b));
    }

    @Test void resetWorldAndModeInvalidateEvenAtSameEffectiveCell() {
        Object world = new Object();
        BlockPos pos = new BlockPos(4, 1_000_000, 7);
        var placement = CreateEjectorCacheKey.of(world, hit(pos, Direction.UP), false);
        assertFalse(placement.sameContext(null));
        assertFalse(placement.sameContext(CreateEjectorCacheKey.of(new Object(), hit(pos, Direction.UP), false)));
        assertFalse(placement.sameContext(CreateEjectorCacheKey.of(world, hit(pos.above(), Direction.UP), true)));
        assertNull(CreateEjectorCacheKey.of(null, hit(pos, Direction.UP), false));
        assertNull(CreateEjectorCacheKey.of(world, BlockHitResult.miss(Vec3.ZERO, Direction.UP, pos), false));
    }
}
