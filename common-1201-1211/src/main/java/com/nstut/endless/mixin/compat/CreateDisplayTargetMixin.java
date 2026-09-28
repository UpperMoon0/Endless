package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateFullPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.simibubi.create.api.behaviour.display.DisplayTarget", remap = false)
public abstract class CreateDisplayTargetMixin {

    @Inject(method = "reserve", at = @At("RETURN"), require = 1, remap = false)
    private static void endless$reserveExact(
        int line,
        BlockEntity target,
        @Coerce Object context,
        CallbackInfo ci
    ) {
        if (line == 0) {
            return;
        }

        CompoundTag persistent = CreateFullPosition.persistentData(target);
        CompoundTag displayLink = persistent.getCompound("DisplayLink");
        CreateFullPosition.put(
            displayLink,
            CreateFullPosition.displayLineKey(line),
            CreateFullPosition.blockPosFromDisplayContext(context)
        );
        persistent.put("DisplayLink", displayLink);
    }

    @Inject(method = "isReserved", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void endless$isReservedExact(
        int line,
        BlockEntity target,
        @Coerce Object context,
        CallbackInfoReturnable<Boolean> cir
    ) {
        CompoundTag persistent = CreateFullPosition.persistentData(target);
        CompoundTag displayLink = persistent.getCompound("DisplayLink");
        String exactKey = CreateFullPosition.displayLineKey(line);
        if (!CreateFullPosition.contains(displayLink, exactKey)) {
            return;
        }

        BlockPos reserved = CreateFullPosition.get(displayLink, exactKey);
        BlockPos source = CreateFullPosition.blockPosFromDisplayContext(context);
        if (!reserved.equals(source) && CreateFullPosition.isCreateDisplayLink(target, reserved)) {
            cir.setReturnValue(true);
            return;
        }

        // Mirror Create's stale-reservation cleanup for both representations.
        displayLink.remove("Line" + line);
        displayLink.remove(exactKey);
        if (displayLink.isEmpty()) {
            persistent.remove("DisplayLink");
        } else {
            persistent.put("DisplayLink", displayLink);
        }
        cir.setReturnValue(false);
    }
}
