package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.ExtendedPoiStorage;
import com.nstut.endless.vertical.MinecraftVerticalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/** Real-server sparse-boundary integration coverage used only by the live-join harness. */
public final class LiveHighYServerTest {
    public static final String SYSTEM_PROPERTY = "endless.liveJoinHighYTest";
    public static final String WAYSTONES_SYSTEM_PROPERTY = "endless.liveJoinWaystonesTest";
    public static final String PASS_MARKER = "ENDLESS_HIGH_Y_SERVER_PASS";
    public static final String FAIL_MARKER = "ENDLESS_HIGH_Y_SERVER_FAIL";
    public static final String COMMAND_PASS_MARKER = "ENDLESS_COMMAND_BOUNDS_PASS";
    public static final String WAYSTONES_PASS_MARKER = "ENDLESS_WAYSTONES_SPARSE_PASS";

    private static boolean prepared;
    private static boolean mechanicsVerified;
    private static boolean movedUpper;
    private static boolean done;
    private static int ticksWithPlayer;
    private static int preparedTick;
    private static int lowerArrivalTick;
    private static ArmorStand lowerStand;
    private static ArmorStand upperStand;

    private LiveHighYServerTest() {}

    /** Runtime values: never cache these before server-authoritative config sync. */
    public static int lowerY() {
        return EndlessHeights.getMinBuildHeight();
    }

    public static int upperY() {
        return EndlessHeights.getMaxBuildHeight() - 1;
    }

    public static double lowerPlayerY() {
        return lowerY() + 2.0D;
    }

    public static double upperPlayerY() {
        return upperY() - 2.0D;
    }

    public static BlockPos lowerTestPos() { return pos(0, lowerY()); }
    public static BlockPos lowerWaterPos() { return pos(1, lowerY()); }
    public static BlockPos lowerChestPos() { return pos(2, lowerY()); }
    public static BlockPos lowerPoiPos() { return pos(3, lowerY()); }
    public static BlockPos lowerPowerPos() { return pos(4, lowerY()); }
    public static BlockPos lowerLampPos() { return pos(5, lowerY()); }

    public static BlockPos upperTestPos() { return pos(0, upperY()); }
    public static BlockPos upperWaterPos() { return pos(1, upperY()); }
    public static BlockPos upperChestPos() { return pos(2, upperY()); }
    public static BlockPos upperPoiPos() { return pos(3, upperY()); }
    public static BlockPos upperPowerPos() { return pos(4, upperY()); }
    public static BlockPos upperLampPos() { return pos(5, upperY()); }

    /** Legal two-block Waystone occupies logical max-2 and max-1. */
    public static BlockPos upperWaystoneBasePos() { return pos(24, upperY() - 1); }
    public static BlockPos upperWaystoneTopPos() { return upperWaystoneBasePos().above(); }

    public static void tick(MinecraftServer server) {
        if (done || !Boolean.parseBoolean(System.getProperty(SYSTEM_PROPERTY, "false"))) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ticksWithPlayer++;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = player.serverLevel();

        try {
            if (!prepared) {
                if (ticksWithPlayer < 10) return;
                prepare(level, player);
                prepared = true;
                preparedTick = ticksWithPlayer;
                return;
            }

            if (!mechanicsVerified) {
                if (ticksWithPlayer < preparedTick + 6) return;
                // Vanilla ServerLevel queues POI registration onto the server executor
                // after a block-state change. Check it on a later tick instead of
                // racing the queued add from the same setBlock call stack.
                requirePoi(level, lowerPoiPos(), "lower-bound POI was not registered/searchable");
                requirePoi(level, upperPoiPos(), "upper-bound POI was not registered/searchable");
                verifyDelayedMechanics(level);
                flushReloadAndVerify(level);
                requireEntityAlive(lowerStand, "lower-bound entity did not survive normal ticking");
                requireEntityAlive(upperStand, "upper-bound entity did not survive normal ticking");
                mechanicsVerified = true;
                player.setNoGravity(true);
                player.teleportTo(0.5D, lowerPlayerY(), 0.5D);
                lowerArrivalTick = ticksWithPlayer;
                return;
            }

            // Give the real client enough time to receive and render the lower
            // sparse page before moving the same connection to the upper edge.
            if (!movedUpper && ticksWithPlayer >= lowerArrivalTick + 80) {
                player.teleportTo(0.5D, upperPlayerY(), 0.5D);
                movedUpper = true;
                return;
            }

            if (movedUpper && ticksWithPlayer >= lowerArrivalTick + 120) {
                done = true;
                System.out.println(PASS_MARKER
                    + " lowerY=" + lowerY()
                    + " upperY=" + upperY()
                    + " denseMin=" + EndlessHeights.getDenseMinBuildHeight()
                    + " denseMax=" + EndlessHeights.getDenseMaxBuildHeight()
                    + " upperHeight=" + level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0)
                    + " lowerLight=" + level.getBrightness(LightLayer.BLOCK, lowerTestPos())
                    + " upperLight=" + level.getBrightness(LightLayer.BLOCK, upperTestPos()));
            }
        } catch (Throwable t) {
            done = true;
            System.out.println(FAIL_MARKER + " error=" + t);
            t.printStackTrace();
            player.connection.disconnect(Component.literal("Endless sparse-boundary integration test failed: " + t));
        }
    }

    private static void prepare(ServerLevel level, ServerPlayer player) throws Exception {
        int min = lowerY();
        int max = EndlessHeights.getMaxBuildHeight();
        require(!level.isOutsideBuildHeight(min), "configured logical minimum must be buildable");
        require(!level.isOutsideBuildHeight(max - 1), "configured logical maximum - 1 must be buildable");
        require(level.isOutsideBuildHeight(min - 1), "configured logical minimum - 1 must be rejected");
        require(level.isOutsideBuildHeight(max), "configured logical maximum is exclusive and must be rejected");
        require(EndlessHeights.isOutsideDenseBuildHeight(min),
            "live sparse test minimum must actually be outside the dense core");
        require(EndlessHeights.isOutsideDenseBuildHeight(max - 1),
            "live sparse test maximum must actually be outside the dense core");

        verifySetBlockCommandBounds(level, player);
        prepareBoundary(level, player, false);
        prepareBoundary(level, player, true);
        prepareWaystonesIfRequested(level, player);

        lowerStand = spawnStand(level, 10.5D, min + 1.0D);
        upperStand = spawnStand(level, 10.5D, max - 2.0D);
    }

    /** Exercise the real vanilla /setblock parser/executor at all four logical edges. */
    private static void verifySetBlockCommandBounds(ServerLevel level, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        require(server != null, "server unavailable for command-boundary test");
        int min = lowerY();
        int max = EndlessHeights.getMaxBuildHeight();

        // BlockPosArgument#getLoadedBlockPos rejects an unloaded horizontal chunk
        // before it checks the world-height bound. Anchor the probes to the real
        // connected player's current chunk so this test measures height semantics,
        // not whether a fixed spawn-adjacent chunk happened to be loaded.
        ChunkPos commandChunk = player.chunkPosition();
        require(level.getChunkSource().getChunkNow(commandChunk.x, commandChunk.z) != null,
            "player chunk is not loaded for command-boundary test: " + commandChunk);
        int baseX = (commandChunk.x << 4) + 4;
        int z = (commandChunk.z << 4) + 4;

        BlockPos lowerInsidePos = new BlockPos(baseX, min, z);
        BlockPos upperInsidePos = new BlockPos(baseX + 1, max - 1, z);
        BlockPos lowerOutsidePos = new BlockPos(baseX + 2, min - 1, z);
        BlockPos upperOutsidePos = new BlockPos(baseX + 3, max, z);

        int lowerInside = runCommand(server, setBlockCommand(lowerInsidePos, "minecraft:diamond_block"));
        int upperInside = runCommand(server, setBlockCommand(upperInsidePos, "minecraft:emerald_block"));
        int lowerOutside = runCommand(server, setBlockCommand(lowerOutsidePos, "minecraft:gold_block"));
        int upperOutside = runCommand(server, setBlockCommand(upperOutsidePos, "minecraft:gold_block"));

        require(lowerInside > 0 && level.getBlockState(lowerInsidePos).is(Blocks.DIAMOND_BLOCK),
            "/setblock rejected configured logical minimum");
        require(upperInside > 0 && level.getBlockState(upperInsidePos).is(Blocks.EMERALD_BLOCK),
            "/setblock rejected configured logical maximum - 1");
        require(lowerOutside == 0 && level.getBlockState(lowerOutsidePos).isAir(),
            "/setblock escaped configured logical minimum");
        require(upperOutside == 0 && level.getBlockState(upperOutsidePos).isAir(),
            "/setblock escaped configured logical maximum");
        System.out.println(COMMAND_PASS_MARKER + " min=" + min + " max=" + max + " chunk=" + commandChunk);
    }

    private static String setBlockCommand(BlockPos pos, String blockId) {
        return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + blockId;
    }

    private static int runCommand(MinecraftServer server, String command) {
        return server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    private static void prepareBoundary(ServerLevel level, ServerPlayer player, boolean upper) {
        int y = upper ? upperY() : lowerY();
        BlockPos glowstone = upper ? upperTestPos() : lowerTestPos();
        BlockPos water = upper ? upperWaterPos() : lowerWaterPos();
        BlockPos chest = upper ? upperChestPos() : lowerChestPos();
        BlockPos poi = upper ? upperPoiPos() : lowerPoiPos();
        BlockPos power = upper ? upperPowerPos() : lowerPowerPos();
        BlockPos lamp = upper ? upperLampPos() : lowerLampPos();
        String edge = upper ? "upper" : "lower";

        require(level.setBlock(glowstone, Blocks.GLOWSTONE.defaultBlockState(), 3), edge + " glowstone write failed");
        require(level.setBlock(water, Blocks.WATER.defaultBlockState(), 3), edge + " water write failed");
        require(level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3), edge + " chest write failed");
        // Vanilla HOME POIs include only the HEAD half of beds; the default
        // BedBlock state is FOOT and therefore is intentionally not a POI.
        require(level.setBlock(poi,
            Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD), 3),
            edge + " bed/POI write failed");
        require(level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3), edge + " redstone source write failed");

        // Exercise the ordinary BlockItem placement path, not only direct world writes.
        placeFromSupport(player, power, Direction.EAST, Blocks.REDSTONE_LAMP);
        require(level.getBlockState(lamp).is(Blocks.REDSTONE_LAMP), edge + " lamp item placement failed");
        require(level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
            edge + " redstone neighbor signal did not reach placed lamp");

        // Prove the first coordinate outside either configured logical edge remains illegal.
        BlockPos boundarySupport = pos(8, y);
        require(level.setBlock(boundarySupport, Blocks.DEEPSLATE.defaultBlockState(), 3),
            edge + " boundary support write failed");
        BlockPos illegal = upper ? boundarySupport.above() : boundarySupport.below();
        placeFromSupport(player, boundarySupport, upper ? Direction.UP : Direction.DOWN, Blocks.STONE);
        require(level.getBlockState(illegal).isAir(), edge + " placement escaped logical build boundary at " + illegal);

        require(level.getBlockState(glowstone).is(Blocks.GLOWSTONE), edge + " block read mismatch");
        require(level.getFluidState(water).isSource(), edge + " fluid state missing");
        require(level.getBlockState(chest).is(Blocks.CHEST), edge + " chest state missing");
        require(level.getBlockEntity(chest) != null, edge + " block entity was not created");
        require(level.getBrightness(LightLayer.BLOCK, glowstone) >= 15, edge + " glowstone emission missing");
        BlockPos inwardLight = upper ? glowstone.below() : glowstone.above();
        require(level.getBrightness(LightLayer.BLOCK, inwardLight) > 0,
            edge + " block light did not propagate toward the logical interior");

        // Removing power schedules the vanilla lamp's four-tick turn-off.
        require(level.removeBlock(power, false), edge + " redstone source removal failed");
    }

    /**
     * Load and exercise the actual Waystones 1.20.1 classes when the CI
     * compatibility leg requests them. No compile-time Waystones dependency is
     * introduced into common production code.
     */
    private static void prepareWaystonesIfRequested(ServerLevel level, ServerPlayer player) throws Exception {
        if (!Boolean.parseBoolean(System.getProperty(WAYSTONES_SYSTEM_PROPERTY, "false"))) return;

        Class.forName("net.blay09.mods.waystones.block.WaystoneBlockBase");
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation("waystones", "waystone"));
        require(item instanceof BlockItem, "waystones:waystone item was not registered as a BlockItem");
        Block waystoneBlock = ((BlockItem) item).getBlock();

        BlockPos legalBase = upperWaystoneBasePos();
        BlockPos legalSupport = legalBase.below();
        require(level.setBlock(legalSupport, Blocks.DEEPSLATE.defaultBlockState(), 3),
            "could not create legal high-Y Waystone support");
        BlockState legalState = placementState(player, legalSupport, Direction.UP, item, waystoneBlock);
        require(legalState != null, "Waystones rejected legal sparse placement at " + legalBase);
        require(level.setBlock(legalBase, legalState, 3), "could not write legal high-Y Waystone base");
        waystoneBlock.setPlacedBy(level, legalBase, legalState, null, new ItemStack(item));
        require(level.getBlockState(legalBase).is(waystoneBlock), "high-Y Waystone base missing after placement");
        require(level.getBlockState(upperWaystoneTopPos()).is(waystoneBlock), "high-Y Waystone top missing after placement");
        require(level.getBlockEntity(legalBase) != null, "high-Y Waystone block entity missing");
        verifyWaystoneManager(level, legalBase);

        // A double-height Waystone starting at max-1 would need a block at max,
        // so WaystoneBlockBase#getStateForPlacement must reject it. This call is
        // the exact method whose Level#getHeight() invocation Endless redirects.
        BlockPos illegalTarget = pos(28, upperY());
        BlockPos illegalSupport = illegalTarget.below();
        require(level.setBlock(illegalSupport, Blocks.DEEPSLATE.defaultBlockState(), 3),
            "could not create illegal high-Y Waystone support");
        BlockState illegalState = placementState(player, illegalSupport, Direction.UP, item, waystoneBlock);
        require(illegalState == null, "Waystones allowed a two-block placement to escape configured logical max");
        require(!level.getBlockState(illegalTarget).is(waystoneBlock), "illegal top-edge Waystone was written");

        System.out.println(WAYSTONES_PASS_MARKER + " pos=" + legalBase + " max=" + EndlessHeights.getMaxBuildHeight());
    }

    private static BlockState placementState(
        ServerPlayer player,
        BlockPos support,
        Direction face,
        Item item,
        Block block
    ) {
        ItemStack stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), face, support, false);
        BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        return block.getStateForPlacement(context);
    }

    private static void verifyWaystoneManager(ServerLevel level, BlockPos pos) throws Exception {
        Class<?> managerClass = Class.forName("net.blay09.mods.waystones.core.WaystoneManager");
        Method getManager = managerClass.getMethod("get", MinecraftServer.class);
        Object manager = getManager.invoke(null, level.getServer());
        Method getAt = managerClass.getMethod("getWaystoneAt", BlockGetter.class, BlockPos.class);
        Object atResult = getAt.invoke(manager, level, pos);
        require(atResult instanceof Optional<?> && ((Optional<?>) atResult).isPresent(),
            "WaystoneManager could not resolve sparse Waystone at " + pos);

        Object waystone = ((Optional<?>) atResult).orElseThrow();
        BlockPos registeredPos = (BlockPos) waystone.getClass().getMethod("getPos").invoke(waystone);
        UUID uid = (UUID) waystone.getClass().getMethod("getWaystoneUid").invoke(waystone);
        require(pos.equals(registeredPos), "WaystoneManager stored wrong high-Y position: " + registeredPos);
        Object byId = managerClass.getMethod("getWaystoneById", UUID.class).invoke(manager, uid);
        require(byId instanceof Optional<?> && ((Optional<?>) byId).isPresent(),
            "WaystoneManager did not persist sparse Waystone UUID " + uid);
    }

    private static void verifyDelayedMechanics(ServerLevel level) {
        verifyLampScheduledTick(level, lowerPowerPos(), lowerLampPos(), "lower");
        verifyLampScheduledTick(level, upperPowerPos(), upperLampPos(), "upper");
    }

    private static void verifyLampScheduledTick(ServerLevel level, BlockPos power, BlockPos lamp, String edge) {
        require(level.getBlockState(lamp).is(Blocks.REDSTONE_LAMP), edge + " lamp disappeared before scheduled tick");
        require(!level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
            edge + " scheduled redstone-lamp tick did not run");
        require(level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3),
            edge + " redstone source restore failed");
        require(level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
            edge + " restored neighbor power did not relight lamp");
    }

    private static void flushReloadAndVerify(ServerLevel level) throws Exception {
        MinecraftVerticalWorld vertical = EndlessVerticalEngine.world(level);
        vertical.flushDirty();

        ChunkPos poiChunk = new ChunkPos(lowerPoiPos());
        ExtendedPoiStorage.flush(level, poiChunk);
        ExtendedPoiStorage.unload(level, poiChunk);
        EndlessVerticalEngine.close(level);

        verifyReloadedBoundary(level, false);
        verifyReloadedBoundary(level, true);
        requirePoi(level, lowerPoiPos(), "lower-bound POI did not survive flush + eviction + reload");
        requirePoi(level, upperPoiPos(), "upper-bound POI did not survive flush + eviction + reload");
        if (Boolean.parseBoolean(System.getProperty(WAYSTONES_SYSTEM_PROPERTY, "false"))) {
            verifyWaystoneManager(level, upperWaystoneBasePos());
        }

        require(level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0) == upperY() + 1,
            "sparse heightmap did not include the uppermost legal block");
    }

    private static void verifyReloadedBoundary(ServerLevel level, boolean upper) {
        BlockPos glowstone = upper ? upperTestPos() : lowerTestPos();
        BlockPos water = upper ? upperWaterPos() : lowerWaterPos();
        BlockPos chest = upper ? upperChestPos() : lowerChestPos();
        BlockPos lamp = upper ? upperLampPos() : lowerLampPos();
        String edge = upper ? "upper" : "lower";

        require(level.getBlockState(glowstone).is(Blocks.GLOWSTONE), edge + " block did not survive sparse reload");
        require(level.getFluidState(water).isSource(), edge + " fluid did not survive sparse reload");
        require(level.getBlockState(chest).is(Blocks.CHEST), edge + " chest state did not survive sparse reload");
        require(level.getBlockEntity(chest) != null, edge + " block entity disappeared after sparse reload");
        require(level.getBlockState(lamp).is(Blocks.REDSTONE_LAMP)
                && level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
            edge + " placed/redstone state did not survive sparse reload");
        require(level.getBrightness(LightLayer.BLOCK, glowstone) >= 15,
            edge + " light rebuild after reload lost glowstone emission");
        BlockPos inwardLight = upper ? glowstone.below() : glowstone.above();
        require(level.getBrightness(LightLayer.BLOCK, inwardLight) > 0,
            edge + " light rebuild after reload lost inward propagation");
    }

    private static void placeFromSupport(ServerPlayer player, BlockPos support, Direction face, ItemLike item) {
        ItemStack stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), face, support, false);
        stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    private static ArmorStand spawnStand(ServerLevel level, double x, double y) {
        ArmorStand stand = new ArmorStand(level, x, y, 0.5D);
        stand.setNoGravity(true);
        require(level.addFreshEntity(stand), "could not spawn armor stand at y=" + y);
        return stand;
    }

    private static void requireEntityAlive(ArmorStand stand, String message) {
        require(stand != null && stand.isAlive() && !stand.isRemoved(), message);
    }

    private static void requirePoi(ServerLevel level, BlockPos pos, String message) {
        require(level.getPoiManager().existsAtPosition(PoiTypes.HOME, pos), message + " (direct)");
        require(level.getPoiManager().findClosest(
                holder -> holder.is(PoiTypes.HOME), pos, 16, PoiManager.Occupancy.ANY)
            .filter(pos::equals).isPresent(), message + " (search)");
    }

    private static BlockPos pos(int x, int y) {
        return new BlockPos(x, y, 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
