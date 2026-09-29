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

/**
 * Opt-in characterization of Create issue #14, NOT a serializer fix or safety gate.
 * Real mounted assembly starts its payload at anchor + 1, so the unchanged 2048
 * payload cap admits local Y=+2048 before adding the minecart anchor. The pinned
 * serializers wrap that coordinate to -2048. Keep this limitation explicit until
 * #14 supplies safe refusal or exact-coordinate persistence and updates this test.
 */
public final class LiveCreateContraptionSerializationTest {
    public static final String CONTROL_MARKER = "ENDLESS_CREATE_CONTRAPTION_2047_CONTROL_PASS";
    public static final String LIMITATION_MARKER = "ENDLESS_CREATE_DEFAULT_CONTRAPTION_LIMITATION_CONFIRMED";
    private static final int DEFAULT_CAP = 2048;
    private static final BlockPos ANCHOR = new BlockPos(0, 1_001_000, 14);
    private static final BlockPos TOP = new BlockPos(0, DEFAULT_CAP, 0);
    private static final BlockPos WRAPPED_TOP = new BlockPos(0, -DEFAULT_CAP, 0);

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
            Map<BlockPos, BlockState> expectedWrapped = new HashMap<>(boundary.before);
            BlockState topState = expectedWrapped.remove(TOP);
            require(topState != null && topState.is(Blocks.SLIME_BLOCK), "real assembly did not capture the +2048 slime block");
            require(expectedWrapped.put(WRAPPED_TOP, topState) == null, "fixture already contains a -2048 block");
            require(!boundary.after.containsKey(TOP) && expectedWrapped.equals(boundary.after),
                "known default mounted serialization behavior changed; revisit #14 and replace this characterization"
                    + " with an exact-preservation or safe-refusal regression");
            require(((Number) readCap.invoke(maxBlocksMoved)).intValue() == DEFAULT_CAP,
                "assembly cap changed during the fixture");
        } finally {
            // Never disassemble the corrupted NBT back into the world. The carrier
            // is initialized but not spawned, and only the scratch blocks are removed.
            for (int y = DEFAULT_CAP; y >= -1; y--) {
                level.setBlock(ANCHOR.above(y), Blocks.AIR.defaultBlockState(), 18);
            }
        }
        System.out.println(CONTROL_MARKER + " payload=2047 captured=2048 exactBlocksAndStates=true");
        System.out.println(LIMITATION_MARKER
            + " issue=14 defaultCap=2048 configuredCapChanged=false assembled=true"
            + " beforeCount=2049 afterCount=2049 beforeTopY=2048 afterTopY=-2048"
            + " exactCoordinatesPreserved=false knownLimitation=true serializerFixed=false");
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
        CompoundTag saved = (CompoundTag) contraptionType.getMethod("writeNBT", boolean.class)
            .invoke(contraption, false);
        Object restored = contraptionType.getMethod("fromNBT", Level.class, CompoundTag.class, boolean.class)
            .invoke(null, level, saved, false);
        require(restored != null, "Contraption.fromNBT returned null");
        Map<BlockPos, BlockState> after = snapshot(getBlocks.invoke(restored));
        require(after.size() == payload + 1, "serialized contraption entry count changed");
        return new Snapshot(before, after);
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
