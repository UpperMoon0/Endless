package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateKineticIdData;
import com.nstut.endless.compat.create.CreateKineticMigration;
import com.nstut.endless.compat.create.CreateKineticNetworkAccess;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.core.BlockPos;
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
    @Shadow(remap = false) protected float lastStressApplied;
    @Shadow(remap = false) protected float lastCapacityProvided;
    @Shadow(remap = false) protected float capacity;
    @Shadow(remap = false) protected float stress;
    @Shadow(remap = false) private int networkSize;
    @Shadow(remap = false) public abstract void setSource(BlockPos pos);
    @Shadow(remap = false) public abstract void attachKinetics();
    @Unique private float endless$savedCapacity;
    @Unique private float endless$savedStress;
    @Unique private int endless$savedSize;
    @Unique private boolean endless$initialized;
    @Unique private boolean endless$savedLegacyNetwork;

    @Inject(method = "read", at = @At("RETURN"), require = 1, remap = false)
    private void endless$rememberSavedNetwork(CallbackInfo ci) {
        endless$initialized = false;
        endless$savedCapacity = capacity;
        endless$savedStress = stress;
        endless$savedSize = networkSize;
        endless$savedLegacyNetwork = network != null && !CreateKineticIdData.isSyntheticId(network);
    }

    @Override
    @Unique
    public Long endless$getKineticNetworkId() {
        return network;
    }

    @Inject(method = "initialize", at = @At("HEAD"), require = 1, remap = false)
    private void endless$repairSparseGeneratorNetworkId(CallbackInfo ci) {
        endless$migrateRoot();
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
    }

    @Inject(method = "attachKinetics", at = @At("HEAD"), require = 1, remap = false)
    private void endless$alignFollowerBeforePropagation(CallbackInfo ci) {
        // A late follower's tick attaches before initialize(). Align it first,
        // otherwise equal-speed propagation can pull the already migrated root
        // back into the follower's legacy network before the return hook runs.
        BlockEntity self = (BlockEntity) (Object) this;
        boolean restoreSaved = endless$savedLegacyNetwork && !endless$initialized
            && self.getLevel() instanceof ServerLevel
            && EndlessVerticalEngine.isExtendedY(self.getLevel(), self.getBlockPos().getY());
        endless$migrateRoot();
        endless$alignSourceNetwork();
        if (restoreSaved && network != null) {
            CreateKineticMigration.restoreBeforePropagation((BlockEntity) (Object) this);
        }
    }

    @Inject(method = "setNetwork", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void endless$admitSavedFollower(Long target, CallbackInfo ci) {
        // Root propagation can call setSource/setNetwork on a restored follower
        // BEFORE its first tick. Native add() would then bypass addSilently's
        // unloaded subtraction when initialize later sees it already present.
        BlockEntity self = (BlockEntity) (Object) this;
        if (endless$savedLegacyNetwork && network != null
            && source != null && target != null && !target.equals(network)
            && CreateKineticIdData.isSyntheticId(target) && self.getLevel() instanceof ServerLevel serverLevel
            && CreateKineticIdData.replacesLegacyId(serverLevel, target, network)) {
            CreateKineticMigration.alignSavedFollower(self, target, true, lastCapacityProvided, lastStressApplied);
            endless$savedLegacyNetwork = false;
            ci.cancel();
        }
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

    @Unique
    private boolean endless$alignSourceNetwork() {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel serverLevel) || source == null || network == null
            || !EndlessVerticalEngine.isExtendedY(level, self.getBlockPos().getY())
            || !level.isLoaded(source)) return false;
        BlockEntity sourceEntity = level.getBlockEntity(source);
        if (!(sourceEntity instanceof CreateKineticNetworkAccess access)) return false;
        Long sourceNetwork = access.endless$getKineticNetworkId();
        if (sourceNetwork == null || sourceNetwork.equals(network)) return false;
        if (endless$savedLegacyNetwork && CreateKineticIdData.isSyntheticId(sourceNetwork)
            && CreateKineticIdData.replacesLegacyId(serverLevel, sourceNetwork, network)) {
            CreateKineticMigration.alignSavedFollower(self, sourceNetwork, endless$initialized,
                lastCapacityProvided, lastStressApplied);
            endless$savedLegacyNetwork = false;
        } else {
            setSource(source);
        }
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
