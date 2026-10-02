package com.nstut.endless.testing;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/** Pinned Create's own structures, recipes and outcome assertions, on normal world ticks. */
public final class LiveCreateGameTests {
    public static final String PROPERTY = "endless.liveCreateGameTests";
    public static final String PASS = "ENDLESS_CREATE_GAMETESTS_PASS";
    public static final String FAIL = "ENDLESS_CREATE_GAMETESTS_FAIL";
    // Origins straddle the seam with native structures, rather than just sitting above it.
    private static final int[] HEIGHTS = {64, -67, 317, -515, 509, -2051, 2045, -1_000_451, 1_000_445};
    private record Running(GameTestInfo info, BoundingBox bounds, int y) {}
    private static final List<Running> running = new ArrayList<>();
    private static List<TestFunction> functions;
    private static int next;
    private static int completed;
    private static int ticks;
    private static int placedAt;
    private static boolean done;
    private LiveCreateGameTests() {}

    public static void tick(MinecraftServer server) {
        String group = System.getProperty(PROPERTY, "");
        if (done || group.isEmpty() || server.getPlayerList().getPlayers().isEmpty()) return;
        ServerLevel level = server.overworld();
        try {
            ticks++;
            if (functions == null) {
                @SuppressWarnings("unchecked") Collection<TestFunction> nativeFunctions = (Collection<TestFunction>)
                    Class.forName("com.simibubi.create.infrastructure.gametest.CreateGameTests")
                        .getMethod("generateTests").invoke(null);
                functions = nativeFunctions.stream().filter(f -> name(f).startsWith("Test" + group + ".")).toList();
                require(new java.util.HashSet<>(functions.stream().map(LiveCreateGameTests::name).toList()).equals(new java.util.HashSet<>(LiveCreateGameTestCatalog.expected(group))),
                    "pinned native test catalog mismatch group=" + group);
                System.out.println("ENDLESS_CREATE_GAMETESTS_START group=" + group + " functions=" + functions.size() + " heights=" + HEIGHTS.length);
            }
            if (running.isEmpty()) {
                if (next == functions.size()) {
                    done = true;
                    require(completed == functions.size() * HEIGHTS.length, "missing native outcome");
                    System.out.println(PASS + " group=" + group + " cases=" + completed + " nativeAssertions=true naturalTicks=true");
                    return;
                }
                // Four templates at a time; all heights use the same horizontal ticking chunks.
                for (int slot = 0; slot < 4 && next < functions.size(); slot++, next++) {
                    TestFunction function = functions.get(next);
                    for (int y : HEIGHTS) running.add(place(level, function, new BlockPos(64 + slot % 2 * 64, y, 64 + slot / 2 * 64)));
                }
                placedAt = ticks;
                return;
            }
            // Let forced chunks and native neighbour/kinetic initialization settle before callbacks.
            if (ticks - placedAt < 30) return;
            for (Running test : List.copyOf(running)) {
                if (!chunksReady(level, test.bounds)) {
                    require(ticks - placedAt < 200, "fixture chunks never entered ticking state");
                    continue;
                }
                if (!test.info.hasStarted()) set(test.info, "startTick", level.getGameTime());
                Method tick = GameTestInfo.class.getDeclaredMethod("tickInternal"); tick.setAccessible(true); tick.invoke(test.info);
                if (!test.info.isDone()) continue;
                require(test.info.hasSucceeded(), "native outcome failed test=" + test.info.getTestName() + " originY=" + test.y + " cause=" + test.info.getError());
                completed++;
                System.out.println("ENDLESS_CREATE_GAMETEST_CASE_PASS test=" + test.info.getTestName() + " originY=" + test.y);
                clear(level, test.bounds);
                running.remove(test);
            }
        } catch (Throwable failure) {
            done = true;
            System.out.println(FAIL + " group=" + group + " error=" + failure);
            failure.printStackTrace();
            throw new IllegalStateException("Create native gameplay gate failed", failure);
        }
    }

    private static Running place(ServerLevel level, TestFunction function, BlockPos origin) throws Exception {
        GameTestInfo info = createInfo(function, level);
        for (Method m : GameTestInfo.class.getDeclaredMethods()) if (m.getName().equals("startExecution")) {
            m.setAccessible(true);
            if (m.getParameterCount() == 0) m.invoke(info); else m.invoke(info, 0);
        }
        var template = level.getStructureManager().get(ResourceLocation.tryParse(info.getStructureName())).orElseThrow();
        var size = template.getSize();
        BoundingBox bounds = new BoundingBox(origin.getX() - 3, origin.getY() - 3, origin.getZ() - 3,
            origin.getX() + size.getX() + 3, origin.getY() + size.getY() + 20, origin.getZ() + size.getZ() + 3);
        for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++)
            for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) level.setChunkForced(x, z, true);
        clear(level, bounds);
        // Native test floors permit items and passengers to land without falling out of the fixture.
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(bounds.minX(), origin.getY() - 1, bounds.minZ()),
                new BlockPos(bounds.maxX(), origin.getY() - 1, bounds.maxZ()))) level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        BlockPos structurePos = origin.below();
        level.setBlock(structurePos, Blocks.STRUCTURE_BLOCK.defaultBlockState(), 2);
        StructureBlockEntity structure = (StructureBlockEntity) level.getBlockEntity(structurePos);
        structure.setMode(StructureMode.LOAD);
        structure.setStructureName(ResourceLocation.tryParse(info.getStructureName()));
        structure.setStructurePos(new BlockPos(0, 1, 0));
        structure.setIgnoreEntities(false);
        structure.setRotation(Rotation.NONE);
        structure.setStructureSize(size);
        require(template.placeInWorld(level, origin, origin, new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings()
            .setIgnoreEntities(false), level.random, 2), "native template placement failed");
        set(info, "structureBlockPos", structurePos);
        set(info, "structureBlockEntity", structure);
        set(info, "startTick", level.getGameTime());
        return new Running(info, bounds, origin.getY());
    }

    private static GameTestInfo createInfo(TestFunction function, ServerLevel level) throws Exception {
        for (Constructor<?> constructor : GameTestInfo.class.getConstructors()) {
            if (constructor.getParameterCount() == 3) return (GameTestInfo) constructor.newInstance(function, Rotation.NONE, level);
            if (constructor.getParameterCount() == 4) {
                Object retry = constructor.getParameterTypes()[3].getMethod("noRetries").invoke(null);
                return (GameTestInfo) constructor.newInstance(function, Rotation.NONE, level, retry);
            }
        }
        throw new NoSuchMethodException("pinned GameTestInfo constructor");
    }
    private static String name(TestFunction function) {
        try { return (String) function.getClass().getMethod("testName").invoke(function); }
        catch (NoSuchMethodException legacy) {
            try { return (String) function.getClass().getMethod("getTestName").invoke(function); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static boolean chunksReady(ServerLevel level, BoundingBox box) {
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++)
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++)
                if (!level.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.asLong(x, z))) return false;
        return true;
    }
    private static void clear(ServerLevel level, BoundingBox box) {
        level.getEntitiesOfClass(Entity.class, AABB.of(box), e -> !(e instanceof Player)).forEach(Entity::discard);
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(box.minX(), box.minY(), box.minZ()), new BlockPos(box.maxX(), box.maxY(), box.maxZ())))
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        level.getBlockTicks().clearArea(box);
        level.clearBlockEvents(box);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
