package com.nstut.endless.compat.create;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Narrow optional bridge to the identical Create 6.0.8/6.0.11 network API. */
public final class CreateKineticMigration {
    private CreateKineticMigration() {}

    // Lazy: unsupported loaders never resolve Create classes. Cache the pinned API
    // once rather than reflecting on every kinetic tick; mismatches fail loudly.
    private static final class Api {
        static final Class<?> BE;
        static final Field NETWORKS, ID, MEMBERS, NETWORK, SOURCE, INITIALIZED, CAPACITY, STRESS, SIZE, LAST_CAPACITY, LAST_STRESS;
        static final Method GET, REMOVE, SILENT, UPDATE, INIT, SYNC;
        static final java.lang.reflect.Constructor<?> NEW_NETWORK;
        static {
            try {
                BE = Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
                Class<?> net = Class.forName("com.simibubi.create.content.kinetics.KineticNetwork");
                NETWORKS = Class.forName("com.simibubi.create.content.kinetics.TorquePropagator").getDeclaredField("networks");
                NETWORKS.setAccessible(true);
                ID = net.getField("id");
                MEMBERS = net.getField("members");
                NETWORK = BE.getField("network");
                SOURCE = BE.getField("source");
                NEW_NETWORK = net.getConstructor();
                SYNC = net.getMethod("sync");
                INITIALIZED = net.getField("initialized");
                CAPACITY = kineticField("capacity"); STRESS = kineticField("stress"); SIZE = kineticField("networkSize");
                LAST_CAPACITY = kineticField("lastCapacityProvided"); LAST_STRESS = kineticField("lastStressApplied");
                INIT = net.getMethod("initFromTE", float.class, float.class, int.class);
                GET = BE.getMethod("getOrCreateNetwork");
                REMOVE = net.getMethod("remove", BE);
                SILENT = net.getMethod("addSilently", BE, float.class, float.class);
                UPDATE = net.getMethod("updateNetwork");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported Create kinetic network API", e);
            }
        }
    }

    private static Field kineticField(String name) throws ReflectiveOperationException {
        Field field = Api.BE.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Create's saved admission, before tick's pre-initialize propagation can add(). */
    public static void restoreBeforePropagation(BlockEntity member) {
        try {
            Object net = Api.GET.invoke(member);
            if (!Api.INITIALIZED.getBoolean(net)) {
                Api.INIT.invoke(net, Api.CAPACITY.getFloat(member), Api.STRESS.getFloat(member), Api.SIZE.getInt(member));
            }
            Api.SILENT.invoke(net, member, Api.LAST_CAPACITY.getFloat(member), Api.LAST_STRESS.getFloat(member));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot restore Create kinetic accounting before propagation", e);
        }
    }

    /** Seed the migrated branch from its saved aggregate, then subtract loaded members. */
    @SuppressWarnings("unchecked")
    public static void migrateRoot(BlockEntity root, Level level, long stableId,
                                   float savedCapacity, float savedStress, int savedSize) {
        try {
            Object old = Api.GET.invoke(root);
            Map<Object, Map<Long, Object>> worlds = (Map<Object, Map<Long, Object>>) Api.NETWORKS.get(null);
            Map<Long, Object> networks = worlds.get(level);
            if (!(level instanceof ServerLevel server)
                || !CreateKineticIdData.belongsTo(server, stableId, root.getBlockPos())) {
                throw new IllegalStateException("Create kinetic migration target is not owned by this root");
            }
            // Legacy packed IDs can name multiple disconnected roots. Never move
            // every member solely because it shares that Long key. Follow exact
            // loaded Source positions; unresolved followers are isolated by the
            // admission hooks until their full source chain becomes available.
            List<BlockEntity> connected = new ArrayList<>();
            connected.add(root);
            Map<Object, Float> members = (Map<Object, Float>) Api.MEMBERS.get(old);
            for (Object candidate : members.keySet()) {
                if (candidate != root && reachesRoot((BlockEntity) candidate, root, level)) {
                    connected.add((BlockEntity) candidate);
                }
            }
            Object target = networks.get(stableId);
            if (target == null) {
                target = Api.NEW_NETWORK.newInstance();
                Api.ID.set(target, Long.valueOf(stableId));
                networks.put(stableId, target);
            }
            // A follower chunk may have saved S while the root still saved L.
            // Its restored aggregate already includes the root; never reseed it.
            Long targetId = (Long) Api.ID.get(target);
            if (!Api.INITIALIZED.getBoolean(target)) {
                Api.INIT.invoke(target, savedCapacity, savedStress, savedSize);
            }
            for (BlockEntity member : connected) {
                float lastCapacity = Api.LAST_CAPACITY.getFloat(member);
                float lastStress = Api.LAST_STRESS.getFloat(member);
                Api.REMOVE.invoke(old, member);
                Api.NETWORK.set(member, targetId);
                Api.SILENT.invoke(target, member, lastCapacity, lastStress);
                member.setChanged();
            }
            Api.UPDATE.invoke(target);
            // initFromTE already cached the full totals; unchanged totals still
            // require restoring BE fields that old.remove() deliberately cleared.
            Api.SYNC.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot preserve Create kinetic branch during migration", e);
        }
    }

    private static boolean reachesRoot(BlockEntity member, BlockEntity root, Level level)
        throws ReflectiveOperationException {
        Set<BlockPos> visited = new HashSet<>();
        while (member != root) {
            if (!visited.add(member.getBlockPos())) return false;
            BlockPos source = (BlockPos) Api.SOURCE.get(member);
            if (source == null || !level.isLoaded(source)) return false;
            member = level.getBlockEntity(source);
            if (member == null || !Api.BE.isInstance(member)) return false;
        }
        return true;
    }

    /** A saved follower consumes its share of the target's unloaded aggregate once. */
    public static void alignSavedFollower(BlockEntity follower, Long target, boolean initialized,
                                          float lastCapacity, float lastStress) {
        try {
            Object previous = Api.GET.invoke(follower);
            float savedCapacity = Api.CAPACITY.getFloat(follower);
            float savedStress = Api.STRESS.getFloat(follower);
            int savedSize = Api.SIZE.getInt(follower);
            // remove() resets BE totals, so saved contribution arguments must be
            // captured by the caller before removing actual old membership.
            Api.REMOVE.invoke(previous, follower);
            Api.NETWORK.set(follower, target);
            if (initialized) {
                Object net = Api.GET.invoke(follower);
                if (!Api.INITIALIZED.getBoolean(net)) {
                    Api.INIT.invoke(net, savedCapacity, savedStress, savedSize);
                }
                Api.SILENT.invoke(net, follower, lastCapacity, lastStress);
                Api.UPDATE.invoke(net);
                Api.SYNC.invoke(net);
            }
            follower.setChanged();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot admit saved Create kinetic follower", e);
        }
    }
}
