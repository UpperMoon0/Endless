package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.util.GoalUtils;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded real-world navigation regressions, reused at ordinary and million-scale heights. */
public final class LivePathfindingTest {
    private LivePathfindingTest() {}

    public static void verify(ServerLevel level) {
        int min = EndlessHeights.getMinBuildHeight();
        int max = EndlessHeights.getMaxBuildHeight();
        Set<Integer> floors = new LinkedHashSet<>();
        // Logical edges, both packed-Y edges, section/page seams, and dense control.
        for (int y : new int[]{min, max - 8, -2050, 2047, -514, 510, 80}) {
            if (y >= min && y + 7 < max) floors.add(y);
        }
        long started = System.nanoTime();
        for (int floor : floors) verifyAt(level, floor);
        System.out.println(LiveHighYServerTest.PATHFINDING_PASS_MARKER
            + " cases=" + floors.size() + " floors=" + floors
            + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
    }

    private static void verifyAt(ServerLevel level, int floor) {
        // Keep every case within the same small X/Z region. No logical-height loops.
        for (int x = 0; x <= 9; x++) {
            for (int z = 11; z <= 15; z++) {
                for (int dy = 1; dy <= 7; dy++) {
                    BlockPos pos = new BlockPos(x, floor + dy, z);
                    if (!level.getBlockState(pos).isAir()) level.removeBlock(pos, false);
                }
                level.setBlock(new BlockPos(x, floor, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        BlockPos start = new BlockPos(0, floor + 1, 12);
        BlockPos target = new BlockPos(7, floor + 1, 12);
        PathNavigationRegion region = new PathNavigationRegion(level, start.offset(-8, -8, -8), target.offset(8, 8, 8));
        require(region.getBlockState(start.below()).is(Blocks.STONE), "region lost floor at " + start);
        require(region.isOutsideBuildHeight(EndlessHeights.getMinBuildHeight() - 1)
            && region.isOutsideBuildHeight(EndlessHeights.getMaxBuildHeight()), "region lost logical bounds");

        Zombie walker = EntityType.ZOMBIE.create(level);
        Parrot flyer = EntityType.PARROT.create(level);
        require(walker != null && flyer != null, "could not create pathfinder mobs");
        try {
            PathfindingContext pathContext = new PathfindingContext(region, walker);
            require(WalkNodeEvaluator.getPathTypeStatic(pathContext, start.mutable()) == PathType.WALKABLE,
                "region did not classify supported air as WALKABLE at " + start);
            walker.setPos(0.5D, floor + 1.0D, 12.5D);
            walker.setNoAi(true);
            require(walker.getNavigation() instanceof GroundPathNavigation, "wrong walking navigator");
            // setPos does not simulate a landing. Retain the vanilla airborne guard,
            // then explicitly establish the grounded fixture before requesting a path.
            walker.setOnGround(false);
            require(walker.getNavigation().createPath(target, 0) == null, "airborne walking guard was bypassed");
            walker.setOnGround(true);
            require(!GoalUtils.isOutsideLimits(target, walker), "GoalUtils rejected " + target);
            require(GoalUtils.isOutsideLimits(new BlockPos(7, EndlessHeights.getMinBuildHeight() - 1, 12), walker),
                "GoalUtils accepted a target below logical minimum");
            requirePath(walker.getNavigation().createPath(target, 0), target, "flat walking");
            for (int illegalY : new int[]{EndlessHeights.getMinBuildHeight() - 1, EndlessHeights.getMaxBuildHeight()}) {
                BlockPos illegal = new BlockPos(7, illegalY, 12);
                require(walker.getNavigation().createPath(illegal, 0) == null,
                    "walking normalized an illegal target into the world: " + illegal);
                require(walker.getNavigation().createPath(Set.of(illegal), 0) == null,
                    "set navigation accepted illegal target: " + illegal);
            }

            // Step up across the chosen section/page/packed boundary, then step down.
            for (int x = 4; x <= 9; x++) {
                for (int z = 11; z <= 13; z++) {
                    level.setBlock(new BlockPos(x, floor + 1, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            requirePath(walker.getNavigation().createPath(target.above(), 0), target.above(), "step up");
            walker.setPos(7.5D, floor + 2.0D, 12.5D);
            requirePath(walker.getNavigation().createPath(start, 0), start, "step down");

            // A sealed destination must not be certified as reachable.
            for (int x = 0; x <= 2; x++) {
                for (int z = 11; z <= 13; z++) {
                    for (int dy = 1; dy <= 4; dy++) {
                        level.setBlock(new BlockPos(x, floor + dy, z), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
            BlockPos sealed = new BlockPos(1, floor + 1, 12);
            // Set overload deliberately avoids GroundPathNavigation's solid-target normalization.
            Path blocked = walker.getNavigation().createPath(Set.of(sealed), 0);
            require(blocked == null || !blocked.canReach(), "sealed destination reported reachable at " + sealed);

            if (EndlessHeights.isOutsideDenseBuildHeight(floor)) {
                // A remote empty column used to normalize all the way to the
                // dense surface. At million scale this must remain local.
                BlockPos empty = new BlockPos(7, floor + 6, 24);
                long started = System.nanoTime();
                Path emptyPath = walker.getNavigation().createPath(empty, 0);
                require(emptyPath == null || !emptyPath.canReach(), "empty column reported reachable at " + empty);
                require(System.nanoTime() - started < 5_000_000_000L,
                    "empty-column normalization exceeded five seconds at " + empty);
            }

            flyer.setPos(0.5D, floor + 6.0D, 14.5D);
            flyer.setNoAi(true);
            require(flyer.getNavigation() instanceof FlyingPathNavigation, "wrong flying navigator");
            BlockPos flyTarget = new BlockPos(7, floor + 6, 14);
            requirePath(flyer.getNavigation().createPath(flyTarget, 0), flyTarget, "flying");
            require(flyer.getNavigation().createPath(new BlockPos(7, EndlessHeights.getMaxBuildHeight(), 14), 0) == null,
                "flying accepted a target above the ceiling");
            System.out.println("ENDLESS_PATHFINDING_CASE_PASS floor=" + floor
                + " flat=true stepUp=true stepDown=true blocked=true flying=true");
        } finally {
            walker.discard();
            flyer.discard();
        }
    }

    private static void requirePath(Path path, BlockPos target, String kind) {
        require(path != null && path.canReach() && path.getNodeCount() > 1
            && path.getEndNode().asBlockPos().equals(target), kind + " did not reach exact target " + target
            + " path=" + path);
        for (int i = 0; i < path.getNodeCount(); i++) {
            require(!EndlessHeights.isOutsideBuildHeight(path.getNode(i).y), kind + " escaped logical bounds");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
