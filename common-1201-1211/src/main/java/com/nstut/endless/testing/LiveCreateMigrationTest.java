package com.nstut.endless.testing;

import java.lang.reflect.Field;
import com.nstut.endless.compat.create.CreateKineticIdData;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Opt-in regression: real connected Create machinery restored from legacy NBT. */
public final class LiveCreateMigrationTest {
    private LiveCreateMigrationTest() {}

    public static void run(ServerLevel level) {
        try {
            for (String order : new String[]{"follower-first", "root-first", "late-follower", "late-partial-follower"}) {
                verifyOrder(level, order);
            }
            verifyAliasedLegacyRoots(level);
            verifyUnavailableSource(level);
            verifyInterruptedSave(level);
            verifyUnresolvedAliases(level, false, 1_000_000);
            verifyUnresolvedAliases(level, true, 1_000_000);
            verifyUnresolvedAliases(level, false, 0);
            System.out.println("ENDLESS_CREATE_MIGRATION_PASS orders=follower-first,root-first,late-follower,late-partial-follower generators=2 realConsumer=true");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Create connected legacy-network regression failed", e);
        }
    }

    private static void verifyOrder(ServerLevel level, String order) throws ReflectiveOperationException {
        boolean followerFirst = !"root-first".equals(order);
        boolean lateFollower = order.startsWith("late-");
        boolean partialFollower = "late-partial-follower".equals(order);
        BlockPos root = new BlockPos(partialFollower ? 10 : lateFollower ? 6 : followerFirst ? 2 : 4, 1_000_000, 2);
        BlockPos[] positions = {root, root.south(), root.south(2), root.south(3)};
        BlockState[] states = {
            block("creative_motor").setValue(BlockStateProperties.FACING, Direction.SOUTH),
            block("shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.Z),
            block("mechanical_press").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
            block("creative_motor").setValue(BlockStateProperties.FACING, Direction.NORTH)
        };
        BlockEntity[] entities = new BlockEntity[positions.length];
        for (int i = 0; i < positions.length; i++) {
            require(level.setBlock(positions[i], states[i], 3), "migration fixture placement failed");
            entities[i] = level.getBlockEntity(positions[i]);
            require(entities[i] != null, "migration fixture missing real Create block entity");
        }
        // Opposite facing: -8 local RPM is +8 on the common shaft axis. The
        // second real generator stays overpowered by the 16-RPM primary motor.
        Object speedBehaviour = field(entities[3], "generatedSpeed");
        speedBehaviour.getClass().getMethod("setValue", int.class).invoke(speedBehaviour, -8);
        tickAll(entities, false, 5);
        assertConnected(entities);
        require(field(entities[0], "source") == null && field(entities[3], "source") != null,
            "fixture must contain a root and an overpowered generator");

        CompoundTag[] saved = new CompoundTag[entities.length];
        long legacy = root.asLong();
        for (int i = 0; i < entities.length; i++) {
            saved[i] = LiveCreateNbt.save(level, entities[i]);
            saved[i].getCompound("Network").putLong("Id", legacy);
        }
        // Retire runtime memberships before reconstructing fresh BEs. The saved
        // NBT is independent and retains the original Source and stress fields.
        Class<?> kinetic = Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
        for (BlockEntity entity : entities) kinetic.getMethod("setNetwork", Long.class).invoke(entity, new Object[]{null});
        for (BlockPos pos : positions) level.removeBlockEntity(pos);
        if (lateFollower) {
            // Hide the downstream blocks until the root has actually migrated;
            // merely changing tick order while every BE exists cannot exercise
            // late admission. No network method is called on the restored root.
            for (int i = 1; i < positions.length; i++) level.setBlock(positions[i], Blocks.AIR.defaultBlockState(), 2);
        }
        for (int i = 0; i < entities.length; i++) {
            if (partialFollower && i == 1) continue; // admitted before the root tick below
            if (lateFollower && i > 0) {
                level.setBlock(positions[i], states[i], 2);
                level.removeBlockEntity(positions[i]);
            }
            entities[i] = LiveCreateNbt.load(level, positions[i], states[i], saved[i]);
            require(entities[i] != null, "legacy NBT reconstruction failed");
            level.setBlockEntity(entities[i]);
            require(Long.valueOf(legacy).equals(field(entities[i], "network")), "legacy ID was not restored");
            if (lateFollower && i == 0) {
                if (partialFollower) {
                    level.setBlock(positions[1], states[1], 2);
                    level.removeBlockEntity(positions[1]);
                    entities[1] = LiveCreateNbt.load(level, positions[1], states[1], saved[1]);
                    level.setBlockEntity(entities[1]);
                }
                call(entities[0], "tick");
                call(entities[0], "tick");
                require(!Long.valueOf(legacy).equals(field(entities[0], "network")), "root did not migrate before followers loaded");
                Object partial = call(entities[0], "getOrCreateNetwork");
                CompoundTag totals = saved[0].getCompound("Network");
                require(((Number) call(partial, "getSize")).intValue() == totals.getInt("Size"),
                    "partial-load migration lost unloaded members");
                require(Math.abs(number(call(partial, "calculateStress")) - totals.getFloat("Stress")) < .01f,
                    "partial-load migration lost unloaded stress");
                require(Math.abs(number(call(partial, "calculateCapacity")) - totals.getFloat("Capacity")) < .01f,
                    "partial-load migration lost unloaded capacity");
                System.out.println("ENDLESS_CREATE_PARTIAL_MIGRATION_PASS members=" + totals.getInt("Size")
                    + " unloadedConsumer=true unloadedGenerator=true order=" + order);
            }
        }
        tickAll(entities, followerFirst, 260);
        Object network = assertConnected(entities);
        require(!Long.valueOf(legacy).equals(field(entities[0], "network")), "root did not migrate");
        Map<?, ?> sources = (Map<?, ?>) field(network, "sources");
        require(sources.size() == 2 && sources.containsKey(entities[0]) && sources.containsKey(entities[3]),
            "connected generators are missing from the shared source membership");

        // Exercise real Create stress propagation, not just numeric ID equality.
        var updateCapacity = network.getClass().getMethod("updateCapacityFor", kinetic, float.class);
        updateCapacity.invoke(network, entities[0], 0f);
        updateCapacity.invoke(network, entities[3], 0f);
        for (BlockEntity entity : entities) {
            require(Boolean.TRUE.equals(call(entity, "isOverStressed")) && number(call(entity, "getSpeed")) == 0,
                "zero capacity did not overstress every connected member");
        }
        for (int index : new int[]{0, 3}) {
            updateCapacity.invoke(network, entities[index], number(call(entities[index], "calculateAddedStressCapacity")));
        }
        assertConnected(entities);
        System.out.println("ENDLESS_CREATE_CONNECTED_MIGRATION_ORDER_PASS order="
            + order + " members=4 sources=2 stressRecovery=true");
    }

    private static void verifyAliasedLegacyRoots(ServerLevel level) throws ReflectiveOperationException {
        BlockPos firstPos = new BlockPos(12, 1_000_000, 2);
        BlockPos secondPos = firstPos.above(4096);
        require(firstPos.asLong() == secondPos.asLong(), "fixture roots must share a legacy packed ID");
        BlockState state = block("creative_motor").setValue(BlockStateProperties.FACING, Direction.SOUTH);
        BlockEntity[] roots = new BlockEntity[2];
        CompoundTag[] saved = new CompoundTag[2];
        BlockPos[] positions = {firstPos, secondPos};
        Class<?> kinetic = Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
        for (int i = 0; i < 2; i++) {
            level.setBlock(positions[i], state, 3);
            roots[i] = level.getBlockEntity(positions[i]);
            call(roots[i], "tick"); call(roots[i], "tick");
            saved[i] = LiveCreateNbt.save(level, roots[i]);
            saved[i].getCompound("Network").putLong("Id", firstPos.asLong());
            kinetic.getMethod("setNetwork", Long.class).invoke(roots[i], new Object[]{null});
            level.removeBlockEntity(positions[i]);
            roots[i] = LiveCreateNbt.load(level, positions[i], state, saved[i]);
            level.setBlockEntity(roots[i]);
        }
        // Reproduce a foreign member already admitted to the shared legacy map.
        Object legacyNet = call(roots[1], "getOrCreateNetwork");
        CompoundTag totals = saved[1].getCompound("Network");
        legacyNet.getClass().getMethod("initFromTE", float.class, float.class, int.class)
            .invoke(legacyNet, totals.getFloat("Capacity"), totals.getFloat("Stress"), totals.getInt("Size"));
        legacyNet.getClass().getMethod("addSilently", kinetic, float.class, float.class)
            .invoke(legacyNet, roots[1], number(field(roots[1], "lastCapacityProvided")), number(field(roots[1], "lastStressApplied")));
        call(roots[0], "initialize");
        require(Long.valueOf(firstPos.asLong()).equals(field(roots[1], "network")),
            "migration stole a disconnected aliased root");
        call(roots[1], "initialize");
        Object first = call(roots[0], "getOrCreateNetwork");
        Object second = call(roots[1], "getOrCreateNetwork");
        require(first != second && !field(roots[0], "network").equals(field(roots[1], "network")),
            "aliased legacy roots failed to separate");
        for (int i = 0; i < 2; i++) {
            Object net = call(roots[i], "getOrCreateNetwork");
            require(((Number) call(net, "getSize")).intValue() == 1
                && ((Map<?, ?>) field(net, "members")).size() == 1,
                "aliased root migration retained foreign membership");
            require(number(call(net, "calculateCapacity")) == saved[i].getCompound("Network").getFloat("Capacity"),
                "aliased root migration lost its saved capacity");
            kinetic.getMethod("setNetwork", Long.class).invoke(roots[i], new Object[]{null});
            level.setBlock(positions[i], Blocks.AIR.defaultBlockState(), 2);
        }
        System.out.println("ENDLESS_CREATE_ALIASED_LEGACY_ROOTS_PASS separated=true exactSourceChains=true");
    }

    private static void verifyUnavailableSource(ServerLevel level) throws ReflectiveOperationException {
        // The owner is a real modern generator. The restored shaft is in a loaded
        // adjacent chunk while its immediate source/root chunk was never requested.
        // Reserve a sequence whose OLD Y-gap encoding names an unloaded legal
        // root. Keep the live allocator's prior entries; never reset its state.
        Field cacheField = level.getDataStorage().getClass().getDeclaredField("cache");
        cacheField.setAccessible(true);
        Object allocator = ((Map<?, ?>) cacheField.get(level.getDataStorage())).get(CreateKineticIdData.DATA_NAME);
        require(allocator != null, "fixture needs the existing live allocator");
        Field next = allocator.getClass().getDeclaredField("nextSequence");
        next.setAccessible(true);
        long sequence = Math.max(1L << 17, (next.getLong(allocator) + 511L) & ~511L);
        next.setLong(allocator, sequence);
        BlockPos ownerPos = new BlockPos(8, 1_000_000, 2);
        BlockState motor = block("creative_motor").setValue(BlockStateProperties.FACING, Direction.SOUTH);
        level.setBlock(ownerPos, Blocks.AIR.defaultBlockState(), 18);
        level.setBlock(ownerPos, motor, 3);
        BlockEntity owner = level.getBlockEntity(ownerPos);
        call(owner, "tick");
        call(owner, "tick");
        Object ownerNet = call(owner, "getOrCreateNetwork");
        Long ownerId = (Long) field(owner, "network");
        // Old encoding: ((sequence >>> 5) << 12) | (2032 + (sequence & 31)).
        long oldCollisionId = ((sequence >>> 5) << 12) | (2032 + (sequence & 31));
        BlockPos root = BlockPos.of(oldCollisionId);
        BlockPos source = root.south(15);
        BlockPos followerPos = root.south(16);
        require(!level.isLoaded(source), "source-unavailable fixture source chunk already loaded");
        BlockState shaft = block("shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
        // Flag 16 suppresses shape-neighbour reads across the chunk boundary;
        // otherwise fixture placement itself loads the deliberately absent source.
        level.setBlock(followerPos, shaft, 18);
        require(!level.isLoaded(source), "fixture placement loaded its source chunk");
        BlockEntity initial = level.getBlockEntity(followerPos);
        CompoundTag saved = LiveCreateNbt.save(level, initial);
        CompoundTag sourceTag = new CompoundTag();
        sourceTag.putInt("X", source.getX()); sourceTag.putInt("Y", source.getY()); sourceTag.putInt("Z", source.getZ());
        saved.put("Source", sourceTag);
        saved.putFloat("Speed", 16f);
        CompoundTag legacy = new CompoundTag();
        legacy.putLong("Id", root.asLong()); legacy.putFloat("Capacity", 512f);
        legacy.putFloat("Stress", 64f); legacy.putInt("Size", 3);
        saved.put("Network", legacy);
        level.removeBlockEntity(followerPos);
        BlockEntity follower = LiveCreateNbt.load(level, followerPos, shaft, saved);
        level.setBlockEntity(follower);
        // Negative control: reproduce the old encoding with the real Create API.
        // Call the pinned native network API directly as the negative control;
        // normal initialize now isolates unresolved followers before admission.
        Class<?> kinetic = Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
        var setNetwork = kinetic.getMethod("setNetwork", Long.class);
        setNetwork.invoke(owner, Long.valueOf(oldCollisionId));
        Object raw = call(follower, "getOrCreateNetwork");
        raw.getClass().getMethod("addSilently", kinetic, float.class, float.class)
            .invoke(raw, follower, 0f, 0f);
        Object colliding = call(owner, "getOrCreateNetwork");
        require(call(follower, "getOrCreateNetwork") == colliding
            && ((Map<?, ?>) field(colliding, "members")).containsKey(follower),
            "old encoding negative control failed to reproduce wrong-network admission");
        require(!level.isLoaded(source), "negative control loaded its source chunk");
        setNetwork.invoke(follower, new Object[]{null});
        level.removeBlockEntity(followerPos);
        setNetwork.invoke(owner, ownerId);
        call(owner, "tick");
        ownerNet = call(owner, "getOrCreateNetwork");
        float ownerCapacity = number(call(ownerNet, "calculateCapacity"));
        int ownerSize = ((Number) call(ownerNet, "getSize")).intValue();
        follower = LiveCreateNbt.load(level, followerPos, shaft, saved);
        level.setBlockEntity(follower);
        call(follower, "initialize");
        require(!level.isLoaded(source), "legacy follower admission loaded its source chunk");
        Object followerNet = call(follower, "getOrCreateNetwork");
        require(followerNet != ownerNet, "source-unavailable legacy follower joined modern owner");
        require(CreateKineticIdData.isSyntheticId(ownerId) && ownerId.longValue() != oldCollisionId,
            "modern owner retained the old legal-position collision");
        require(!((Map<?, ?>) field(ownerNet, "members")).containsKey(follower), "modern membership contaminated");
        require(ownerSize == ((Number) call(ownerNet, "getSize")).intValue()
            && ownerCapacity == number(call(ownerNet, "calculateCapacity")), "modern owner totals changed");
        require(((Map<?, ?>) field(followerNet, "members")).containsKey(follower), "legacy follower not admitted");
        require(((Number) call(followerNet, "getSize")).intValue() == 3
            && number(call(followerNet, "calculateCapacity")) == 512f
            && number(call(followerNet, "calculateStress")) == 64f, "legacy unloaded totals changed");
        System.out.println("ENDLESS_CREATE_UNAVAILABLE_SOURCE_PASS sourceLoaded=false separated=true membership=true oldEncodingCounterexample=true");
        Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity")
            .getMethod("setNetwork", Long.class).invoke(follower, new Object[]{null});
        level.setBlock(followerPos, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(ownerPos, Blocks.AIR.defaultBlockState(), 3);
    }

    private static void verifyInterruptedSave(ServerLevel level) throws ReflectiveOperationException {
        BlockPos rootPos = new BlockPos(14, 1_000_000, 2);
        BlockPos followerPos = rootPos.south();
        BlockState motor = block("creative_motor").setValue(BlockStateProperties.FACING, Direction.SOUTH);
        BlockState shaft = block("shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
        level.setBlock(rootPos, motor, 3); level.setBlock(followerPos, shaft, 3);
        BlockEntity root = level.getBlockEntity(rootPos), follower = level.getBlockEntity(followerPos);
        tickAll(new BlockEntity[]{root, follower}, false, 5);
        Long stable = (Long) field(root, "network");
        CompoundTag rootNbt = LiveCreateNbt.save(level, root);
        CompoundTag followerNbt = LiveCreateNbt.save(level, follower);
        // Exact interrupted-save state: allocator and follower S saved, root L
        // stale. The follower snapshot also retains an unloaded consumer.
        followerNbt.getCompound("Network").putFloat("Stress", 64f);
        followerNbt.getCompound("Network").putInt("Size", 3);
        rootNbt.getCompound("Network").putLong("Id", rootPos.asLong());
        rootNbt.getCompound("Network").putFloat("Stress", 32f);
        rootNbt.getCompound("Network").putInt("Size", 2);
        level.getDataStorage().save();
        retire(root); retire(follower);
        level.removeBlockEntity(rootPos); level.removeBlockEntity(followerPos);
        follower = LiveCreateNbt.load(level, followerPos, shaft, followerNbt);
        level.setBlockEntity(follower);
        call(follower, "initialize");
        Object restored = call(follower, "getOrCreateNetwork");
        require(((Number) call(restored, "getSize")).intValue() == 3, "follower did not restore its snapshot");
        root = LiveCreateNbt.load(level, rootPos, motor, rootNbt);
        level.setBlockEntity(root);
        call(root, "initialize");
        require(stable.equals(field(root, "network")) && call(root, "getOrCreateNetwork") == restored,
            "interrupted-save root failed to reuse its allocator-owned target");
        require(((Map<?, ?>) field(restored, "members")).size() == 2
            && ((Number) call(restored, "getSize")).intValue() == 3
            && number(call(restored, "calculateStress")) == 64f,
            "root reseeded or doubled the already restored target aggregate");
        require(number(call(restored, "calculateCapacity")) == followerNbt.getCompound("Network").getFloat("Capacity"),
            "interrupted-save migration lost target capacity");
        call(root, "initialize"); call(follower, "initialize");
        require(((Number) call(restored, "getSize")).intValue() == 3, "repeated admission consumed unloaded members twice");
        System.out.println("ENDLESS_CREATE_INTERRUPTED_SAVE_PASS followerFirst=true targetReused=true aggregatePreserved=true");
        retire(root); retire(follower);
        level.setBlock(rootPos, Blocks.AIR.defaultBlockState(), 18);
        level.setBlock(followerPos, Blocks.AIR.defaultBlockState(), 18);
    }

    private static void verifyUnresolvedAliases(ServerLevel level, boolean reverse, int baseY) throws ReflectiveOperationException {
        BlockState motor = block("creative_motor").setValue(BlockStateProperties.FACING, Direction.SOUTH);
        BlockState shaft = block("shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
        BlockPos templatePos = new BlockPos(14, 1_000_000, 2);
        level.setBlock(templatePos, motor, 18);
        BlockEntity template = level.getBlockEntity(templatePos);
        call(template, "tick"); call(template, "tick");
        CompoundTag motorNbt = LiveCreateNbt.save(level, template);
        float baseCapacity = motorNbt.getCompound("Network").getFloat("Capacity");
        require(baseCapacity > 0, "unresolved-alias fixture has no generator capacity");
        retire(template); level.setBlock(templatePos, Blocks.AIR.defaultBlockState(), 18);
        int z = baseY == 0 ? 49167 : reverse ? 32783 : 16399;
        BlockPos[] roots = {new BlockPos(0, baseY, z), new BlockPos(0, baseY + 4096, z)};
        BlockPos[] positions = {roots[0].south(), roots[1].south()};
        require(roots[0].asLong() == roots[1].asLong(), "unresolved roots must alias");
        require(!level.isLoaded(roots[0]), "unresolved source chunk already loaded");
        BlockEntity[] followers = new BlockEntity[2];
        CompoundTag[] pending = new CompoundTag[2];
        for (int step = 0; step < 2; step++) {
            int i = reverse ? 1 - step : step;
            level.setBlock(positions[i], shaft, 18);
            CompoundTag tag = LiveCreateNbt.save(level, level.getBlockEntity(positions[i]));
            CompoundTag source = new CompoundTag();
            source.putInt("X", roots[i].getX()); source.putInt("Y", roots[i].getY()); source.putInt("Z", roots[i].getZ());
            tag.put("Source", source); tag.putFloat("Speed", 16f);
            CompoundTag totals = new CompoundTag();
            totals.putLong("Id", roots[i].asLong()); totals.putFloat("Capacity", baseCapacity + i * 512f);
            totals.putFloat("Stress", 64f + i * 64f); totals.putInt("Size", 3 + i * 2);
            tag.put("Network", totals);
            level.removeBlockEntity(positions[i]);
            followers[i] = LiveCreateNbt.load(level, positions[i], shaft, tag);
            level.setBlockEntity(followers[i]);
            call(followers[i], "tick"); call(followers[i], "tick");
            require(!level.isLoaded(roots[i]), "unresolved admission loaded the source chunk");
        }
        assertIsolatedAliases(followers, baseCapacity);
        // Persist and reconstruct both provisional BEs while sources stay absent.
        for (int i = 0; i < 2; i++) {
            pending[i] = LiveCreateNbt.save(level, followers[i]);
            require(pending[i].getLong("EndlessLegacyNetworkId") == roots[i].asLong(), "pending legacy ID not persisted");
            retire(followers[i]); level.removeBlockEntity(positions[i]);
            followers[i] = LiveCreateNbt.load(level, positions[i], shaft, pending[i]);
            level.setBlockEntity(followers[i]);
            call(followers[i], "initialize"); call(followers[i], "tick");
        }
        assertIsolatedAliases(followers, baseCapacity);
        require(!level.isLoaded(roots[0]), "pending restart loaded source chunks");
        if (baseY == 0) {
            // A loaded intermediate with zero theoretical speed must still use
            // native validation cleanup, even if its own upstream chunk is absent.
            BlockPos absent = roots[0].north(16);
            require(!level.isLoaded(absent), "stopped-source ancestor unexpectedly loaded");
            level.setBlock(roots[0], shaft, 18); level.removeBlockEntity(roots[0]);
            CompoundTag stoppedTag = pending[0].copy();
            CompoundTag upstream = new CompoundTag();
            upstream.putInt("X", absent.getX()); upstream.putInt("Y", absent.getY()); upstream.putInt("Z", absent.getZ());
            stoppedTag.put("Source", upstream); stoppedTag.putFloat("Speed", 0f);
            stoppedTag.getCompound("Network").putLong("Id", roots[0].asLong());
            BlockEntity stopped = LiveCreateNbt.load(level, roots[0], shaft, stoppedTag);
            level.setBlockEntity(stopped);
            tickAll(new BlockEntity[]{followers[0]}, false, 260);
            require(field(followers[0], "source") == null && field(followers[0], "network") == null
                && number(call(followers[0], "getSpeed")) == 0f,
                "pending identity blocked native stopped-source cleanup");
            require(!LiveCreateNbt.save(level, followers[0]).contains("EndlessLegacyNetworkId"), "stopped-source marker retained");
            retire(stopped); level.removeBlockEntity(roots[0]);
            level.setBlock(roots[0], Blocks.AIR.defaultBlockState(), 18);
            level.removeBlockEntity(positions[0]);
            followers[0] = LiveCreateNbt.load(level, positions[0], shaft, pending[0]);
            level.setBlockEntity(followers[0]);
        }
        BlockEntity[] generators = new BlockEntity[2];
        for (int i = 0; i < 2; i++) {
            level.setBlock(roots[i], motor, 18); level.removeBlockEntity(roots[i]);
            CompoundTag tag = motorNbt.copy();
            tag.put("Network", pending[i].getCompound("Network").copy());
            tag.getCompound("Network").putLong("Id", roots[i].asLong());
            tag.getCompound("Network").putFloat("AddedCapacity", motorNbt.getCompound("Network").getFloat("AddedCapacity"));
            generators[i] = LiveCreateNbt.load(level, roots[i], motor, tag);
            level.setBlockEntity(generators[i]);
        }
        // Follower admission resolves exact loaded roots before either root ticks.
        tickAll(new BlockEntity[]{followers[0], followers[1], generators[0], generators[1]}, reverse, 260);
        assertIsolatedAliases(followers, baseCapacity);
        for (int i = 0; i < 2; i++) {
            Object net = call(followers[i], "getOrCreateNetwork");
            require(net == call(generators[i], "getOrCreateNetwork") && ((Map<?, ?>) field(net, "members")).size() == 2,
                "resolved follower failed to join its exact root");
            require(!LiveCreateNbt.save(level, followers[i]).contains("EndlessLegacyNetworkId"), "resolved marker retained");
            retire(followers[i]); retire(generators[i]);
            level.setBlock(roots[i], Blocks.AIR.defaultBlockState(), 18);
            level.setBlock(positions[i], Blocks.AIR.defaultBlockState(), 18);
        }
        System.out.println("ENDLESS_CREATE_UNRESOLVED_ALIASES_PASS baseY=" + baseY + " reverse=" + reverse
            + " sourceLoaded=false independentTotals=true pendingRestart=true exactRootRejoined=true");
    }

    private static void assertIsolatedAliases(BlockEntity[] followers, float baseCapacity) throws ReflectiveOperationException {
        Object first = call(followers[0], "getOrCreateNetwork"), second = call(followers[1], "getOrCreateNetwork");
        require(first != second && !field(followers[0], "network").equals(field(followers[1], "network")),
            "source-unavailable legacy aliases share a network");
        for (int i = 0; i < 2; i++) {
            Object net = i == 0 ? first : second;
            require(((Map<?, ?>) field(net, "members")).containsKey(followers[i])
                && !((Map<?, ?>) field(net, "members")).containsKey(followers[1 - i]), "aliased membership mixed");
            require(((Number) call(net, "getSize")).intValue() == 3 + i * 2
                && number(call(net, "calculateCapacity")) == baseCapacity + i * 512f
                && number(call(net, "calculateStress")) == 64f + i * 64f,
                "aliased saved aggregates mixed or unloaded admission doubled");
            require(number(call(followers[i], "getSpeed")) == 16f, "isolated follower speed changed");
        }
    }

    private static void retire(BlockEntity entity) throws ReflectiveOperationException {
        Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity")
            .getMethod("setNetwork", Long.class).invoke(entity, new Object[]{null});
    }

    private static Object assertConnected(BlockEntity[] entities) throws ReflectiveOperationException {
        Object network = call(entities[0], "getOrCreateNetwork");
        Map<?, ?> members = (Map<?, ?>) field(network, "members");
        float expectedStress = 0;
        float expectedCapacity = 0;
        for (BlockEntity entity : entities) {
            require(call(entity, "getOrCreateNetwork") == network && members.containsKey(entity),
                "connected Create machinery split across network objects");
            require(number(call(entity, "getSpeed")) != 0, "connected machinery stopped");
            expectedStress += number(call(entity, "calculateStressApplied")) * Math.abs(number(call(entity, "getTheoreticalSpeed")));
            expectedCapacity += number(call(entity, "calculateAddedStressCapacity")) * Math.abs(number(call(entity, "getGeneratedSpeed")));
        }
        require(members.size() == entities.length, "stale or duplicate network membership");
        require(expectedStress > 0 && expectedCapacity > expectedStress, "fixture lacks a real stress consumer/capacity");
        require(Math.abs(number(call(network, "calculateStress")) - expectedStress) < .01f, "network stress accounting diverged");
        require(Math.abs(number(call(network, "calculateCapacity")) - expectedCapacity) < .01f, "network capacity accounting diverged");
        for (BlockEntity entity : entities) {
            require(Math.abs(number(field(entity, "stress")) - expectedStress) < .01f, "member stress did not synchronize");
            require(Math.abs(number(field(entity, "capacity")) - expectedCapacity) < .01f, "member capacity did not synchronize");
        }
        return network;
    }

    private static void tickAll(BlockEntity[] entities, boolean reverse, int count) throws ReflectiveOperationException {
        for (int tick = 0; tick < count; tick++) {
            for (int i = 0; i < entities.length; i++) call(entities[reverse ? entities.length - 1 - i : i], "tick");
        }
    }
    private static BlockState block(String id) {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:" + id));
        require(block != Blocks.AIR, "Create fixture block missing: " + id);
        return block.defaultBlockState();
    }
    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static float number(Object value) { return ((Number) value).floatValue(); }
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
