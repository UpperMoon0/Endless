package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.ExtendedPoiStorage;
import com.nstut.endless.vertical.MinecraftVerticalWorld;
import com.nstut.endless.vertical.VerticalPageLayout;
import com.nstut.endless.vertical.VerticalPagePos;
import com.nstut.endless.vertical.VerticalPageSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Lightweight representation-envelope smoke test; deliberately never scans logical height. */
public final class LiveFarEnvelopeServerTest {
    public static final String SYSTEM_PROPERTY = "endless.liveJoinFarEnvelopeTest";
    public static final String PASS_MARKER = "ENDLESS_FAR_ENVELOPE_PASS";
    public static final String FAIL_MARKER = "ENDLESS_FAR_ENVELOPE_FAIL";

    private static boolean prepared;
    private static boolean done;
    private static int ticks;

    private LiveFarEnvelopeServerTest() {}

    public static void tick(MinecraftServer server) {
        if (done || !Boolean.parseBoolean(System.getProperty(SYSTEM_PROPERTY, "false"))
            || server.getPlayerList().getPlayers().isEmpty()) return;
        ticks++;
        ServerLevel level = server.overworld();
        try {
            if (!prepared) {
                if (ticks < 8) return;
                require(EndlessHeights.getMinBuildHeight() == -8_000_000
                        && EndlessHeights.getMaxBuildHeight() == 8_000_000,
                    "far-envelope scenario did not apply the full configured representation range");
                prepare(level);
                prepared = true;
                return;
            }
            if (ticks < 14) return;
            verifyPoi(level, lowerPoi(), "lower");
            verifyPoi(level, upperPoi(), "upper");

            MinecraftVerticalWorld world = EndlessVerticalEngine.world(level);
            world.flushDirty();
            verifySnapshotRoundTrip(level, world, lowerBlock());
            verifySnapshotRoundTrip(level, world, upperBlock());
            EndlessVerticalEngine.close(level);

            require(level.getBlockState(lowerBlock()).is(Blocks.DIAMOND_BLOCK),
                "lower far-envelope block did not reload from sparse storage");
            require(level.getBlockState(upperBlock()).is(Blocks.EMERALD_BLOCK),
                "upper far-envelope block did not reload from sparse storage");
            verifyPoi(level, lowerPoi(), "lower reload");
            verifyPoi(level, upperPoi(), "upper reload");

            done = true;
            System.out.println(PASS_MARKER + " lowerY=" + lowerBlock().getY()
                + " upperY=" + upperBlock().getY());
        } catch (Throwable t) {
            done = true;
            System.out.println(FAIL_MARKER + " error=" + t);
            t.printStackTrace();
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    private static void prepare(ServerLevel level) {
        require(level.setBlock(lowerBlock(), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3), "lower far block write failed");
        require(level.setBlock(upperBlock(), Blocks.EMERALD_BLOCK.defaultBlockState(), 3), "upper far block write failed");
        require(level.setBlock(lowerPoi(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD), 3),
            "lower far POI write failed");
        require(level.setBlock(upperPoi(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD), 3),
            "upper far POI write failed");
    }

    private static void verifySnapshotRoundTrip(ServerLevel level, MinecraftVerticalWorld world, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int pageY = VerticalPageLayout.pageYForBlockY(pos.getY());
        VerticalPageSnapshot snapshot = world.snapshot(new VerticalPagePos(chunkX, pageY, chunkZ), true);
        require(snapshot != null, "missing far-envelope sparse page snapshot at " + pos);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        snapshot.write(buf);
        VerticalPageSnapshot decoded = VerticalPageSnapshot.read(buf);
        require(decoded.pos().equals(snapshot.pos()) && decoded.revision() == snapshot.revision(),
            "far-envelope page packet round-trip changed identity at " + pos);
        require(!buf.isReadable(), "far-envelope page packet left trailing bytes");
        require(!decoded.decode(level).isEmpty(), "far-envelope page packet decoded to an empty page");
    }

    private static void verifyPoi(ServerLevel level, BlockPos pos, String edge) {
        boolean found = level.getPoiManager().findClosest(
            holder -> holder.is(PoiTypes.HOME), pos, 1, PoiManager.Occupancy.ANY).filter(pos::equals).isPresent();
        require(found, edge + " far-envelope POI was not registered/searchable");
    }

    private static BlockPos lowerBlock() { return new BlockPos(0, -8_000_000, 8); }
    private static BlockPos upperBlock() { return new BlockPos(0, 7_999_999, 8); }
    private static BlockPos lowerPoi() { return new BlockPos(2, -7_999_984, 8); }
    private static BlockPos upperPoi() { return new BlockPos(2, 7_999_983, 8); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
