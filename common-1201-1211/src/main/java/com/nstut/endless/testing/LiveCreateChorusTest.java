package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;

public final class LiveCreateChorusTest {
    public static final String PASS = "ENDLESS_CREATE_CHORUS_TELEPORT_PASS";
    private LiveCreateChorusTest() {}
    public static void run(ServerLevel level) throws ReflectiveOperationException {
        Class<?> actionType = Class.forName("com.simibubi.create.content.equipment.potatoCannon.AllPotatoProjectileEntityHitActions$ChorusTeleport");
        Method execute = java.util.Arrays.stream(actionType.getMethods()).filter(m -> m.getName().equals("execute")).findFirst().orElseThrow();
        Object hitType = java.util.Arrays.stream(execute.getParameterTypes()[2].getEnumConstants()).filter(e -> e.toString().equals("ON_HIT")).findFirst().orElseThrow();
        Object action = actionType.getConstructor(double.class).newInstance(16d);
        for (int y : new int[]{-1_000_000, -65, 320, 1_000_000, EndlessHeights.getMinBuildHeight(), EndlessHeights.getMaxBuildHeight() - 1}) {
            RecordingCow cow = new RecordingCow(level);
            cow.setPos(4.5, y, 12.5); cow.getRandom().setSeed(1234);
            require(Boolean.FALSE.equals(execute.invoke(action, new ItemStack(Items.CHORUS_FRUIT), new EntityHitResult(cow), hitType)), "rejected chorus attempts unexpectedly succeeded");
            require(cow.attempts.size() == 16, "native chorus retry loop changed");
            for (double candidate : cow.attempts) {
                double min = Math.max(y - 8d, EndlessHeights.getMinBuildHeight());
                double max = Math.min(y + 7d, EndlessHeights.getMaxBuildHeight() - 1d);
                require(candidate >= min && candidate <= max, "chorus destination pulled to dense range at Y=" + y + " candidate=" + candidate);
            }
        }
        action = actionType.getConstructor(double.class).newInstance(1d);
        for (int y : new int[]{-1_000_000, 1_000_000}) {
            List<BlockPos> floor = new ArrayList<>();
            Cow cow = new Cow(EntityType.COW, level);
            try {
                for (int x = 3; x <= 5; x++) for (int z = 11; z <= 13; z++) {
                    BlockPos pos = new BlockPos(x, y - 1, z); floor.add(pos); level.setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
                }
                cow.setPos(4.5, y, 12.5); cow.getRandom().setSeed(1234);
                require(Boolean.TRUE.equals(execute.invoke(action, new ItemStack(Items.CHORUS_FRUIT), new EntityHitResult(cow), hitType)), "real sparse chorus teleport failed at Y=" + y);
                require(cow.getY() == y && Math.abs(cow.getX() - 4.5) <= .5 && Math.abs(cow.getZ() - 12.5) <= .5, "real chorus teleport shifted to dense core");
                for (BlockPos pos : floor) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
                long started = System.nanoTime();
                boolean fallback = cow.randomTeleport(4.5, y, 12.5, false);
                if (y < level.getMinBuildHeight()) require(!fallback, "unsupported negative sparse teleport succeeded");
                require(System.nanoTime() - started < 5_000_000_000L, "teleport searched empty logical height");
            } finally { for (BlockPos pos : floor) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18); cow.discard(); }
        }
        System.out.println(PASS + " nativeAction=true sampledDestinations=true logicalEdges=true realPositiveAndNegativeTeleport=true emptyGapBounded=true");
    }
    private static final class RecordingCow extends Cow {
        final List<Double> attempts = new ArrayList<>();
        RecordingCow(Level level) { super(EntityType.COW, level); }
        @Override public boolean randomTeleport(double x, double y, double z, boolean particles) { attempts.add(y); return false; }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
