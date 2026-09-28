package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateKineticIdData;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gives Create sparse generators collision-free, world-persistent network IDs. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity", remap = false)
public abstract class CreateGeneratingKineticBlockEntityMixin {

    @Inject(method = "createNetworkId", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void endless$createSparseNetworkId(CallbackInfoReturnable<Long> cir) {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        BlockPos pos = self.getBlockPos();
        if (!(level instanceof ServerLevel serverLevel)
            || !EndlessVerticalEngine.isExtendedY(level, pos.getY())) {
            return;
        }

        cir.setReturnValue(CreateKineticIdData.idFor(serverLevel, pos));
    }
}