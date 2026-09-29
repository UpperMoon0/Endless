package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateKineticIdData;
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

/** Rebuilds legacy sparse networks through Create's own membership/propagation lifecycle. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public abstract class CreateKineticBlockEntityMixin implements CreateKineticNetworkAccess {
    @Shadow(remap = false) public Long network;
    @Shadow(remap = false) public BlockPos source;
    @Shadow(remap = false) public abstract void setNetwork(Long id);
    @Shadow(remap = false) public abstract void setSource(BlockPos pos);
    @Shadow(remap = false) public abstract void detachKinetics();
    @Shadow(remap = false) public abstract void attachKinetics();

    @Override
    @Unique
    public Long endless$getKineticNetworkId() {
        return network;
    }

    @Inject(method = "initialize", at = @At("HEAD"), require = 1, remap = false)
    private void endless$repairSparseGeneratorNetworkId(CallbackInfo ci) {
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

        // tick() attaches BEFORE initialize(). Followers may already have joined
        // the legacy network. Clear their old sources, move this root using
        // setNetwork (which removes/adds actual membership), and propagate again.
        // A field-only change leaves rotating followers in an unrelated network.
        detachKinetics();
        setNetwork(stableId);
        attachKinetics();
        self.setChanged();
    }

    @Inject(method = "attachKinetics", at = @At("HEAD"), require = 1, remap = false)
    private void endless$alignFollowerBeforePropagation(CallbackInfo ci) {
        // A late follower's tick attaches before initialize(). Align it first,
        // otherwise equal-speed propagation can pull the already migrated root
        // back into the follower's legacy network before the return hook runs.
        endless$alignSourceNetwork();
    }

    @Inject(method = {"initialize", "validateKinetics"}, at = @At("RETURN"), require = 1, remap = false)
    private void endless$reconcileLateLoadedFollower(CallbackInfo ci) {
        if (endless$alignSourceNetwork()) attachKinetics();
    }

    @Unique
    private boolean endless$alignSourceNetwork() {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel) || source == null || network == null
            || !EndlessVerticalEngine.isExtendedY(level, self.getBlockPos().getY())
            || !level.isLoaded(source)) return false;
        BlockEntity sourceEntity = level.getBlockEntity(source);
        if (!(sourceEntity instanceof CreateKineticNetworkAccess access)) return false;
        Long sourceNetwork = access.endless$getKineticNetworkId();
        if (sourceNetwork == null || sourceNetwork.equals(network)) return false;
        setSource(source);
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
