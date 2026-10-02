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
    private record Running(GameTestInfo info, BoundingBox bounds, int y, BlockPos origin) {}
    private static final java.util.Map<GameTestInfo, Integer> populated = new java.util.HashMap<>();
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
                // Match GameTestServer.TEST_GAME_RULES; recipe, entity, fluid and machine ticks still run normally.
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING).set(0, server);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK).set(false, server);
                @SuppressWarnings("unchecked") Collection<TestFunction> nativeFunctions = (Collection<TestFunction>)
                    Class.forName("com.simibubi.create.infrastructure.gametest.CreateGameTests")
                        .getMethod("generateTests").invoke(null);
                functions = nativeFunctions.stream().filter(f -> name(f).startsWith("Test" + group + ".")).toList();
                require(functions.size() == LiveCreateGameTestCatalog.expected(group).size() && new java.util.HashSet<>(functions.stream().map(LiveCreateGameTests::name).toList()).equals(new java.util.HashSet<>(LiveCreateGameTestCatalog.expected(group))),
                    "pinned native test catalog mismatch group=" + group);
                System.out.println("ENDLESS_CREATE_GAMETESTS_START group=" + group + " functions=" + functions.size() + " heights=" + HEIGHTS.length);
            }
            if (running.isEmpty()) {
                if (next == functions.size() * HEIGHTS.length) {
                    done = true;
                    require(completed == functions.size() * HEIGHTS.length, "missing native outcome");
                    System.out.println(PASS + " group=" + group + " cases=" + completed + " nativeAssertions=true naturalTicks=true");
                    return;
                }
                // Isolate heights horizontally: falling items from one fixture must never enter another.
                int limit = seededRecipe(functions.get(next / HEIGHTS.length)) ? 1 : 4;
                for (int slot = 0; slot < limit && next < functions.size() * HEIGHTS.length; slot++, next++) {
                    TestFunction function = functions.get(next / HEIGHTS.length);
                    if (slot > 0 && seededRecipe(function)) break;
                    int y = HEIGHTS[next % HEIGHTS.length];
                    running.add(place(level, function, new BlockPos(64 + slot % 2 * 64, y, 64 + slot / 2 * 64)));
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
                if (!populated.containsKey(test.info)) {
                    level.getEntitiesOfClass(Entity.class, AABB.of(test.bounds), e -> !(e instanceof Player)).forEach(Entity::discard);
                    var template = level.getStructureManager().get(ResourceLocation.tryParse(test.info.getStructureName())).orElseThrow();
                    LiveCreateNbt.prepareGameTestTemplate(level, template);
                    require(template.placeInWorld(level, test.origin, test.origin, new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings()
                        .setIgnoreEntities(false), level.random, 2), "native template placement failed");
                    populated.put(test.info, ticks);
                    continue;
                }
                if (ticks - populated.get(test.info) < 30) continue;
                if (!test.info.hasStarted()) {
                    set(test.info, "startTick", level.getGameTime());
                    restoreLegacyThresholdSettings(level, test);
                    if (test.info.getTestName().equals("TestProcessing.precisionMechanismCrafting") || test.info.getTestName().equals("TestItems.fanProcessing")) {
                        // These native assertions require random recipe products.
                        // Precision mechanisms need both a successful item and a byproduct. A reproducible seed avoids an all-success
                        // random draw; the native recipe and result pool remain intact.
                        LiveCreateNbt.seedRecipe(level);
                    }
                }
                boolean starting = !test.info.hasStarted();
                Method tick = GameTestInfo.class.getDeclaredMethod("tickInternal"); tick.setAccessible(true); tick.invoke(test.info);
                if (starting && test.info.getTestName().equals("TestFluids.openPipes"))
                    for (var mob : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, AABB.of(test.bounds))) mob.setNoAi(true);
                if (!test.info.isDone()) continue;
                if (!test.info.hasSucceeded()) {
                    dump(level, test);
                    if (test.info.getError() != null) test.info.getError().printStackTrace();
                }
                require(test.info.hasSucceeded(), "native outcome failed test=" + test.info.getTestName() + " originY=" + test.y + " cause=" + test.info.getError());
                completed++;
                System.out.println("ENDLESS_CREATE_GAMETEST_CASE_PASS test=" + test.info.getTestName() + " originY=" + test.y);
                clear(level, test.bounds);
                running.remove(test);
                populated.remove(test.info);
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
        // A previous fixture can eject items beyond its vertical cleanup bounds. The cells
        // are reused sequentially, so remove those remnants before placing the next case.
        List<Entity> remnants = new ArrayList<>();
        for (Entity entity : level.getAllEntities())
            if (!(entity instanceof Player) && entity.getX() >= bounds.minX() && entity.getX() <= bounds.maxX() + 1
                && entity.getZ() >= bounds.minZ() && entity.getZ() <= bounds.maxZ() + 1) remnants.add(entity);
        remnants.forEach(Entity::discard);
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
        // Real light blocks supply the same crop-survival precondition at buried and open-air origins.
        for (int x = -3; x <= size.getX() + 2; x++) for (int z = -3; z <= size.getZ() + 2; z++)
            if (x == -3 || x == size.getX() + 2 || z == -3 || z == size.getZ() + 2)
                for (int y = 0; y <= size.getY(); y++) level.setBlock(origin.offset(x, y, z), Blocks.GLOWSTONE.defaultBlockState(), 2);
        set(info, "structureBlockPos", structurePos);
        set(info, "structureBlockEntity", structure);
        set(info, "startTick", level.getGameTime());
        return new Running(info, bounds, origin.getY(), origin);
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
    private static void dump(ServerLevel level, Running test) {
        for (Entity e : level.getAllEntities()) if (!(e instanceof Player) && Math.abs(e.getX()-test.origin.getX()) < 20 && Math.abs(e.getZ()-test.origin.getZ()) < 20)
            System.out.println("ENDLESS_CREATE_GAMETEST_ENTITY type=" + e.getType() + " pos=" + e.position() + " fire=" + e.isOnFire() + " sky=" + level.canSeeSky(e.blockPosition()) + " vehicle=" + e.getVehicle());
        for (Entity e : level.getAllEntities()) if (e instanceof net.minecraft.world.entity.item.ItemEntity item
                && Math.abs(e.getX() - test.origin.getX()) < 20 && Math.abs(e.getZ() - test.origin.getZ()) < 20)
            System.out.println("ENDLESS_CREATE_GAMETEST_ITEM pos=" + e.position() + " motion=" + e.getDeltaMovement() + " stack=" + item.getItem());
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(test.bounds.minX(), test.bounds.minY(), test.bounds.minZ()),
                new BlockPos(test.bounds.maxX(), test.bounds.maxY(), test.bounds.maxZ()))) {
            var entity = level.getBlockEntity(p);
            if (entity != null) System.out.println("ENDLESS_CREATE_GAMETEST_STATE test=" + test.info.getTestName()
                + " pos=" + p + " state=" + level.getBlockState(p) + " nbt=" + LiveCreateNbt.save(level, entity));
            if (entity != null && entity.getClass().getSimpleName().equals("EncasedFanBlockEntity")) try {
                Field air = entity.getClass().getDeclaredField("airCurrent"); air.setAccessible(true); Object current = air.get(entity);
                System.out.println("ENDLESS_CREATE_GAMETEST_FAN pos=" + p + " bounds=" + current.getClass().getField("bounds").get(current)
                    + " maxDistance=" + current.getClass().getField("maxDistance").get(current));
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            var state = level.getBlockState(p);
            if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock || state.is(Blocks.FARMLAND))
                System.out.println("ENDLESS_CREATE_GAMETEST_CROP pos=" + p + " state=" + state + " light=" + level.getRawBrightness(p, 0));
        }
    }
    private static String name(TestFunction function) {
        try { return (String) function.getClass().getMethod("testName").invoke(function); }
        catch (NoSuchMethodException legacy) {
            try { return (String) function.getClass().getMethod("getTestName").invoke(function); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static boolean seededRecipe(TestFunction function) { return name(function).equals("TestProcessing.precisionMechanismCrafting") || name(function).equals("TestItems.fanProcessing"); }
    static int normalizeLegacyThresholds(net.minecraft.nbt.CompoundTag template) {
        int changed = 0;
        for (net.minecraft.nbt.Tag entry : template.getList("blocks", 10)) {
            var data = ((net.minecraft.nbt.CompoundTag) entry).getCompound("nbt");
            if (!data.getString("id").equals("create:stockpile_switch") || !data.contains("OnAbove", 99) || data.contains("OnAboveAmount", 99)) continue;
            // Old templates store percentages. Current Create reads absolute amounts
            // and otherwise loads missing thresholds as zero, activating an empty chest.
            data.putFloat("EndlessFixtureOnPercent", data.getFloat("OnAbove"));
            data.putFloat("EndlessFixtureOffPercent", data.getFloat("OffBelow"));
            data.putInt("OnAboveAmount", 128); data.putInt("OffBelowAmount", 64);
            changed++;
        }
        return changed;
    }
    private static void restoreLegacyThresholdSettings(ServerLevel level, Running test) throws Exception {
        var template = level.getStructureManager().get(ResourceLocation.tryParse(test.info.getStructureName())).orElseThrow();
        var tag = template.save(new net.minecraft.nbt.CompoundTag());
        for (net.minecraft.nbt.Tag entry : tag.getList("blocks", 10)) {
            var block = (net.minecraft.nbt.CompoundTag) entry; var data = block.getCompound("nbt");
            if (!data.contains("EndlessFixtureOnPercent", 99)) continue;
            var relative = block.getList("pos", 3); BlockPos pos = test.origin.offset(relative.getInt(0), relative.getInt(1), relative.getInt(2));
            Object threshold = level.getBlockEntity(pos);
            int max = ((Number) threshold.getClass().getMethod("getMaxLevel").invoke(threshold)).intValue();
            int min = ((Number) threshold.getClass().getMethod("getMinLevel").invoke(threshold)).intValue();
            require(max > min, "legacy threshold fixture has no native observed range min=" + min + " max=" + max);
            float capacity = (float) ((long) max - min);
            threshold.getClass().getField("onWhenAbove").setInt(threshold, Math.round(min + capacity * data.getFloat("EndlessFixtureOnPercent")));
            threshold.getClass().getField("offWhenBelow").setInt(threshold, Math.round(min + capacity * data.getFloat("EndlessFixtureOffPercent")));
        }
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
