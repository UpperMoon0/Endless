package com.nstut.endless.testing;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** Real default-cap assembly plus exact disk/spawn serialization on both supported Create versions. */
public final class LiveCreateContraptionSerializationTest {
    public static final String CONTROL_MARKER = "ENDLESS_CREATE_CONTRAPTION_2047_CONTROL_PASS";
    public static final String SAFETY_MARKER = "ENDLESS_CREATE_CONTRAPTION_EXACT_POSITION_PASS";
    private static final int DEFAULT_CAP = 2048;
    private static final BlockPos ANCHOR = new BlockPos(0, 1_001_000, 14);
    private static final BlockPos TOP = new BlockPos(0, DEFAULT_CAP, 0);

    private LiveCreateContraptionSerializationTest() {}

    public static void run(ServerLevel level) throws ReflectiveOperationException {
        Object config = Class.forName("com.simibubi.create.infrastructure.config.AllConfigs")
            .getMethod("server").invoke(null);
        Object kinetics = config.getClass().getField("kinetics").get(config);
        Object maxBlocksMoved = kinetics.getClass().getField("maxBlocksMoved").get(kinetics);
        Method readCap = maxBlocksMoved.getClass().getMethod("get");
        require(((Number) readCap.invoke(maxBlocksMoved)).intValue() == DEFAULT_CAP,
            "fixture requires unchanged default maxBlocksMoved=2048");

        // Stay in the already forced 0,0 chunk; do not touch another test's blocks.
        // Verify the entire scratch column before making the first write.
        for (int y = -1; y <= DEFAULT_CAP; y++) {
            require(level.getBlockState(ANCHOR.above(y)).isAir(), "contraption fixture column is not empty");
        }
        BlockState assembler = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:cart_assembler"))
            .defaultBlockState();
        require(!assembler.isAir(), "Create cart assembler is missing");
        try {
            require(level.setBlock(ANCHOR.below(), Blocks.STONE.defaultBlockState(), 18), "support write failed");
            require(level.setBlock(ANCHOR, assembler, 18), "assembler write failed");
            for (int y = 1; y < DEFAULT_CAP; y++) {
                require(level.setBlock(ANCHOR.above(y), Blocks.SLIME_BLOCK.defaultBlockState(), 18),
                    "slime payload write failed at " + y);
            }

            Snapshot control = assembleAndRoundTrip(level, DEFAULT_CAP - 1);
            require(control.before.equals(control.after), "2047-payload control did not preserve every local block/state");

            require(level.setBlock(ANCHOR.above(DEFAULT_CAP), Blocks.SLIME_BLOCK.defaultBlockState(), 18),
                "top payload write failed");
            Snapshot boundary = assembleAndRoundTrip(level, DEFAULT_CAP);
            require(boundary.before.containsKey(TOP), "real assembly did not capture local +2048");
            require(boundary.before.equals(boundary.after), "default mounted assembly shifted local coordinates");
            require(((Number) readCap.invoke(maxBlocksMoved)).intValue() == DEFAULT_CAP,
                "assembly cap changed during the fixture");
        } finally {
            // The carrier is initialized but not spawned; remove only the scratch column.
            for (int y = DEFAULT_CAP; y >= -1; y--) {
                level.setBlock(ANCHOR.above(y), Blocks.AIR.defaultBlockState(), 18);
            }
        }
        System.out.println(CONTROL_MARKER + " payload=2047 captured=2048 exactBlocksAndStates=true");
        System.out.println(SAFETY_MARKER
            + " issue=14 defaultCap=2048 configuredCapChanged=false captured=2049"
            + " exactCoordinatesPreserved=true diskAndSpawn=true serializerFixed=true");
    }

    private static Snapshot assembleAndRoundTrip(ServerLevel level, int payload) throws ReflectiveOperationException {
        Class<?> mountedType = Class.forName("com.simibubi.create.content.contraptions.mounted.MountedContraption");
        Class<?> contraptionType = Class.forName("com.simibubi.create.content.contraptions.Contraption");
        Object contraption = mountedType.getConstructor().newInstance();
        require(Boolean.TRUE.equals(mountedType.getMethod("assemble", Level.class, BlockPos.class)
            .invoke(contraption, level, ANCHOR)), "default mounted assembly was rejected for payload=" + payload);
        Method getBlocks = contraptionType.getMethod("getBlocks");
        Map<BlockPos, BlockState> before = snapshot(getBlocks.invoke(contraption));
        require(before.size() == payload + 1, "assembly did not include exactly the payload plus anchor");
        BlockState anchorState = before.get(BlockPos.ZERO);
        require(anchorState != null && anchorState.is(BuiltInRegistries.BLOCK.get(
            ResourceLocation.tryParse("create:minecart_anchor"))), "mounted anchor is missing from local origin");
        for (int y = 1; y <= payload; y++) {
            BlockState state = before.get(new BlockPos(0, y, 0));
            require(state != null && state.is(Blocks.SLIME_BLOCK), "assembled payload is not the exact local slime column");
        }

        // setContraption/onEntityCreated initializes mounted storage. Serializing
        // an uninitialized standalone instance is not Create's real lifecycle.
        Entity carrier = (Entity) Class.forName("com.simibubi.create.content.contraptions.OrientedContraptionEntity")
            .getMethod("create", Level.class, contraptionType, Direction.class)
            .invoke(null, level, contraption, Direction.NORTH);
        require(carrier != null, "mounted entity initialization failed");
        CompoundTag saved = LiveCreateNbt.writeContraption(level, contraption);
        Object restored = contraptionType.getMethod("fromNBT", Level.class, CompoundTag.class, boolean.class)
            .invoke(null, level, saved, false);
        require(restored != null, "Contraption.fromNBT returned null");
        Map<BlockPos, BlockState> after = snapshot(getBlocks.invoke(restored));
        require(after.size() == payload + 1, "serialized contraption entry count changed");
        require(before.equals(after), "disk NBT did not preserve assembled blocks");
        CompoundTag spawn = LiveCreateNbt.writeContraption(level, contraption, true);
        Object spawnRestored = contraptionType.getMethod("fromNBT", Level.class, CompoundTag.class, boolean.class)
            .invoke(null, level, spawn, true);
        require(before.equals(snapshot(getBlocks.invoke(spawnRestored))), "spawn NBT shifted assembled blocks");
        if (payload == DEFAULT_CAP - 1) {
            // Existing native saves remain readable when no exact extension is present.
            CompoundTag legacy = saved.copy();
            for (var entry : legacy.getCompound("Blocks").getList("BlockList", 10))
                ((CompoundTag) entry).remove("EndlessLocalPosition");
            Object old = contraptionType.getMethod("fromNBT", Level.class, CompoundTag.class, boolean.class)
                .invoke(null, level, legacy, false);
            require(before.equals(snapshot(getBlocks.invoke(old))), "native legacy contraption NBT stopped loading");
        }
        if (payload == DEFAULT_CAP) verifyExtendedMetadata(level, contraption);
        return new Snapshot(before, after);
    }

    @SuppressWarnings("unchecked")
    private static void verifyExtendedMetadata(ServerLevel level, Object contraption) throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.simibubi.create.content.contraptions.Contraption");
        Map<BlockPos, StructureBlockInfo> blocks = (Map<BlockPos, StructureBlockInfo>) type.getMethod("getBlocks").invoke(contraption);
        var updatesField = type.getDeclaredField("updateTags"); updatesField.setAccessible(true);
        Map<BlockPos, CompoundTag> updates = (Map<BlockPos, CompoundTag>) updatesField.get(contraption);
        blocks.clear(); updates.clear();
        // Imported/local positions outside the default payload size, including two simultaneous packed aliases.
        BlockPos[] positions = {new BlockPos(0, -8_000_000, 0), new BlockPos(0, 7_999_999, 0),
            new BlockPos(0, -2048, 0), new BlockPos(0, 2048, 0), new BlockPos(3, 4096, -2)};
        for (int i = 0; i < positions.length; i++) {
            CompoundTag data = new CompoundTag(); data.putString("FullPayload", "inventory-" + i);
            CompoundTag update = new CompoundTag();
            if (i != 0) update.putInt("ClientPayload", i); // Empty update tags must also retain their exact key.
            blocks.put(positions[i], new StructureBlockInfo(positions[i], Blocks.STONE.defaultBlockState(), data));
            updates.put(positions[i], update);
        }
        for (boolean spawn : new boolean[]{false, true}) {
            Object restored = type.getMethod("fromNBT", Level.class, CompoundTag.class, boolean.class)
                .invoke(null, level, LiveCreateNbt.writeContraption(level, contraption, spawn), spawn);
            Map<BlockPos, StructureBlockInfo> read = (Map<BlockPos, StructureBlockInfo>) type.getMethod("getBlocks").invoke(restored);
            Map<BlockPos, CompoundTag> readUpdates = (Map<BlockPos, CompoundTag>) updatesField.get(restored);
            require(read.keySet().equals(blocks.keySet()), "extended local keys collided in native serializer");
            for (BlockPos pos : positions) {
                require(read.get(pos).state().equals(blocks.get(pos).state()), "state detached from exact local key");
                require((spawn ? updates.get(pos) : blocks.get(pos).nbt()).equals(read.get(pos).nbt()),
                    "native disk/spawn payload detached from exact local key");
                if (!spawn) require(updates.get(pos).equals(readUpdates.get(pos)), "update tag detached from exact local key");
            }
        }
    }

    private static Map<BlockPos, BlockState> snapshot(Object raw) {
        require(raw instanceof Map<?, ?>, "Create getBlocks did not return a map");
        Map<BlockPos, BlockState> copy = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            require(entry.getKey() instanceof BlockPos && entry.getValue() instanceof StructureBlockInfo,
                "unexpected contraption block representation");
            BlockPos position = (BlockPos) entry.getKey();
            StructureBlockInfo block = (StructureBlockInfo) entry.getValue();
            require(position.equals(block.pos()), "contraption map key disagrees with block info");
            copy.put(position.immutable(), block.state());
        }
        return Map.copyOf(copy);
    }

    private record Snapshot(Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> after) {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
