package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateKineticIdData;
import com.nstut.endless.compat.create.CreateKineticMigration;
import com.nstut.endless.compat.create.CreateKineticNetworkAccess;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Migrates sparse identities while retaining Create's persisted network accounting. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public abstract class CreateKineticBlockEntityMixin implements CreateKineticNetworkAccess {
    @Shadow(remap = false) public Long network;
    @Shadow(remap = false) public BlockPos source;
    @Shadow(remap = false) public boolean updateSpeed;
    @Shadow(remap = false) protected float lastStressApplied;
    @Shadow(remap = false) protected float lastCapacityProvided;
    @Shadow(remap = false) protected float capacity;
    @Shadow(remap = false) protected float stress;
    @Shadow(remap = false) private int networkSize;
    @Shadow(remap = false) public abstract void setSource(BlockPos pos);
    @Shadow(remap = false) public abstract void setNetwork(Long id);
    @Shadow(remap = false) public abstract void attachKinetics();
    @Unique private float endless$savedCapacity;
    @Unique private float endless$savedStress;
    @Unique private int endless$savedSize;
    @Unique private boolean endless$initialized;
    @Unique private boolean endless$savedLegacyNetwork;
    @Unique private Long endless$legacyId;
    @Unique private static final String ENDLESS_LEGACY_ID = "EndlessLegacyNetworkId";

    @Inject(method = "read", at = @At("RETURN"), require = 1, remap = false)
    private void endless$rememberSavedNetwork(CallbackInfo ci) {
        endless$initialized = false;
        endless$savedCapacity = capacity;
        endless$savedStress = stress;
        endless$savedSize = networkSize;
        if (network == null) endless$legacyId = null;
        else if (!CreateKineticIdData.isSyntheticId(network)) endless$legacyId = network;
        else if (source == null) endless$legacyId = null;
        endless$savedLegacyNetwork = endless$legacyId != null;
    }

    @Override
    @Unique
    public Long endless$getKineticNetworkId() {
        return network;
    }

    @Override @Unique
    public BlockPos endless$getKineticSource() { return source; }

    @Override @Unique
    public boolean endless$hasLegacyIdentity() { return endless$savedLegacyNetwork; }

    @Override @Unique
    public void endless$prepareKineticRoot() { endless$migrateRoot(); }

    @Override @Unique
    public void endless$readLegacyMarker(CompoundTag tag) {
        endless$legacyId = tag.contains(ENDLESS_LEGACY_ID, Tag.TAG_LONG)
            ? tag.getLong(ENDLESS_LEGACY_ID) : null;
    }

    @Override @Unique
    public void endless$writeLegacyMarker(CompoundTag tag) {
        if (endless$savedLegacyNetwork && endless$legacyId != null && source != null
            && network != null && CreateKineticIdData.isSyntheticId(network)) {
            tag.putLong(ENDLESS_LEGACY_ID, endless$legacyId);
        } else tag.remove(ENDLESS_LEGACY_ID);
    }

    @Inject(method = "initialize", at = @At("HEAD"), require = 1, remap = false)
    private void endless$repairSparseGeneratorNetworkId(CallbackInfo ci) {
        endless$migrateRoot();
        endless$alignSourceNetwork();
        endless$isolateUnresolved();
    }

    @Unique
    private void endless$migrateRoot() {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        BlockPos pos = self.getBlockPos();
        if (!(level instanceof ServerLevel serverLevel)
            || !EndlessVerticalEngine.isExtendedY(level, pos.getY())
            || !endless$isGenerating(self.getClass())
            || network == null || source != null || network.longValue() != pos.asLong()) {
            return;
        }

        long stableId = CreateKineticIdData.idFor(serverLevel, pos);
        if (network.longValue() == stableId) return;

        CreateKineticMigration.migrateRoot(self, level, stableId,
            endless$savedCapacity, endless$savedStress, endless$savedSize);
        endless$savedLegacyNetwork = false;
        endless$legacyId = null;
    }

    @Inject(method = "attachKinetics", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void endless$alignFollowerBeforePropagation(CallbackInfo ci) {
        // A late follower's tick attaches before initialize(). Align it first,
        // otherwise equal-speed propagation can pull the already migrated root
        // back into the follower's legacy network before the return hook runs.
        BlockEntity self = (BlockEntity) (Object) this;
        boolean restoreSaved = EndlessLogicalHeights.isActive() && endless$savedLegacyNetwork && !endless$initialized
            && self.getLevel() instanceof ServerLevel;
        endless$migrateRoot();
        endless$alignSourceNetwork();
        if (endless$isolateUnresolved()) {
            // A provisional identity must not propagate into adjacent saved BEs.
            // Validation retries the exact Source chain without loading chunks.
            updateSpeed = false;
            ci.cancel();
            return;
        }
        if (restoreSaved && network != null) {
            CreateKineticMigration.restoreBeforePropagation((BlockEntity) (Object) this);
        }
    }

    @Inject(method = "setNetwork", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void endless$admitSavedFollower(Long target, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (target == null || source == null) {
            endless$savedLegacyNetwork = false;
            endless$legacyId = null;
            return;
        }
        if (!EndlessLogicalHeights.isActive() || !endless$savedLegacyNetwork || network == null
            || !(self.getLevel() instanceof ServerLevel)) return;
        // Propagation can reach a saved follower before its first tick. Resolve
        // full Source positions before accepting even an equal packed ID.
        if (endless$alignSourceNetwork() || endless$isolateUnresolved()) ci.cancel();
    }

    @Inject(method = "initialize", at = @At("RETURN"), require = 1, remap = false)
    private void endless$finishInitialization(CallbackInfo ci) {
        endless$initialized = true;
        if (endless$alignSourceNetwork()) attachKinetics();
    }

    @Inject(method = "validateKinetics", at = @At("RETURN"), require = 1, remap = false)
    private void endless$reconcileLateLoadedFollower(CallbackInfo ci) {
        if (endless$alignSourceNetwork()) attachKinetics();
    }

    @Inject(method = "validateKinetics", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void endless$holdUnresolvedIdentity(CallbackInfo ci) {
        if (endless$alignSourceNetwork()) attachKinetics();
        if (endless$isolateUnresolved()) ci.cancel();
    }

    @Unique
    private Long endless$resolvedSourceNetwork() {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel) || source == null) return null;
        Set<BlockPos> visited = new HashSet<>();
        visited.add(self.getBlockPos());
        List<CreateKineticNetworkAccess> pending = new ArrayList<>();
        Long resolved = null;
        BlockPos next = source;
        while (next != null && visited.add(next) && level.isLoaded(next)) {
            BlockEntity entity = level.getBlockEntity(next);
            if (!(entity instanceof CreateKineticNetworkAccess access)) {
                // The chunk is available and its saved source no longer exists.
                // Let native validation remove the source rather than preserving
                // a provisional identity forever after a real topology change.
                endless$savedLegacyNetwork = false;
                endless$legacyId = null;
                self.setChanged();
                return null;
            }
            access.endless$prepareKineticRoot();
            Long id = access.endless$getKineticNetworkId();
            if (id != null && !access.endless$hasLegacyIdentity()) { resolved = id; break; }
            pending.add(access);
            next = access.endless$getKineticSource();
            // A dense root retains its unambiguous native identity.
            if (next == null && !EndlessVerticalEngine.isExtendedY(level, entity.getBlockPos().getY())) { resolved = id; break; }
        }
        if (resolved != null) {
            for (int i = pending.size() - 1; i >= 0; i--) pending.get(i).endless$acceptResolvedNetwork(resolved);
        }
        return resolved;
    }

    @Unique
    private boolean endless$isolateUnresolved() {
        BlockEntity self = (BlockEntity) (Object) this;
        if (!EndlessLogicalHeights.isActive() || !endless$savedLegacyNetwork || network == null || source == null
            || !(self.getLevel() instanceof ServerLevel server)) return false;
        // The packed legacy ID cannot tell which of the Y-period aliases owns
        // an unavailable source. Persist one private identity per full position,
        // retaining the original ID for later unloaded-contribution admission.
        long provisional = CreateKineticIdData.idForUnresolvedFollower(server, self.getBlockPos());
        if (network.longValue() != provisional) {
            CreateKineticMigration.alignSavedFollower(self, provisional, true,
                lastCapacityProvided, lastStressApplied);
        }
        return true;
    }

    @Unique
    private boolean endless$alignSourceNetwork() {
        BlockEntity self = (BlockEntity) (Object) this;
        if (!EndlessLogicalHeights.isActive() || !(self.getLevel() instanceof ServerLevel)
            || source == null || network == null) return false;
        if (!endless$savedLegacyNetwork
            && !EndlessVerticalEngine.isExtendedY(self.getLevel(), self.getBlockPos().getY())) return false;
        Long resolved = endless$resolvedSourceNetwork();
        if (resolved == null) return false;
        return endless$acceptResolvedNetwork(resolved);
    }

    @Override @Unique
    public boolean endless$acceptResolvedNetwork(Long resolved) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel server) || network == null) return false;
        if (endless$savedLegacyNetwork && endless$legacyId != null
            && (resolved.equals(endless$legacyId)
                || CreateKineticIdData.isSyntheticId(resolved)
                    && CreateKineticIdData.replacesLegacyId(server, resolved, endless$legacyId))) {
            if (!resolved.equals(network)) {
                CreateKineticMigration.alignSavedFollower(self, resolved, true,
                    lastCapacityProvided, lastStressApplied);
            }
            endless$savedLegacyNetwork = false;
            endless$legacyId = null;
            self.setChanged();
            return true;
        }
        if (resolved.equals(network)) return false;
        // A genuinely changed topology follows Create's normal add semantics.
        endless$savedLegacyNetwork = false;
        endless$legacyId = null;
        setNetwork(resolved);
        return true;
    }

    @Unique
    private static boolean endless$isGenerating(Class<?> type) {
        while (type != null) {
            if ("com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity".equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }
}
