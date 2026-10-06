package com.nstut.endless.forge.testing;

import com.google.gson.*;
import com.nstut.endless.forge.compat.EmbeddiumSections;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Development-only copy-world fixture using the same framebuffer path as Tidal Terror. */
@Mod.EventBusSubscriber(modid = "endless", value = Dist.CLIENT)
public final class EmbeddiumShaderPreview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final boolean ARMED = Boolean.getBoolean("endless.shaderPreview")
        || Files.isRegularFile(MC.gameDirectory.toPath().resolve("endless-preview-request.json"));
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long START = System.nanoTime();
    private static final int[] HEIGHTS = {-80, 320, 512, 1_000_000, -1_000_000};
    private static JsonObject request;
    private static Path out;
    private static JsonArray shots;
    private static final List<Map<String, Object>> records = new ArrayList<>();
    private static CompletableFuture<Void> future;
    private static boolean requested, prepared, done;
    private static int index, frames, denseWorkFrames;
    private static long denseWorkLastFrame, denseWorkAllocated, denseWorkBytes;
    private static final List<Double> denseWorkTimes = new ArrayList<>();
    private static long cameraAt;
    private static Object priorManager, priorCache;
    private static List<RenderSection> priorNodes = List.of();
    private static boolean lifecyclePending, lifecycleVerified;
    private static long lastDiagnostic;
    private static long lastFrame, allocatedAt;
    private static final List<Double> frameTimes = new ArrayList<>();
    private static final com.sun.management.ThreadMXBean ALLOCATIONS = allocationBean();

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!ARMED || done || event.phase != TickEvent.Phase.END) return;
        try {
            if (System.nanoTime() - START > 20L * 60 * 1_000_000_000L) throw new IllegalStateException("Preview timeout");
            if (!requested && MC.getOverlay() == null) {
                request = JsonParser.parseString(Files.readString(MC.gameDirectory.toPath().resolve("endless-preview-request.json"))).getAsJsonObject();
                out = Path.of(request.get("output").getAsString()).toAbsolutePath();
                Files.createDirectories(out);
                Files.deleteIfExists(out.resolve("failure.txt"));
                shots = request.getAsJsonArray("shots");
                // Wait for initial baked models before creating/loading a world.
                try { MC.getModelManager().requiresRender(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()); }
                catch (NullPointerException notReady) { return; }
                requested = true;
                MC.options.pauseOnLostFocus = false;
                MC.options.hideGui = true;
                MC.options.bobView().set(false);
                MC.options.enableVsync().set(false);
                MC.options.framerateLimit().set(60);
                MC.options.fov().set(65);
                MC.options.renderDistance().set(integer("renderDistance", 8));
                MC.options.simulationDistance().set(5);
                MC.options.cloudStatus().set(CloudStatus.OFF);
                MC.options.setCameraType(CameraType.FIRST_PERSON);
                if (MC.getWindow().isFullscreen()) MC.getWindow().toggleFullScreen();
                org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(MC.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_DECORATED, org.lwjgl.glfw.GLFW.GLFW_FALSE);
                MC.getWindow().setWindowed(integer("width", 1920), integer("height", 1080));
                org.lwjgl.glfw.GLFW.glfwHideWindow(MC.getWindow().getWindow());
                String world = request.get("world").getAsString();
                if (request.has("createWorld") && request.get("createWorld").getAsBoolean()) {
                    if (Files.exists(MC.gameDirectory.toPath().resolve("saves").resolve(world))) {
                        throw new IllegalStateException("Fresh fixture world already exists: " + world);
                    }
                    MC.createWorldOpenFlows().createFreshLevel(world,
                        new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(0x5EEDL, true, false), WorldPresets::createNormalWorldDimensions);
                } else MC.createWorldOpenFlows().loadLevel(null, world);
            }
            if (MC.level == null || MC.player == null || MC.getSingleplayerServer() == null) return;
            if (MC.screen != null) MC.setScreen(null);
            if (!prepared) {
                if (!com.nstut.endless.heights.EndlessLogicalHeights.isActive()
                    || !com.nstut.endless.heights.EndlessLogicalHeights.contains(-1_000_000)
                    || !com.nstut.endless.heights.EndlessLogicalHeights.contains(1_000_016)) {
                    throw new IllegalStateException("Fixture requires logical range containing +/-1,000,000; configure config/endless.json buildHeight");
                }
                prepared = true;
                var server = MC.getSingleplayerServer();
                future = CompletableFuture.runAsync(() -> {
                    for (var level : List.of(server.overworld(), server.getLevel(Level.NETHER))) {
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                    level.setDayTime(6000);
                    level.setWeatherParameters(0, 6000, false, false);
                    for (int y : HEIGHTS) {
                        level.getChunk(4, 4);
                        for (int x = 62; x <= 70; x++) for (int z = 62; z <= 70; z++) {
                            level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.SMOOTH_STONE.defaultBlockState());
                        }
                        level.setBlockAndUpdate(new BlockPos(66, y + 1, 66), Blocks.GLOWSTONE.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(67, y + 1, 66), Blocks.BLUE_STAINED_GLASS.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(66, y + 1, 67), Blocks.CHEST.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(65, y + 1, 66), Blocks.STONE_SLAB.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(66, y + 2, 66), Blocks.DIAMOND_BLOCK.defaultBlockState());
                        var vertical = EndlessVerticalEngine.world(level);
                        BlockPos emitter = new BlockPos(66, y + 1, 66);
                        BlockPos[] samples = {emitter, emitter.east(), emitter.north(), emitter.south(), emitter.west(), emitter.offset(4, 0, 0)};
                        int[] reference = Arrays.stream(samples).mapToInt(pos -> vertical.getBrightness(LightLayer.BLOCK, pos)).toArray();
                        int[] skyReference = Arrays.stream(samples).mapToInt(pos -> vertical.getBrightness(LightLayer.SKY, pos)).toArray();
                        var batch = vertical.copyRenderBlockLight(SectionPos.of(emitter));
                        var skyBatch = vertical.copyRenderSkyLight(SectionPos.of(emitter));
                        for (int i = 0; i < samples.length; i++) {
                            BlockPos pos = samples[i];
                            if (batch.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15) != reference[i]) {
                                throw new IllegalStateException("Batch lighting differs from independent point solve: " + pos);
                            }
                            if (skyBatch.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15) != skyReference[i]) {
                                throw new IllegalStateException("Batch skylight differs from independent point solve: " + pos);
                            }
                        }
                        System.out.println("ENDLESS_EMBEDDIUM_BATCH_LIGHT_PASS y=" + y + " dimension=" + level.dimension().location());
                    }
                    }
                }, server);
                return;
            }
            if (future != null && !future.isDone()) return;
            if (future != null) future.join();
            if (index >= shots.size()) {
                Files.writeString(out.resolve("capture-manifest.json"), JSON.toJson(records));
                System.out.println("ENDLESS_EMBEDDIUM_PREVIEW_PASS shots=" + records.size());
                done = true;
                MC.stop();
                return;
            }
            if (cameraAt == 0) {
                JsonObject shot = shots.get(index).getAsJsonObject();
                Vec3 eye = vector(shot.getAsJsonArray("eye"));
                Vec3 target = vector(shot.getAsJsonArray("target"));
                Vec3 delta = target.subtract(eye);
                float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                var server = MC.getSingleplayerServer();
                lifecycleVerified = false;
                var destination = shot.has("dimension") && shot.get("dimension").getAsString().equals("minecraft:the_nether")
                    ? server.getLevel(Level.NETHER) : server.overworld();
                if (ModList.get().isLoaded("embeddium") && (destination.dimension() != MC.level.dimension()
                    || shot.has("reload") && shot.get("reload").getAsBoolean())) {
                    priorManager = field(SodiumWorldRenderer.instance(), "renderSectionManager");
                    priorCache = field(priorManager, "sectionCache");
                    priorNodes = new ArrayList<>(((it.unimi.dsi.fastutil.longs.Long2ReferenceMap<RenderSection>)
                        field(priorManager, "sectionByPosition")).values());
                    lifecyclePending = true;
                }
                UUID uuid = MC.player.getUUID();
                future = CompletableFuture.runAsync(() -> {
                    var player = server.getPlayerList().getPlayer(uuid);
                    if (shot.has("edit") && shot.get("edit").getAsBoolean()) {
                        int y = shot.get("fixtureY").getAsInt();
                        destination.setBlockAndUpdate(new BlockPos(66, y + 1, 66), Blocks.REDSTONE_TORCH.defaultBlockState());
                        destination.setBlockAndUpdate(new BlockPos(66, y + 2, 66), Blocks.EMERALD_BLOCK.defaultBlockState());
                        destination.setBlockAndUpdate(new BlockPos(66, y + 1, 67), Blocks.AIR.defaultBlockState());
                    }
                    player.setGameMode(GameType.CREATIVE);
                    player.teleportTo(destination, eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
                    player.setNoGravity(true);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                }, server);
                MC.player.setPos(eye.x, eye.y - MC.player.getEyeHeight(), eye.z);
                MC.player.setYRot(yaw); MC.player.yRotO = yaw;
                MC.player.setXRot(pitch); MC.player.xRotO = pitch;
                MC.player.setDeltaMovement(Vec3.ZERO);
                MC.player.setNoGravity(true);
                MC.player.getAbilities().flying = true;
                if (shot.has("reload") && shot.get("reload").getAsBoolean()) MC.levelRenderer.allChanged();
                cameraAt = System.nanoTime(); frames = 0;
                denseWorkFrames = 0; denseWorkLastFrame = 0; denseWorkTimes.clear();
                lastFrame = 0; frameTimes.clear(); allocatedAt = allocatedBytes();
                System.out.println("ENDLESS_EMBEDDIUM_PREVIEW_CAMERA " + shot.get("name").getAsString() + " eye=" + eye);
            }
        } catch (Throwable error) { fail(error); }
    }

    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if (ARMED && !done && event.phase == TickEvent.Phase.END) {
            // Background clients may skip game ticks while a loading screen is
            // paused. Keep the fixture's startup/settlement state machine moving.
            tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            if (System.nanoTime() - lastDiagnostic > 10_000_000_000L) {
                lastDiagnostic = System.nanoTime();
                System.out.println("ENDLESS_PREVIEW_STATE level=" + (MC.level != null) + " player=" + (MC.player != null)
                    + " server=" + (MC.getSingleplayerServer() != null) + " paused=" + MC.isPaused()
                    + " prepared=" + prepared + " future=" + (future == null ? "none" : future.isDone()) + " index=" + index);
                if (out != null) {
                    try {
                        Map<String, Object> progress = new LinkedHashMap<>();
                        progress.put("index", index); progress.put("paused", MC.isPaused());
                        progress.put("frames", frames);
                        progress.put("camera", MC.gameRenderer.getMainCamera().getPosition().toString());
                        progress.put("screen", MC.screen == null ? "none" : MC.screen.getClass().getSimpleName());
                        progress.put("overlay", MC.getOverlay() == null ? "none" : MC.getOverlay().getClass().getSimpleName());
                        progress.put("futureDone", future == null || future.isDone());
                        if (MC.player != null) progress.put("player", MC.player.position().toString());
                        if (shots != null && index < shots.size() && MC.level != null) {
                            BlockPos target = BlockPos.containing(vector(shots.get(index).getAsJsonObject().getAsJsonArray("target")));
                            progress.put("target", target.toString());
                            progress.put("targetState", MC.level.getBlockState(target).toString());
                            progress.put("targetBuilt", sectionReady(target));
                        }
                        Files.writeString(out.resolve("progress.json"), JSON.toJson(progress));
                    } catch (Exception diagnosticFailure) { diagnosticFailure.printStackTrace(); }
                }
            }
        }
        if (!ARMED || done || event.phase != TickEvent.Phase.END
            || cameraAt == 0 || MC.level == null || MC.getOverlay() != null || MC.screen != null) return;
        long now = System.nanoTime();
        if (lastFrame != 0) frameTimes.add((now - lastFrame) / 1_000_000.0);
        lastFrame = now;
        if (++frames < integer("warmupFrames", 180) || now - cameraAt < integer("warmupSeconds", 10) * 1_000_000_000L || future != null && !future.isDone()) return;
        try {
            JsonObject shot = shots.get(index).getAsJsonObject();
            String expectedDimension = shot.has("dimension") ? shot.get("dimension").getAsString() : "minecraft:overworld";
            if (!MC.level.dimension().location().toString().equals(expectedDimension)) return;
            Vec3 eye = vector(shot.getAsJsonArray("eye"));
            if (MC.gameRenderer.getMainCamera().getPosition().distanceTo(eye) > .1) throw new IllegalStateException("Camera not settled");
            boolean shaders = false;
            String pack = "none";
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                shaders = (boolean) iris.getMethod("isPackInUseQuick").invoke(null);
                pack = String.valueOf(iris.getMethod("getCurrentPackName").invoke(null));
            } catch (ClassNotFoundException absent) { /* Embeddium-only baseline. */ }
            if (shaders != request.get("expectShaders").getAsBoolean()) throw new IllegalStateException("Unexpected shader state: " + shaders);
            if (shaders && !pack.contains("Complementary")) throw new IllegalStateException("Wrong shader pack: " + pack);
            BlockPos target = BlockPos.containing(vector(shot.getAsJsonArray("target")));
            boolean embeddium = ModList.get().isLoaded("embeddium");
            if (embeddium != request.get("expectEmbeddium").getAsBoolean()) throw new IllegalStateException("Wrong renderer backend");
            if (embeddium && shot.get("name").getAsString().equals("fixture-512") && denseWorkFrames < 120) {
                if (denseWorkFrames == 0) denseWorkAllocated = allocatedBytes();
                if (denseWorkLastFrame != 0) denseWorkTimes.add((now - denseWorkLastFrame) / 1_000_000.0);
                denseWorkLastFrame = now;
                denseEditFrame();
                denseWorkFrames++;
                if (denseWorkFrames == 120) {
                    long bytes = allocatedBytes();
                    denseWorkBytes = bytes < 0 || denseWorkAllocated < 0 ? -1 : bytes - denseWorkAllocated;
                }
                return; // Let actual terrain preparation/workers run between batches.
            }
            if (!sectionReady(target)) return;
            if (embeddium) {
                verifyWindow();
                if (lifecyclePending) {
                    Object manager = field(SodiumWorldRenderer.instance(), "renderSectionManager");
                    var oldManager = (me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager) priorManager;
                    // Whole-manager destruction shuts down workers and deletes
                    // regions; it does not mark every detached RenderSection disposed.
                    if (manager == priorManager || field(manager, "sectionCache") == priorCache
                        || oldManager.getBuilder().getTotalThreadCount() != 0
                        || priorNodes.stream().anyMatch(section -> section.getRegion() != null
                            && section.getRegion().getResources() != null)) {
                        throw new IllegalStateException("Dimension/reload retained old manager/cache, workers or GPU resources");
                    }
                    lifecyclePending = false; lifecycleVerified = true;
                    priorNodes = List.of(); priorManager = null; priorCache = null;
                }
                if (shot.has("fixtureY")) verifySnapshot(shot.get("fixtureY").getAsInt(), shot.has("edit") && shot.get("edit").getAsBoolean());
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("file", shot.get("name").getAsString() + ".png");
            record.put("dimension", MC.level.dimension().location().toString());
            record.put("managerLifecycle", lifecycleVerified ? "old manager/cache replaced; workers stopped; GPU resources released" : "not requested");
            record.put("camera", eye.toString()); record.put("shadersActive", shaders); record.put("shaderPack", pack);
            record.put("embeddium", embeddium); record.put("oculus", ModList.get().isLoaded("oculus"));
            if (shot.has("fixtureY")) record.put("fixtureY", shot.get("fixtureY").getAsInt());
            if (embeddium && shot.has("fixtureY") && !(shot.has("edit") && shot.get("edit").getAsBoolean())) {
                record.put("nativeRegressionMeasurements", EmbeddiumCompatibilityRegression.measurements());
                record.put("compatibilityRegressions", "native initial/dynamic sort, crack aliases/removal, complete mesh output, unload/cancel/late upload"
                    + (MC.level.dimensionType().hasSkyLight() ? ", dense roof edits" : ", no-skylight dimension skip")
                    + (MC.level.dimensionType().hasSkyLight() && shot.get("fixtureY").getAsInt() >= 320 ? ", distant sky page/removal and snapshot halo" : ""));
            }
            if (shot.get("name").getAsString().equals("fixture-512")) {
                var times = denseWorkTimes.stream().sorted().toList();
                record.put("denseEditWorkload", Map.of("frames", denseWorkFrames, "edits", denseWorkFrames * 64,
                    "dirtyNotifications", denseWorkFrames * (64 * 17 + 1),
                    "frameTimeMedianMs", percentile(times, .5), "frameTimeP95Ms", percentile(times, .95),
                    "renderThreadAllocatedBytes", denseWorkBytes));
            }
            record.put("captureMethod", "Screenshot.takeScreenshot(mainRenderTarget), RenderTick END, hidden GLFW window");
            record.put("capturedAtUtc", java.time.Instant.now().toString());
            var sortedTimes = frameTimes.stream().sorted().toList();
            record.put("frameTimeMedianMs", percentile(sortedTimes, .5));
            record.put("frameTimeP95Ms", percentile(sortedTimes, .95));
            long bytes = allocatedBytes();
            record.put("renderThreadAllocatedBytes", bytes < 0 || allocatedAt < 0 ? -1 : bytes - allocatedAt);
            record.put("measurementScope", "render-callback intervals include camera travel/warmup; allocation includes native probes; CPU-only probe costs reported separately");
            record.put("warmupFrames", frames);
            record.put("warmupSeconds", (System.nanoTime() - cameraAt) / 1_000_000_000.0);
            try (var image = Screenshot.takeScreenshot(MC.getMainRenderTarget())) {
                image.writeToFile(out.resolve((String) record.get("file")));
                Set<Integer> colors = new HashSet<>();
                for (int y = 0; y < image.getHeight(); y += 8) for (int x = 0; x < image.getWidth(); x += 8) {
                    colors.add(image.getPixelRGBA(x, y));
                }
                if (colors.size() < 16) throw new IllegalStateException("Framebuffer is blank or lacks fixture detail");
                record.put("sampledColors", colors.size());
                if (!shaders && shot.has("fixtureY")) {
                    int markerPixels = 0;
                    boolean edited = shot.has("edit") && shot.get("edit").getAsBoolean();
                    for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y += 2) {
                        for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x += 2) {
                            int pixel = image.getPixelRGBA(x, y);
                            int red = pixel & 255, green = (pixel >> 8) & 255, blue = (pixel >> 16) & 255;
                            if (green >= 40 && green > red * 1.3
                                && (edited ? green > blue * 1.3 : green > blue * 1.01 && blue > red * 1.3)) markerPixels++;
                        }
                    }
                    if (markerPixels == 0) {
                        image.writeToFile(out.resolve("failed-" + shot.get("name").getAsString() + ".png"));
                        throw new IllegalStateException("Native framebuffer omitted the colored fixture marker");
                    }
                    record.put("markerPixels", markerPixels);
                }
                image.writeToFile(out.resolve((String) record.get("file")));
                record.put("width", image.getWidth()); record.put("height", image.getHeight());
            }
            records.add(record);
            Files.writeString(out.resolve("capture-manifest.json"), JSON.toJson(records));
            System.out.println("ENDLESS_EMBEDDIUM_PREVIEW_CAPTURE " + record);
            index++; cameraAt = 0;
        } catch (Throwable error) { fail(error); }
    }

    private static int integer(String key, int fallback) {
        return request.has(key) ? request.get(key).getAsInt() : fallback;
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean allocations && allocations.isThreadAllocatedMemorySupported()) {
            allocations.setThreadAllocatedMemoryEnabled(true);
            return allocations;
        }
        return null;
    }

    private static void denseEditFrame() {
        BlockPos pos = new BlockPos(66, 300, 66);
        var chunk = MC.level.getChunk(pos);
        var original = chunk.getBlockState(pos);
        try {
            for (int edit = 0; edit < 64; edit++) {
                var next = (edit % 2 == 0 ? Blocks.STONE : Blocks.DIRT).defaultBlockState();
                var previous = chunk.setBlockState(pos, next, false);
                MC.level.setBlocksDirty(pos, previous, next);
                for (int repeat = 0; repeat < 16; repeat++)
                    SodiumWorldRenderer.instance().scheduleRebuildForChunk(4, 18, 4, false);
            }
        } finally {
            var previous = chunk.setBlockState(pos, original, false);
            MC.level.setBlocksDirty(pos, previous, original);
        }
    }

    static long allocatedBytes() {
        return ALLOCATIONS == null ? -1 : ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static double percentile(List<Double> times, double p) {
        return times.isEmpty() ? 0 : times.get(Math.min(times.size() - 1, (int) Math.floor(times.size() * p)));
    }

    private static void verifyWindow() throws Exception {
        Object manager = field(SodiumWorldRenderer.instance(), "renderSectionManager");
        var sections = (it.unimi.dsi.fastutil.longs.Long2ReferenceMap<RenderSection>) field(manager, "sectionByPosition");
        Map<Long, Integer> counts = new HashMap<>();
        for (RenderSection section : sections.values()) {
            long key = ChunkPos.asLong(section.getChunkX(), section.getChunkZ());
            if (counts.merge(key, 1, Integer::sum) > 32) throw new IllegalStateException("Unbounded vertical render column");
            if (section.isDisposed()) throw new IllegalStateException("Disposed section retained in render grid");
        }
        if (counts.isEmpty()) throw new IllegalStateException("No ready render columns");
    }

    private static boolean sectionReady(BlockPos target) throws Exception {
        if (!ModList.get().isLoaded("embeddium")) return MC.levelRenderer.hasRenderedAllChunks();
        Object manager = field(SodiumWorldRenderer.instance(), "renderSectionManager");
        if (manager == null) return false;
        var sections = (it.unimi.dsi.fastutil.longs.Long2ReferenceMap<RenderSection>) field(manager, "sectionByPosition");
        var render = sections.get(SectionPos.asLong(target.getX() >> 4, target.getY() >> 4, target.getZ() >> 4));
        // isBuilt alone also accepts an old/empty mesh while a page rebuild is
        // queued. Capture only after that section's current work has uploaded.
        return render != null && render.isBuilt() && render.getPendingUpdate() == null
            && render.getBuildCancellationToken() == null;
    }

    private static void verifySnapshot(int y, boolean edited) throws Exception {
        BlockPos emitter = new BlockPos(66, y + 1, 66);
        var emitterBlock = edited ? Blocks.REDSTONE_TORCH : Blocks.GLOWSTONE;
        if (!MC.level.getBlockState(emitter).is(emitterBlock)) throw new IllegalStateException("Sparse page not received");
        var sectionPos = SectionPos.of(emitter);
        var vertical = EndlessVerticalEngine.world(MC.level);
        BlockPos[] samples = {emitter, emitter.east(), emitter.north(), emitter.south(), emitter.west(), emitter.offset(4, 0, 0)};
        int[] expectedLight = new int[samples.length];
        for (int i = 0; i < samples.length; i++) expectedLight[i] = vertical.getBrightness(LightLayer.BLOCK, samples[i]);
        var cache = new ClonedChunkSectionCache(MC.level);
        var clone = cache.acquire(sectionPos.getX(), sectionPos.getY(), sectionPos.getZ());
        if (!clone.getBlockData().get(2, (y + 1) & 15, 2).is(emitterBlock)) throw new IllegalStateException("Sparse palette missing");
        if (!clone.getBlockData().get(2, (y + 2) & 15, 2).is(edited ? Blocks.EMERALD_BLOCK : Blocks.DIAMOND_BLOCK)) throw new IllegalStateException("Marker palette not refreshed");
        BlockPos chest = emitter.south();
        boolean hasChest = clone.getBlockEntityMap() != null
            && clone.getBlockEntityMap().values().stream().anyMatch(be -> be.getBlockPos().equals(chest));
        if (hasChest == edited) {
            throw new IllegalStateException(edited ? "Removed chest retained in render snapshot" : "Sparse chest missing from render snapshot");
        }
        if (edited && MC.level.getChunk(4, 4).getBlockEntities().containsKey(chest)) {
            throw new IllegalStateException("Removed chest retained in client chunk map");
        }
        var light = clone.getLightArray(LightLayer.BLOCK);
        for (int i = 0; i < samples.length; i++) {
            BlockPos pos = samples[i];
            int expected = expectedLight[i];
            if (light.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15) != expected) throw new IllegalStateException("Sparse light mismatch at " + pos);
        }
        int emission = edited ? 7 : 15;
        if (light.get(2, (y + 1) & 15, 2) != emission) throw new IllegalStateException("Emitter lost its light");
        if (light.get(3, (y + 1) & 15, 2) != emission - 1) throw new IllegalStateException("Glass lost adjacent light");
        if (EmbeddiumSections.get(MC.level, MC.level.getChunk(4, 4), sectionPos.getY()) == null) throw new IllegalStateException("Section route failed");
        if (!edited) EmbeddiumCompatibilityRegression.verify(y);
        System.out.println("ENDLESS_EMBEDDIUM_SNAPSHOT_PASS y=" + y + " palette/light/blockEntity");
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static Vec3 vector(JsonArray values) { return new Vec3(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble()); }
    private static void fail(Throwable error) {
        error.printStackTrace();
        System.out.println("ENDLESS_EMBEDDIUM_PREVIEW_FAIL " + error);
        try { if (out != null) Files.writeString(out.resolve("failure.txt"), error.toString()); } catch (Exception ignored) {}
        done = true; MC.stop();
    }
}
