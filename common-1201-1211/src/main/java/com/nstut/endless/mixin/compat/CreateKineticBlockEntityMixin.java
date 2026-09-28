package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateKineticIdData;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Migrates persisted sparse Create generators off vanilla packed-position network IDs before registration. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public abstract class CreateKineticBlockEntityMixin {
    @Shadow(remap = false) public Long network;
    @Shadow(remap = false) public BlockPos source;

    @Inject(method = "initialize", at = @At("HEAD"), require = 1, remap = false)
    private void endless$repairSparseGeneratorNetworkId(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        BlockPos pos = self.getBlockPos();
        if (!(level instanceof ServerLevel serverLevel)
            || !EndlessVerticalEngine.isExtendedY(level, pos.getY())
            || !endless$isGenerating(self.getClass())) {
            return;
        }

        // Only migrate the persisted identity of a generator that was its own
        // network root before Endless provided full-Y IDs. Followers persist
        // their root's network ID and must not be rewritten to their own ID;
        // stopped generators with no network must remain network-less.
        if (network == null || source != null || network.longValue() != pos.asLong()) {
            return;
        }

        long stableId = CreateKineticIdData.idFor(serverLevel, pos);
        if (network.longValue() != stableId) {
            network = stableId;
            self.setChanged();
        }
    }

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